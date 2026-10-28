package com.heybuddy.data

import com.google.crypto.tink.KeysetHandle
import com.heybuddy.files.DetectedFileType
import com.heybuddy.files.findOrphanIds
import com.heybuddy.security.DocumentUnreadableException
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.LockStatus
import com.heybuddy.security.PageBinding
import com.heybuddy.security.VaultLockState
import com.heybuddy.security.VerifiedBytes
import com.heybuddy.security.VerifiedFile
import com.heybuddy.util.LogEvent
import com.heybuddy.util.PAGE_FORMAT_CURRENT
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID

/**
 * A document as screens see it: no keys, no file details.
 *
 * @property id The document's id.
 * @property displayName The user's name for it.
 * @property type What kind of document it is.
 * @property pageCount How many pages it has (at least 1).
 * @property createdAt When it was added (epoch milliseconds).
 * @property updatedAt When it was last changed (epoch milliseconds).
 */
data class DocumentSummary(
    val id: String,
    val displayName: String,
    val type: DocumentType,
    val pageCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One page as screens see it (no key).
 *
 * @property pageId The page's id.
 * @property position Its place in the document, starting at 0.
 * @property mimeType The detected file type.
 * @property sizeBytes The original file size.
 */
data class DocumentPage(
    val pageId: String,
    val position: Int,
    val mimeType: String,
    val sizeBytes: Long,
) {
    /** True for a PDF page file (it may hold several PDF pages inside). */
    val isPdf: Boolean
        get() = mimeType == DetectedFileType.PDF.mimeType
}

/**
 * A document with its pages in order.
 *
 * @property summary The document.
 * @property pages Its pages, first page first.
 */
data class DocumentDetails(val summary: DocumentSummary, val pages: List<DocumentPage>)

/** No document with this id exists (for example it was deleted on another screen). */
class DocumentNotFoundException : NoSuchElementException("Document not found")

/** No such page exists in this document (for example it was removed meanwhile). */
class PageNotFoundException : NoSuchElementException("Page not found")

/** One page decrypted and verified for the viewer. The viewer must release it when it is not shown. */
sealed interface OpenedPage {

    /** The page's details (no key). */
    val page: DocumentPage

    /**
     * A photo, verified to the end, in memory.
     *
     * @property bytes The verified file bytes. Wipe them as soon as the picture is decoded.
     */
    data class Image(override val page: DocumentPage, val bytes: VerifiedBytes) : OpenedPage

    /**
     * A PDF, verified to the end, in view_tmp (rule 6). Delete it when it is no longer shown.
     *
     * @property file The verified file.
     */
    data class Pdf(override val page: DocumentPage, val file: VerifiedFile) : OpenedPage
}

/**
 * A page that is encrypted into vault/<pageId>.bin.pending but not saved yet (Add screen).
 *
 * It keeps the page's key in memory until the document is saved, so picking a file never asks for
 * fingerprint/PIN; the key is wrapped by the master key only at Save. Pass it to
 * [VaultRepository.saveDocument] or [VaultRepository.addPages], or to
 * [VaultRepository.discardPreparedPages] on cancel and lock. Only the repository can make one.
 *
 * @property documentId The document it will belong to.
 * @property pageId Its id (and file name).
 * @property mimeType The type detected from its bytes.
 * @property sizeBytes Its original size.
 */
class PreparedPage internal constructor(
    val documentId: String,
    val pageId: String,
    val mimeType: String,
    val sizeBytes: Long,
    internal val pageKey: KeysetHandle,
)

/**
 * The only class screens use for documents (spec 5.7).
 *
 * Every database step goes through [VaultDatabaseHolder.withDatabase], so it never overlaps the
 * close-on-lock or the orphan cleanup. Changes run as NonCancellable once they start: a cancelled
 * screen must never stop them between "files moved" and "rows written".
 *
 * ORDERS (Task 1.10p), chosen so a crash at any moment leaves only harmless, unreadable files:
 * - Save / add pages: wrap every key first (this is where an expired fingerprint/PIN window fails,
 *   before anything changed), then rename the pending files to .bin, then write all rows in ONE
 *   transaction. If the rows fail, the files go back to pending.
 * - Remove a page: delete its row (with its key) and renumber, in one transaction; then its file.
 * - Delete a document: delete it and all page rows (with all keys) in one transaction; then the files.
 *
 * @param holder Opens the database while unlocked.
 * @param lockState The app's lock, for [observeDocuments] and [preparePage].
 * @param keyWrapper Creates and wraps page keys.
 * @param fileCrypto Encrypts pages into the vault.
 * @param clock Current time in epoch milliseconds (tests pass a fixed clock).
 * @param newId Makes new document and page ids (tests can force specific ids).
 * @param ioDispatcher Where file and database work runs.
 */
class VaultRepository(
    private val holder: VaultDatabaseHolder,
    private val lockState: VaultLockState,
    private val keyWrapper: KeyWrapper,
    private val fileCrypto: FileCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * A fresh id for a document that is about to be put together on the Add screen.
     *
     * @return A new UUID.
     */
    fun newDocumentId(): String = newId()

    /**
     * Encrypts one picked file as a pending page of [documentId]. Needs no fingerprint/PIN.
     *
     * @param documentId The document it will belong to (new or existing).
     * @param input The page's bytes. Not closed here.
     * @param mimeType The type detected from the bytes (Task 1.7).
     * @return The prepared page.
     * @throws VaultLockedException If the vault is locked.
     * @throws com.heybuddy.files.FileTooLargeException If the file is over MAX_FILE_BYTES.
     */
    suspend fun preparePage(documentId: String, input: InputStream, mimeType: String): PreparedPage {
        if (lockState.status.value != LockStatus.UNLOCKED) throw VaultLockedException()
        return withContext(ioDispatcher) {
            val pageId = newId()
            val binding = PageBinding.forNewPage(documentId, pageId)
            val pageKey = keyWrapper.generatePageKey()
            val sizeBytes = fileCrypto.encryptToPending(input, pageKey, binding)
            PreparedPage(documentId, pageId, mimeType, sizeBytes, pageKey)
        }
    }

    /**
     * Deletes the pending files of pages that will not be saved (cancel, page removed, lock).
     * Pages that were already saved are not touched.
     *
     * @param pages The pages to throw away.
     */
    fun discardPreparedPages(pages: List<PreparedPage>) {
        for (page in pages) {
            fileCrypto.deletePending(page.pageId)
        }
    }

    /**
     * Saves a new document with its pages, in the order given.
     *
     * @param documentId The id the pages were prepared for.
     * @param pages The prepared pages, first page first.
     * @param displayName The name the user typed.
     * @param type The kind of document.
     * @return The saved document.
     * @throws InvalidDisplayNameException If the name breaks a rule.
     * @throws PageLimitException If the pages break a document limit.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun saveDocument(
        documentId: String,
        pages: List<PreparedPage>,
        displayName: String,
        type: DocumentType,
    ): DocumentSummary {
        val cleanName = requireValidName(displayName)
        requirePagesOf(documentId, pages)
        requireWithinLimits(existingPageCount = 0, existingBytes = 0L, pages = pages)
        val summary = withContext(ioDispatcher + NonCancellable) {
            val wrappedKeys = wrapKeys(pages)
            holder.withDatabase { database ->
                val now = clock()
                val document = DocumentEntity(documentId, cleanName, type.name, createdAt = now, updatedAt = now)
                val rows = pageRows(pages, wrappedKeys, firstPosition = 0)
                commitFilesThenRows(pages) { database.documentDao().insertDocumentWithPages(document, rows) }
                document.toSummary(pageCount = pages.size)
            }
        }
        SafeLog.info(LogEvent.DOCUMENT_SAVED, count = pages.size)
        return summary
    }

    /**
     * Saves a one-page document in one step (prepare + save). Used where there is no Add screen
     * in between, for example in tests.
     *
     * @param input The page's bytes. Not closed here.
     * @param displayName The name the user typed.
     * @param type The kind of document.
     * @param mimeType The type detected from the bytes.
     * @return The saved document.
     * @throws InvalidDisplayNameException If the name breaks a rule.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun saveDocument(
        input: InputStream,
        displayName: String,
        type: DocumentType,
        mimeType: String,
    ): DocumentSummary {
        requireValidName(displayName)
        val documentId = newDocumentId()
        val page = preparePage(documentId, input, mimeType)
        try {
            return saveDocument(documentId, listOf(page), displayName, type)
        } finally {
            // After a successful save the pending file is gone already; after a failure it must go.
            discardPreparedPages(listOf(page))
        }
    }

    /**
     * Adds pages to the end of an existing document.
     *
     * @param documentId The document's id.
     * @param pages Pages prepared for this document.
     * @throws DocumentNotFoundException If the document does not exist (any more).
     * @throws PageLimitException If the document would break a limit.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun addPages(documentId: String, pages: List<PreparedPage>) {
        requirePagesOf(documentId, pages)
        withContext(ioDispatcher + NonCancellable) {
            val wrappedKeys = wrapKeys(pages)
            holder.withDatabase { database ->
                val dao = database.documentDao()
                dao.findById(documentId) ?: throw DocumentNotFoundException()
                val existing = dao.pagesOf(documentId)
                requireWithinLimits(existing.size, existing.sumOf { page -> page.sizeBytes }, pages)
                val rows = pageRows(pages, wrappedKeys, firstPosition = existing.size)
                commitFilesThenRows(pages) { dao.appendPages(documentId, rows, clock()) }
            }
        }
        SafeLog.info(LogEvent.PAGES_ADDED, count = pages.size)
    }

    /**
     * Removes one page for good (rule 13): its row and key first, then its file. The remaining
     * pages are numbered again with no gap.
     *
     * @param documentId The page's document.
     * @param pageId The page to remove.
     * @throws PageNotFoundException If the document has no such page.
     * @throws LastPageException If it is the only page (delete the document instead).
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun removePage(documentId: String, pageId: String) {
        withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database ->
                val dao = database.documentDao()
                val pages = dao.pagesOf(documentId)
                if (pages.none { page -> page.pageId == pageId }) throw PageNotFoundException()
                if (pages.size == 1) throw LastPageException()
                dao.removePageAndRenumber(documentId, pageId, clock())
                fileCrypto.encryptedFileFor(pageId).delete()
            }
        }
        SafeLog.info(LogEvent.PAGE_REMOVED)
    }

    /**
     * Watches the document list, newest first.
     *
     * When the vault locks, the list ENDS quietly (it completes, no error), even if the database
     * closes while it is being watched. Starting it while locked ends it at once.
     *
     * @return A live list of documents.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDocuments(): Flow<List<DocumentSummary>> {
        return lockState.status
            .flatMapLatest { status ->
                if (status == LockStatus.UNLOCKED) observeWhileUnlocked() else flowOf(LIST_ENDED)
            }
            .takeWhile { documents -> documents !== LIST_ENDED }
    }

    /**
     * Reads a document and its page list. Decrypts nothing and needs no fingerprint/PIN.
     *
     * @param documentId The document's id.
     * @return The document and its pages in order.
     * @throws DocumentNotFoundException If there is no such document.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun documentDetails(documentId: String): DocumentDetails {
        return withContext(ioDispatcher) {
            holder.withDatabase { database ->
                val dao = database.documentDao()
                val document = dao.findById(documentId) ?: throw DocumentNotFoundException()
                val pages = dao.pagesOf(documentId).map { row -> row.toPage() }
                DocumentDetails(document.toSummary(pageCount = pages.size), pages)
            }
        }
    }

    /**
     * Decrypts one page for viewing, fully verified (rule 6, Task 1.5).
     *
     * Needs the master key to unwrap the page's key, so after the fingerprint/PIN window it throws
     * UserNotAuthenticatedException; call it through MasterKeyAccess, which asks once and retries.
     * Only the row is read under the database lock; the (slower) decryption runs outside it.
     *
     * @param documentId The page's document.
     * @param pageId The page's id.
     * @return The verified page. The caller must release it (wipe bytes / delete the file).
     * @throws PageNotFoundException If the document has no such page.
     * @throws DocumentUnreadableException If the stored page or its file fails verification.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun openPage(documentId: String, pageId: String): OpenedPage {
        return withContext(ioDispatcher) {
            val row = holder.withDatabase { database -> database.documentDao().findPage(pageId) }
            if (row == null || row.documentId != documentId) throw PageNotFoundException()
            val binding = bindingOf(row)
            val pageKey = keyWrapper.unwrapPageKey(row.wrappedKey, binding)
            val page = row.toPage()
            if (page.isPdf) {
                val destination = fileCrypto.newViewTempFile()
                OpenedPage.Pdf(page, fileCrypto.decryptVerifiedToFile(binding, pageKey, destination))
            } else {
                OpenedPage.Image(page, fileCrypto.decryptVerified(binding, pageKey))
            }
        }
    }

    /**
     * Changes a document's name.
     *
     * @param documentId The document's id.
     * @param newName The name the user typed.
     * @return true if renamed, false if no such document exists.
     * @throws InvalidDisplayNameException If the name breaks a rule.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun renameDocument(documentId: String, newName: String): Boolean {
        val cleanName = requireValidName(newName)
        val changedRows = withContext(ioDispatcher) {
            holder.withDatabase { database ->
                database.documentDao().rename(documentId, cleanName, clock())
            }
        }
        if (changedRows > 0) SafeLog.info(LogEvent.DOCUMENT_RENAMED)
        return changedRows > 0
    }

    /**
     * Deletes a document and all its pages for good (rule 13, crypto-shredding).
     *
     * Order: the rows go first, in one transaction, because they hold the wrapped keys; from that
     * moment no page file can ever be decrypted again. Then the .bin files are deleted. If the app
     * dies in between, only unreadable files are left, and the orphan cleanup removes them.
     *
     * @param documentId The document's id.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun deleteDocument(documentId: String) {
        withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database ->
                val pageIds = database.documentDao().deleteDocumentWithPages(documentId)
                deleteFiles(pageIds)
            }
        }
        SafeLog.info(LogEvent.DOCUMENT_DELETED)
    }

    /**
     * Deletes encrypted page files that no row points to (left only by a crash). Runs after unlock.
     *
     * Runs INSIDE [VaultDatabaseHolder.withDatabase], so it can never overlap a save: during a save
     * the new .bin files exist for a moment before their rows, and must not be mistaken for orphans.
     * Only files named exactly "<valid UUID>.bin" are considered (owner rule B); pending and other
     * files are left alone. Only the NUMBER of removed files is logged, never names.
     *
     * @return How many files were removed.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun removeOrphanFiles(): Int {
        val removed = withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database ->
                val knownPageIds = database.documentDao().allPageIds().toSet()
                val orphanIds = findOrphanIds(fileCrypto.vaultFileNames(), knownPageIds)
                deleteFiles(orphanIds)
            }
        }
        SafeLog.info(LogEvent.ORPHAN_FILES_REMOVED, count = removed)
        return removed
    }

    private fun deleteFiles(pageIds: List<String>): Int {
        var removed = 0
        for (pageId in pageIds) {
            if (fileCrypto.encryptedFileFor(pageId).delete()) removed++
        }
        return removed
    }

    /** Wraps every page key with the master key. Fails before anything is written if the window expired. */
    private fun wrapKeys(pages: List<PreparedPage>): List<ByteArray> {
        return pages.map { page ->
            keyWrapper.wrapPageKey(page.pageKey, PageBinding.forNewPage(page.documentId, page.pageId))
        }
    }

    private fun pageRows(pages: List<PreparedPage>, wrappedKeys: List<ByteArray>, firstPosition: Int): List<DocumentPageEntity> {
        return pages.mapIndexed { index, page ->
            DocumentPageEntity(
                pageId = page.pageId,
                documentId = page.documentId,
                position = firstPosition + index,
                mimeType = page.mimeType,
                sizeBytes = page.sizeBytes,
                wrappedKey = wrappedKeys[index],
                formatVersion = PAGE_FORMAT_CURRENT,
            )
        }
    }

    /**
     * Moves the pending files into the vault, then writes the rows. If anything fails, the files
     * go back to pending, so no .bin is left without a row and the user can simply try again.
     */
    private suspend fun commitFilesThenRows(pages: List<PreparedPage>, writeRows: suspend () -> Unit) {
        val committedPageIds = mutableListOf<String>()
        try {
            for (page in pages) {
                fileCrypto.commitPending(page.pageId)
                committedPageIds.add(page.pageId)
            }
            writeRows()
        } catch (failure: Exception) {
            for (pageId in committedPageIds) {
                fileCrypto.returnToPending(pageId)
            }
            SafeLog.error(LogEvent.DOCUMENT_SAVE_ROLLED_BACK, failure)
            throw failure
        }
    }

    private fun requirePagesOf(documentId: String, pages: List<PreparedPage>) {
        require(pages.isNotEmpty()) { "A document needs at least one page" }
        require(pages.all { page -> page.documentId == documentId }) { "Every page must be prepared for this document" }
    }

    private fun requireWithinLimits(existingPageCount: Int, existingBytes: Long, pages: List<PreparedPage>) {
        val newSizes = pages.map { page -> page.sizeBytes }
        val problem = pageLimitProblem(existingPageCount, existingBytes, newSizes)
        if (problem != null) throw PageLimitException(problem)
    }

    private fun bindingOf(row: DocumentPageEntity): PageBinding {
        return try {
            PageBinding.forStoredPage(row.documentId, row.pageId, row.formatVersion)
        } catch (badRow: IllegalArgumentException) {
            // A row that no version of the app writes: treat it like a damaged page.
            throw DocumentUnreadableException(badRow)
        }
    }

    private fun observeWhileUnlocked(): Flow<List<DocumentSummary>> {
        val rows = flow {
            val database = holder.withDatabase { openDatabase -> openDatabase }
            emitAll(database.documentDao().observeAll())
        }
        return rows
            .map { listRows -> listRows.map { row -> row.toSummary() } }
            // The database can close (on lock) just before this flow is cancelled. Any error from
            // that, or a lock that came first, simply ends the list instead of crashing the screen.
            .catch { emit(LIST_ENDED) }
    }

    private fun requireValidName(rawName: String): String {
        val result = DisplayNameValidator.validate(rawName)
        return when (result) {
            is DisplayNameResult.Valid -> result.cleanName
            is DisplayNameResult.Invalid -> throw InvalidDisplayNameException(result.problem)
        }
    }

    private companion object {
        /** Marker that tells [observeDocuments] to end. Compared by identity, never shown. */
        val LIST_ENDED: List<DocumentSummary> = ArrayList()
    }
}

private fun DocumentEntity.toSummary(pageCount: Int): DocumentSummary {
    return DocumentSummary(
        id = id,
        displayName = displayName,
        type = DocumentType.fromStoredValue(type),
        pageCount = pageCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun DocumentListRow.toSummary(): DocumentSummary {
    return DocumentSummary(
        id = id,
        displayName = displayName,
        type = DocumentType.fromStoredValue(type),
        pageCount = pageCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun PageListRow.toPage(): DocumentPage {
    return DocumentPage(pageId = pageId, position = position, mimeType = mimeType, sizeBytes = sizeBytes)
}

private fun DocumentPageEntity.toPage(): DocumentPage {
    return DocumentPage(pageId = pageId, position = position, mimeType = mimeType, sizeBytes = sizeBytes)
}

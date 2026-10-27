package com.heybuddy.data

import com.heybuddy.files.DetectedFileType
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.VerifiedBytes
import com.heybuddy.security.VerifiedFile
import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.LogEvent
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
 * A document as screens see it: everything except the wrapped key.
 *
 * @property id The document's id.
 * @property displayName The user's name for it.
 * @property type What kind of document it is.
 * @property mimeType The detected file type.
 * @property sizeBytes The original file size.
 * @property createdAt When it was added (epoch milliseconds).
 * @property updatedAt When it was last changed (epoch milliseconds).
 */
data class DocumentSummary(
    val id: String,
    val displayName: String,
    val type: DocumentType,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** No document with this id exists (for example it was deleted on another screen). */
class DocumentNotFoundException : NoSuchElementException("Document not found")

/** A document decrypted and verified for the viewer. The viewer must release it when it closes. */
sealed interface OpenedDocument {

    /** The document's details (no key). */
    val summary: DocumentSummary

    /**
     * A photo, verified to the end, in memory.
     *
     * @property bytes The verified file bytes. Wipe them as soon as the picture is decoded.
     */
    data class Image(override val summary: DocumentSummary, val bytes: VerifiedBytes) : OpenedDocument

    /**
     * A PDF, verified to the end, in view_tmp (rule 6). Delete it when the viewer closes.
     *
     * @property file The verified file.
     */
    data class Pdf(override val summary: DocumentSummary, val file: VerifiedFile) : OpenedDocument
}

/**
 * The only class screens use for documents (spec 5.7).
 *
 * Every database step goes through [VaultDatabaseHolder.withDatabase], so it never overlaps the
 * close-on-lock. Save and delete run as NonCancellable once they start: a cancelled screen must
 * never stop them between "file written" and "row written".
 *
 * @param holder Opens the database while unlocked.
 * @param lockState The app's lock, for [observeDocuments].
 * @param keyWrapper Creates and wraps document keys.
 * @param fileCrypto Encrypts files into the vault.
 * @param clock Current time in epoch milliseconds (tests pass a fixed clock).
 * @param newDocumentId Makes new document ids (tests can force a specific id).
 * @param ioDispatcher Where file and database work runs.
 */
class VaultRepository(
    private val holder: VaultDatabaseHolder,
    private val lockState: VaultLockState,
    private val keyWrapper: KeyWrapper,
    private val fileCrypto: FileCrypto,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newDocumentId: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Saves a new document: encrypts the file, then adds its row.
     *
     * Order: the wrapped key is made first (it needs the master key, so an expired fingerprint/PIN
     * window fails HERE, before anything is written; the caller asks again via MasterKeyAccess).
     * Then the .bin is written, then the row. If the row cannot be added, the .bin is deleted.
     * Why this order: a .bin without a row is harmless (unreadable, its key is stored nowhere),
     * while a row without a .bin would show the user a document that cannot be opened.
     *
     * @param input The document's bytes. Not closed here.
     * @param displayName The name the user typed.
     * @param type The kind of document.
     * @param mimeType The type detected from the file's bytes (Task 1.7).
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
        val cleanName = requireValidName(displayName)
        return withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database ->
                saveInsideDatabase(database, input, cleanName, type, mimeType)
            }
        }
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
     * Decrypts a document for viewing, fully verified (rule 6, Task 1.5).
     *
     * Needs the master key to unwrap the document's key, so after the fingerprint/PIN window it throws
     * UserNotAuthenticatedException; call it through MasterKeyAccess, which asks once and retries.
     * Only the row is read under the database lock; the (slower) decryption runs outside it.
     *
     * @param documentId The document's id.
     * @return The verified document. The caller must release it (wipe bytes / delete the file).
     * @throws DocumentNotFoundException If there is no such document.
     * @throws com.heybuddy.security.DocumentUnreadableException If the file fails verification.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun openDocument(documentId: String): OpenedDocument {
        return withContext(ioDispatcher) {
            val entity = holder.withDatabase { database -> database.documentDao().findById(documentId) }
                ?: throw DocumentNotFoundException()
            val documentKey = keyWrapper.unwrapDocumentKey(entity.wrappedKey, documentId)
            val summary = entity.toSummary()
            if (entity.mimeType == DetectedFileType.PDF.mimeType) {
                val destination = fileCrypto.newViewTempFile()
                val file = fileCrypto.decryptVerifiedToFile(documentId, documentKey, destination)
                OpenedDocument.Pdf(summary, file)
            } else {
                val bytes = fileCrypto.decryptVerified(documentId, documentKey)
                OpenedDocument.Image(summary, bytes)
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
     * Deletes a document for good (rule 13, crypto-shredding).
     *
     * Order: the row goes first, because it holds the wrapped key; from that moment the file can
     * never be decrypted again. Then the .bin is deleted. If the app dies in between, only an
     * unreadable file is left (Task 1.10 cleans those up). The other order could leave a row
     * pointing to a missing file.
     *
     * @param documentId The document's id.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun deleteDocument(documentId: String) {
        withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database ->
                database.documentDao().deleteById(documentId)
                fileCrypto.encryptedFileFor(documentId).delete()
            }
        }
        SafeLog.info(LogEvent.DOCUMENT_DELETED)
    }

    private suspend fun saveInsideDatabase(
        database: VaultDatabase,
        input: InputStream,
        cleanName: String,
        type: DocumentType,
        mimeType: String,
    ): DocumentSummary {
        val documentId = newDocumentId()
        val documentKey = keyWrapper.generateDocumentKey()
        val wrappedKey = keyWrapper.wrapDocumentKey(documentKey, documentId)
        val sizeBytes = fileCrypto.encryptToVault(input, documentKey, documentId)
        val now = clock()
        val entity = DocumentEntity(
            id = documentId,
            displayName = cleanName,
            type = type.name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            wrappedKey = wrappedKey,
            createdAt = now,
            updatedAt = now,
        )
        insertOrRemoveFile(database, entity)
        SafeLog.info(LogEvent.DOCUMENT_SAVED)
        return entity.toSummary()
    }

    private suspend fun insertOrRemoveFile(database: VaultDatabase, entity: DocumentEntity) {
        try {
            database.documentDao().insert(entity)
        } catch (insertFailed: Exception) {
            // No row means nobody can ever open this file; remove it so no orphan stays behind.
            fileCrypto.encryptedFileFor(entity.id).delete()
            SafeLog.error(LogEvent.DOCUMENT_SAVE_ROLLED_BACK, insertFailed)
            throw insertFailed
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

private fun DocumentEntity.toSummary(): DocumentSummary {
    return DocumentSummary(
        id = id,
        displayName = displayName,
        type = DocumentType.fromStoredValue(type),
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun DocumentListRow.toSummary(): DocumentSummary {
    return DocumentSummary(
        id = id,
        displayName = displayName,
        type = DocumentType.fromStoredValue(type),
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

package com.heybuddy.ocr

import com.heybuddy.data.VaultLockedException
import com.heybuddy.data.VaultRepository
import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reading results of the pages read in this unlocked session, in memory only (spec 7.3-2).
 * Cleared at once when the vault locks, and by [clear] when the reading screen is done. Never
 * saved, never logged.
 *
 * @param lockState The app's lock; every lock clears everything.
 */
class ReadingSession(private val lockState: VaultLockState) {

    private val results = mutableMapOf<String, RecognizedPage>()

    init {
        lockState.addOnLockListener { clear() }
    }

    /**
     * Keeps a page's result, unless the vault locked meanwhile (then it is dropped).
     *
     * @param pageId The page that was read.
     * @param page What was read.
     */
    @Synchronized
    fun keep(pageId: String, page: RecognizedPage) {
        if (lockState.status.value != LockStatus.UNLOCKED) return
        results[pageId] = page
    }

    /**
     * A page's result, if it was read in this session.
     *
     * @param pageId The page.
     * @return Its result, or null.
     */
    @Synchronized
    fun resultFor(pageId: String): RecognizedPage? = results[pageId]

    /** Drops every result (the text itself cannot be zeroed; dropping every reference is the best possible). */
    @Synchronized
    fun clear() {
        if (results.isEmpty()) return
        results.clear()
        SafeLog.info(LogEvent.READING_RESULTS_CLEARED)
    }
}

/**
 * Reads one vault page: decrypts it into memory, reads it, releases the decrypted copy, and keeps
 * the text in [session] (spec 7.2, 7.3-2).
 *
 * Needs the master key (to open the page), so after the fingerprint/PIN window it throws
 * UserNotAuthenticatedException; call it through MasterKeyAccess, like opening a page in the viewer.
 *
 * @param repository Opens pages.
 * @param reader Reads pictures.
 * @param session Keeps the results until they are no longer needed or the vault locks.
 */
class DocumentPageReader(
    private val repository: VaultRepository,
    private val reader: TextReader,
    private val session: ReadingSession,
) {

    /**
     * Reads one page. For a PDF page-file, its PDF pages are read in order and their lines joined.
     *
     * @param documentId The page's document.
     * @param pageId The page.
     * @return What was read.
     * @throws VaultLockedException If the vault is locked (before or during reading).
     */
    suspend fun readPage(documentId: String, pageId: String): RecognizedPage {
        val opened = repository.openPage(documentId, pageId)
        val pictures = withContext(Dispatchers.Default) { PagePictures.of(opened) }
        val lines = mutableListOf<RecognizedLine>()
        try {
            for (picture in pictures) {
                lines.addAll(reader.read(picture).lines)
            }
        } finally {
            for (picture in pictures) picture.recycle()
        }
        val page = RecognizedPage(lines)
        session.keep(pageId, page)
        if (session.resultFor(pageId) == null) throw VaultLockedException()
        SafeLog.info(LogEvent.PAGE_READ, count = page.lines.size)
        return page
    }
}

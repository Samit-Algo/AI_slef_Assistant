package com.heybuddy.ui.adddocument

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.heybuddy.data.PreparedPage
import com.heybuddy.data.VaultLockedException
import com.heybuddy.data.VaultRepository
import com.heybuddy.files.DetectedFileType
import com.heybuddy.files.FileTooLargeException
import com.heybuddy.files.FileTypeDetector
import com.heybuddy.files.PickedFileReader
import com.heybuddy.files.isStorageFull
import com.heybuddy.util.MAX_FILE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException

/** What preparing one picked file produced. */
sealed interface PrepareResult {

    /**
     * The file is checked and encrypted as a pending page.
     *
     * @property page The pending page (holds its key in memory until saved or discarded).
     * @property thumbnail A small picture of it, or null for a PDF that cannot be previewed.
     * @property isPdf True for a PDF.
     */
    class Prepared(val page: PreparedPage, val thumbnail: Bitmap?, val isPdf: Boolean) : PrepareResult

    /**
     * Nothing was kept.
     *
     * @property failure Why.
     */
    class Failed(val failure: AddDocumentFailure) : PrepareResult
}

/**
 * Turns one picked file into a pending page (Task 1.10p): read once, type-check, thumbnail,
 * encrypt into vault/<pageId>.bin.pending. The plaintext is wiped before this returns, so at most
 * ONE file's plaintext is ever in memory, however many pages are picked.
 *
 * Used by the Add screen and by the viewer's "Add page".
 *
 * @param contentResolver Reads the picked file.
 * @param repository Encrypts the page.
 * @param freeSpaceBytes How much storage space is free for the vault right now.
 */
class PagePreparer(
    private val contentResolver: ContentResolver,
    private val repository: VaultRepository,
    private val freeSpaceBytes: () -> Long,
) {

    /**
     * A fresh id for a new document's pages to be bound to.
     *
     * @return A new UUID.
     */
    fun newDocumentId(): String = repository.newDocumentId()

    /**
     * Deletes pending pages that will not be saved. Saved pages are never touched.
     *
     * @param pages The pages to throw away.
     */
    fun discard(pages: List<PreparedPage>) {
        repository.discardPreparedPages(pages)
    }

    /**
     * Prepares one picked file. Never throws: every failure comes back as [PrepareResult.Failed].
     *
     * @param documentId The document the page will belong to.
     * @param uri The picked file. Read once now; no long-term access is kept.
     * @return The pending page, or why it could not be made.
     */
    suspend fun prepare(documentId: String, uri: Uri): PrepareResult {
        val loaded = withContext(Dispatchers.IO) { readAndCheck(uri) }
        if (loaded is LoadResult.Failed) return PrepareResult.Failed(loaded.failure)
        val checked = loaded as LoadResult.Checked
        try {
            if (!hasRoomFor(checked.bytes.size.toLong())) {
                checked.thumbnail?.recycle()
                return PrepareResult.Failed(AddDocumentFailure.STORAGE_FULL)
            }
            val result = encryptAsPage(documentId, checked)
            if (result is PrepareResult.Failed) checked.thumbnail?.recycle()
            return result
        } finally {
            // The page is encrypted (or failed); its plaintext is not needed any more.
            checked.bytes.fill(0)
        }
    }

    private suspend fun encryptAsPage(documentId: String, checked: LoadResult.Checked): PrepareResult {
        val failure = try {
            val input = ByteArrayInputStream(checked.bytes)
            val page = repository.preparePage(documentId, input, checked.type.mimeType)
            return PrepareResult.Prepared(page, checked.thumbnail, isPdf = !checked.type.isImage)
        } catch (locked: VaultLockedException) {
            AddDocumentFailure.VAULT_LOCKED
        } catch (tooLarge: FileTooLargeException) {
            AddDocumentFailure.TOO_LARGE
        } catch (failed: Exception) {
            if (isStorageFull(failed)) AddDocumentFailure.STORAGE_FULL else AddDocumentFailure.OTHER
        }
        return PrepareResult.Failed(failure)
    }

    /** Reads, type-checks and thumbnails the file. Wipes the bytes itself on any failure. */
    private fun readAndCheck(uri: Uri): LoadResult {
        val bytes = try {
            readOnce(uri)
        } catch (tooLarge: FileTooLargeException) {
            return LoadResult.Failed(AddDocumentFailure.TOO_LARGE)
        } catch (unreadable: IOException) {
            return LoadResult.Failed(AddDocumentFailure.UNREADABLE_FILE)
        } catch (unreadable: SecurityException) {
            return LoadResult.Failed(AddDocumentFailure.UNREADABLE_FILE)
        }
        val type = FileTypeDetector.detect(bytes)
        if (type == null) {
            bytes.fill(0)
            return LoadResult.Failed(AddDocumentFailure.WRONG_TYPE)
        }
        if (!type.isImage) {
            val pdfThumbnail = FilePreview.renderFirstPdfPage(contentResolver, uri)
            return LoadResult.Checked(bytes, type, pdfThumbnail)
        }
        return try {
            // Decoding fully is also the check that the image is not damaged.
            LoadResult.Checked(bytes, type, FilePreview.decodeImage(bytes))
        } catch (damaged: DamagedImageException) {
            bytes.fill(0)
            LoadResult.Failed(AddDocumentFailure.DAMAGED_IMAGE)
        }
    }

    private fun readOnce(uri: Uri): ByteArray {
        val sizeHint = reportedSize(uri)
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Picker gave no data")
        input.use { stream ->
            return PickedFileReader.readAll(stream, MAX_FILE_BYTES, sizeHint)
        }
    }

    /** The size the picker reports. Only a hint for the buffer size; never trusted as a limit. */
    private fun reportedSize(uri: Uri): Long {
        val columns = arrayOf(OpenableColumns.SIZE)
        contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0L
    }

    private fun hasRoomFor(plaintextBytes: Long): Boolean {
        // The encrypted copy is a little bigger than the original; keep a safety margin on top.
        val needed = plaintextBytes + plaintextBytes / ENCRYPTION_OVERHEAD_DIVISOR + SAFETY_MARGIN_BYTES
        return freeSpaceBytes() >= needed
    }

    /** What reading a picked file produced. */
    private sealed interface LoadResult {

        /** Read and type-checked, not encrypted yet. */
        class Checked(val bytes: ByteArray, val type: DetectedFileType, val thumbnail: Bitmap?) : LoadResult

        /** Nothing was kept. */
        class Failed(val failure: AddDocumentFailure) : LoadResult
    }

    private companion object {
        /** Tink adds well under 1% to a file; 1/64 is a generous estimate. */
        const val ENCRYPTION_OVERHEAD_DIVISOR = 64

        /** Room for the database rows and other small writes during a save. */
        const val SAFETY_MARGIN_BYTES = 1024L * 1024L
    }
}

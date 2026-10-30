package com.heybuddy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.heybuddy.files.TempFileCleaner
import com.heybuddy.security.DatabaseKeyProvider
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.VaultLockState
import com.heybuddy.ui.VaultNavigator
import com.heybuddy.ui.adddocument.AddDocumentSaver
import com.heybuddy.ui.adddocument.PagePreparer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A complete, test-only vault: its own database, folders, passphrase file and keys WITHOUT the
 * fingerprint/PIN requirement (no timing). The real vault is never touched. Call [deleteAll] after
 * every test.
 *
 * @param context The app context.
 * @param name A short name that keeps this test's files apart from other tests'.
 * @param clock The clock used for createdAt (tests that check ordering pass their own).
 */
class TestVault(private val context: Context, name: String, clock: () -> Long = { System.currentTimeMillis() }) {

    /** The test database's file name. */
    val databaseName = "vault_${name}_test.db"

    private val passphraseFile = File(context.noBackupFilesDir, "db_passphrase_${name}_test.bin")

    /** The test master key (no fingerprint/PIN needed). */
    val masterKeyManager = MasterKeyManager(keyAlias = "hb_master_key_${name}_test", requireUserAuthentication = false)

    /** Encrypted files folder. */
    val vaultDir = File(context.filesDir, "vault_${name}_test")

    /** Decrypted-for-viewing folder. */
    val viewTempDir = File(context.noBackupFilesDir, "view_tmp_${name}_test")

    /** The app lock (starts locked; call [unlock]). */
    val lockState = VaultLockState()

    /** Which vault screen is showing. */
    val navigator = VaultNavigator(lockState)

    /** Wraps keys with the test master key. */
    val keyWrapper = KeyWrapper(masterKeyManager)

    /** File encryption into the test folders. */
    val fileCrypto = FileCrypto(vaultDir, viewTempDir)

    /** Empties the test view folder. */
    val tempFileCleaner = TempFileCleaner(vaultDir, viewTempDir)

    /** Gives the test database's passphrase. */
    val keyProvider = DatabaseKeyProvider(keyWrapper, passphraseFile)

    /** The test database. */
    val holder = VaultDatabaseHolder(context, lockState, keyProvider, databaseName)

    /** The test repository. */
    val repository = VaultRepository(holder, lockState, keyWrapper, fileCrypto, clock = clock)

    /** The test vault's confirmed profile details (Task 2.8). */
    val profileRepository = ProfileRepository(holder, clock = clock)

    /** Turns test files (content:// or file:// uris) into pending pages; storage is never "full". */
    val pagePreparer = PagePreparer(context.contentResolver, repository, freeSpaceBytes = { Long.MAX_VALUE })

    /** Saves pending pages. */
    val saver = AddDocumentSaver(repository)

    /**
     * The encrypted files of a document's pages, in page order. The vault must be unlocked.
     *
     * @param documentId The document's id.
     * @return vault/<pageId>.bin for each page.
     */
    fun pageFilesOf(documentId: String): List<File> {
        val details = runBlocking { repository.documentDetails(documentId) }
        return details.pages.map { page -> fileCrypto.encryptedFileFor(page.pageId) }
    }

    /** Unlocks the test vault and waits until its database is ready. */
    fun unlock() {
        lockState.markUnlocked()
        runBlocking { holder.awaitPendingWork() }
    }

    /** Locks the test vault and waits until its database has closed. */
    fun lock() {
        lockState.lock()
        runBlocking { holder.awaitPendingWork() }
    }

    /** Deletes everything this test vault created. */
    fun deleteAll() {
        lock()
        context.deleteDatabase(databaseName)
        vaultDir.deleteRecursively()
        viewTempDir.deleteRecursively()
        passphraseFile.delete()
        masterKeyManager.deleteMasterKey()
    }

    companion object {
        /** A real JPEG with fake content: a plain blue picture. */
        fun sampleJpegBytes(): ByteArray {
            val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(30, 90, 160))
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            bitmap.recycle()
            return output.toByteArray()
        }

        /** A real 2-page A4 PDF with fake content ("Sample page 1", "Sample page 2"). */
        fun sampleTwoPagePdfBytes(): ByteArray {
            val document = PdfDocument()
            val paint = Paint().apply { textSize = TEXT_SIZE }
            for (pageNumber in 1..2) {
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pageNumber).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawText("Sample page $pageNumber", TEXT_X, TEXT_Y, paint)
                document.finishPage(page)
            }
            val output = ByteArrayOutputStream()
            document.writeTo(output)
            document.close()
            return output.toByteArray()
        }

        private const val SAMPLE_WIDTH = 600
        private const val SAMPLE_HEIGHT = 400
        private const val JPEG_QUALITY = 90
        private const val A4_WIDTH = 595
        private const val A4_HEIGHT = 842
        private const val TEXT_SIZE = 24f
        private const val TEXT_X = 72f
        private const val TEXT_Y = 100f
    }
}

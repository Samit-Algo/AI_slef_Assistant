package com.heybuddy.ocr

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.DocumentType
import com.heybuddy.data.TestVault
import com.heybuddy.data.VaultLockedException
import com.heybuddy.ocr.fakedocs.FakeDocuments
import com.heybuddy.ocr.fakedocs.PhotoLook
import com.heybuddy.ocr.fakedocs.SampleStyle
import com.heybuddy.security.requirePhoneUnlocked
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spec 7.3-2 on a real phone: pages are read only after being decrypted in memory, and the read text
 * is never written to disk, never logged, and dropped on lock. Uses a test vault and fake samples.
 */
@RunWith(AndroidJUnit4::class)
class OcrPrivacyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val vault = TestVault(context, name = "ocr_privacy")
    private val reader = MlKitTextReader()
    private val session = ReadingSession(vault.lockState)
    private val pageReader = DocumentPageReader(vault.repository, reader, session)

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        vault.deleteAll()
        vault.unlock()
    }

    @After
    fun cleanUp() {
        reader.close()
        vault.deleteAll()
    }

    @Test
    fun readTextIsNeverLoggedNorWrittenAnywhereInTheApp() = runBlocking {
        val since = logTimestampNow()
        val (documentId, pageId) = saveFakeAadhaarStyleCard()

        val page = pageReader.readPage(documentId, pageId)

        assertTrue("The sample must actually have been read", page.text.contains(SAMPLE_NAME))
        val log = runShell("logcat -d -T '$since'")
        for (secret in SECRETS) {
            assertFalse("\"$secret\" appeared in the log", log.contains(secret))
        }
        for (file in allAppFiles()) {
            val bytes = file.readBytes()
            for (secret in SECRETS) {
                assertFalse("\"$secret\" found in ${file.name}", contains(bytes, secret.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    @Test
    fun lockingDropsTheReadingResults() = runBlocking {
        val (documentId, pageId) = saveFakeAadhaarStyleCard()
        pageReader.readPage(documentId, pageId)
        assertNotNull(session.resultFor(pageId))

        vault.lock()

        assertNull(session.resultFor(pageId))
    }

    @Test
    fun aResultThatArrivesAfterALockIsNotKept() {
        vault.lock()

        session.keep(PAGE_ID, RecognizedPage(listOf(RecognizedLine(SAMPLE_NAME, null))))

        assertNull(session.resultFor(PAGE_ID))
    }

    @Test
    fun readingWhileLockedIsRefused() = runBlocking {
        val (documentId, pageId) = saveFakeAadhaarStyleCard()
        vault.lock()

        val outcome = runCatching { pageReader.readPage(documentId, pageId) }

        assertTrue("Got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is VaultLockedException)
    }

    @Test
    fun pdfPagesAreReadAndTheirViewCopyIsDeleted() = runBlocking {
        val saved = vault.repository.saveDocument(ByteArrayInputStream(TestVault.sampleTwoPagePdfBytes()), "Sample pdf", DocumentType.CERTIFICATE, MIME_PDF)
        val pageId = vault.repository.documentDetails(saved.id).pages.single().pageId

        val page = pageReader.readPage(saved.id, pageId)

        assertTrue("Got: ${page.text}", page.text.contains("page 1") && page.text.contains("page 2"))
        assertTrue("view_tmp must be empty", vault.viewTempDir.listFiles().isNullOrEmpty())
    }

    /** Saves the clean fake Aadhaar-style card as a photo page; returns its document and page ids. */
    private suspend fun saveFakeAadhaarStyleCard(): Pair<String, String> {
        val sample = FakeDocuments.make(SampleStyle.AADHAAR_STYLE, PhotoLook.CLEAN)
        val jpeg = ByteArrayOutputStream().use { output ->
            sample.picture.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        }
        sample.picture.recycle()
        val saved = vault.repository.saveDocument(ByteArrayInputStream(jpeg), "Sample card", DocumentType.AADHAAR, MIME_JPEG)
        val pageId = vault.repository.documentDetails(saved.id).pages.single().pageId
        return Pair(saved.id, pageId)
    }

    private fun allAppFiles(): List<File> {
        return context.dataDir.walkTopDown().filter { file -> file.isFile && file.canRead() && file.length() <= MAX_SCANNED_BYTES }.toList()
    }

    private fun logTimestampNow(): String = SimpleDateFormat(LOG_TIME_FORMAT, Locale.ROOT).format(Date())

    private fun runShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private companion object {
        const val SAMPLE_NAME = "TESTA KUMARI"
        val SECRETS = listOf(SAMPLE_NAME, "9999 4567 1231", "999945671231", "14/08/1990", "SAMPLE STREET")
        const val PAGE_ID = "0a0b0c0d-1111-4222-8333-444455556666"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PDF = "application/pdf"
        const val JPEG_QUALITY = 92
        const val LOG_TIME_FORMAT = "MM-dd HH:mm:ss.SSS"

        /** The app's files are small; anything bigger is not ours to scan (and would be slow). */
        const val MAX_SCANNED_BYTES = 64L * 1024 * 1024
    }
}

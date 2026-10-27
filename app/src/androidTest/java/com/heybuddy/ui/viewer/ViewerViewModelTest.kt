package com.heybuddy.ui.viewer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DocumentType
import com.heybuddy.data.TestVault
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.ReAuthPrompt
import com.heybuddy.security.requirePhoneUnlocked
import com.heybuddy.ui.VaultScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * Checks the viewer's tidy-up (rule 6), Back handling, PDF pages and Rename, on a real phone.
 * Uses a test vault only; the real vault is never touched.
 */
@RunWith(AndroidJUnit4::class)
class ViewerViewModelTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val vault = TestVault(instrumentation.targetContext, name = "viewer")
    private lateinit var viewModel: ViewerViewModel

    /** Not used here: these documents open without "Confirm it's you" (test key without auth). */
    private val unusedPrompt = object : ReAuthPrompt {
        override fun ask(onConfirmed: () -> Unit, onDeclined: () -> Unit) = onDeclined()
    }

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        vault.deleteAll()
        vault.unlock()
        val unlocker = BiometricUnlocker(MasterKeyManager(keyAlias = "not_used_by_viewer_tests"), vault.lockState)
        viewModel = onMain {
            ViewerViewModel(vault.repository, unlocker, vault.lockState, vault.navigator, vault.tempFileCleaner)
        }
    }

    @After
    fun cleanUp() {
        vault.deleteAll()
    }

    @Test
    fun aPhotoOpensAndBackFreesItAndReturnsToTheList() {
        val documentId = save(TestVault.sampleJpegBytes(), "Sample photo", DocumentType.PHOTO, "image/jpeg")
        val picture = openAndWaitForPicture(documentId)

        onMain { viewModel.onBack() }

        assertEquals(VaultScreen.List, vault.navigator.screen.value)
        assertTrue(onMain { viewModel.isReleasedForTest() })
        waitUntil("picture freed") { picture.isRecycled }
    }

    @Test
    fun aPdfOpensOnPage1Of2AndNextShowsPage2() {
        val documentId = save(TestVault.sampleTwoPagePdfBytes(), "Sample PDF", DocumentType.CERTIFICATE, "application/pdf")
        val firstPage = openAndWaitForPicture(documentId)

        val state = viewModel.uiState.value
        assertTrue(state.isPdf)
        assertEquals(2, state.pageCount)
        assertEquals(0, state.pageIndex)
        assertEquals("One decrypted PDF while viewing", 1, vault.viewTempDir.listFiles()?.size)

        onMain { viewModel.nextPage() }
        waitUntil("page 2 shown") { viewModel.uiState.value.pageIndex == 1 && viewModel.uiState.value.picture != null }

        assertFalse(viewModel.uiState.value.hasNextPage)
        waitUntil("page 1 picture freed") { firstPage.isRecycled }
    }

    @Test
    fun closingAPdfDeletesItFromViewTemp() {
        val documentId = save(TestVault.sampleTwoPagePdfBytes(), "Sample PDF", DocumentType.CERTIFICATE, "application/pdf")
        openAndWaitForPicture(documentId)

        onMain { viewModel.onBack() }

        assertTrue("view_tmp must be empty", vault.viewTempDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun lockingWhileViewingReleasesEverythingAtOnce() {
        val documentId = save(TestVault.sampleTwoPagePdfBytes(), "Sample PDF", DocumentType.CERTIFICATE, "application/pdf")
        val picture = openAndWaitForPicture(documentId)

        onMain { vault.lockState.lock() }
        instrumentation.waitForIdleSync()

        assertEquals(VaultScreen.List, vault.navigator.screen.value)
        assertTrue("view_tmp must be empty", vault.viewTempDir.listFiles().isNullOrEmpty())
        assertTrue(onMain { viewModel.isReleasedForTest() })
        assertEquals(ViewerUiState(), viewModel.uiState.value)
        waitUntil("picture freed") { picture.isRecycled }
    }

    @Test
    fun backWithTheRenameDialogOpenClosesOnlyTheDialog() {
        val documentId = save(TestVault.sampleJpegBytes(), "Sample photo", DocumentType.PHOTO, "image/jpeg")
        openAndWaitForPicture(documentId)
        onMain { viewModel.openRename() }
        assertNotNull(viewModel.uiState.value.rename)

        onMain { viewModel.onBack() }

        assertNull(viewModel.uiState.value.rename)
        assertEquals(ViewerStage.READY, viewModel.uiState.value.stage)
        assertEquals(VaultScreen.Viewer(documentId), vault.navigator.screen.value)
        assertFalse(onMain { viewModel.isReleasedForTest() })
    }

    @Test
    fun renameUpdatesTheTitleAndTheList() {
        val documentId = save(TestVault.sampleJpegBytes(), "Sample photo", DocumentType.PHOTO, "image/jpeg")
        openAndWaitForPicture(documentId)

        onMain {
            viewModel.openRename()
            viewModel.onRenameTextChanged("  Renamed sample  ")
            viewModel.confirmRename()
        }
        waitUntil("dialog closed") { viewModel.uiState.value.rename == null }

        assertEquals("Renamed sample", viewModel.uiState.value.title)
        val listName = runBlocking { vault.repository.observeDocuments().first().single().displayName }
        assertEquals("Renamed sample", listName)
    }

    @Test
    fun renameShowsTheNameRulesAndCannotSaveABadName() {
        val documentId = save(TestVault.sampleJpegBytes(), "Sample photo", DocumentType.PHOTO, "image/jpeg")
        openAndWaitForPicture(documentId)

        onMain {
            viewModel.openRename()
            viewModel.onRenameTextChanged("   ")
        }

        val dialog = viewModel.uiState.value.rename!!
        assertEquals(DisplayNameProblem.EMPTY, dialog.problem)
        assertFalse(dialog.canSave)
    }

    @Test
    fun aTamperedFileShowsTheCannotOpenMessage() {
        val documentId = save(TestVault.sampleJpegBytes(), "Sample photo", DocumentType.PHOTO, "image/jpeg")
        val encryptedFile = vault.fileCrypto.encryptedFileFor(documentId)
        val bytes = encryptedFile.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        encryptedFile.writeBytes(bytes)

        onMain { viewModel.showWith(unusedPrompt, documentId, SCREEN_WIDTH, SCREEN_LONGEST) }
        waitUntil("failed") { viewModel.uiState.value.stage == ViewerStage.FAILED }

        assertEquals(com.heybuddy.R.string.viewer_error_unreadable, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.canRetry)
    }

    private fun save(bytes: ByteArray, name: String, type: DocumentType, mimeType: String): String {
        return runBlocking {
            vault.repository.saveDocument(ByteArrayInputStream(bytes), name, type, mimeType).id
        }
    }

    private fun openAndWaitForPicture(documentId: String): Bitmap {
        vault.navigator.open(VaultScreen.Viewer(documentId))
        onMain { viewModel.showWith(unusedPrompt, documentId, SCREEN_WIDTH, SCREEN_LONGEST) }
        waitUntil("document shown") { viewModel.uiState.value.picture != null }
        return viewModel.uiState.value.picture!!
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $what" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val SCREEN_WIDTH = 1080
        const val SCREEN_LONGEST = 2400
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}

package com.heybuddy.ui.adddocument

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.HeyBuddyApp
import com.heybuddy.data.DocumentType
import com.heybuddy.data.TestVault
import com.heybuddy.security.requirePhoneUnlocked
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Picked-but-not-saved pages must not outlive a lock (Task 1.10p): their pending files are
 * deleted and the Add screen's state is cleared. Uses a test vault, so the real app's vault is
 * never locked, unlocked or written to.
 */
@RunWith(AndroidJUnit4::class)
class AddDocumentLockTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val vault = TestVault(context, name = "add_lock")
    private val firstSample = File(context.cacheDir, "lock_test_sample_1.jpg")
    private val secondSample = File(context.cacheDir, "lock_test_sample_2.jpg")

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        vault.deleteAll()
        vault.unlock()
    }

    @After
    fun cleanUp() {
        firstSample.delete()
        secondSample.delete()
        vault.deleteAll()
    }

    @Test
    fun lockDeletesThePendingPagesAndClearsTheScreen() {
        val uris = writeSamples()
        val viewModel = createViewModelOnMainThread()

        runOnMain { viewModel.onFilesPicked(uris) }
        waitUntil { viewModel.uiState.value.stage == AddStage.READY }
        runOnMain { viewModel.onTypeChosen(DocumentType.AADHAAR) }
        val pendingIds = readOnMain { viewModel.pendingPageIdsForTest() }
        assertEquals("Both picked files should be pending pages", 2, pendingIds.size)
        assertTrue(pendingIds.all { pageId -> vault.fileCrypto.pendingFileFor(pageId).exists() })

        runOnMain { vault.lockState.lock() }
        instrumentation.waitForIdleSync()

        assertTrue("Pending files must be deleted on lock", pendingIds.none { pageId -> vault.fileCrypto.pendingFileFor(pageId).exists() })
        assertTrue(readOnMain { viewModel.pendingPageIdsForTest() }.isEmpty())
        assertEquals(AddDocumentUiState(), viewModel.uiState.value)
    }

    @Test
    fun removingAPageDeletesItsPendingFileAndMovingChangesTheOrder() {
        val uris = writeSamples()
        val viewModel = createViewModelOnMainThread()
        runOnMain { viewModel.onFilesPicked(uris) }
        waitUntil { viewModel.uiState.value.stage == AddStage.READY }
        val pickedOrder = viewModel.uiState.value.pages.map { page -> page.pageId }

        runOnMain { viewModel.onMovePageDown(0) }
        val movedOrder = viewModel.uiState.value.pages.map { page -> page.pageId }
        runOnMain { viewModel.onRemovePage(pickedOrder[0]) }

        assertEquals(pickedOrder.reversed(), movedOrder)
        assertEquals(listOf(pickedOrder[1]), viewModel.uiState.value.pages.map { page -> page.pageId })
        assertFalse(vault.fileCrypto.pendingFileFor(pickedOrder[0]).exists())
        assertTrue(vault.fileCrypto.pendingFileFor(pickedOrder[1]).exists())
    }

    private fun createViewModelOnMainThread(): AddDocumentViewModel {
        val container = (context.applicationContext as HeyBuddyApp).container
        return readOnMain {
            // Saving is never reached in these tests; the unlocker is only needed to build it.
            AddDocumentViewModel(context, vault.pagePreparer, vault.saver, container.biometricUnlocker, vault.lockState)
        }
    }

    /** Two real (fake-content) JPEGs, like the front and back of a card. */
    private fun writeSamples(): List<Uri> {
        firstSample.writeBytes(TestVault.sampleJpegBytes())
        secondSample.writeBytes(TestVault.sampleJpegBytes())
        return listOf(Uri.fromFile(firstSample), Uri.fromFile(secondSample))
    }

    private fun runOnMain(block: () -> Unit) {
        instrumentation.runOnMainSync { block() }
    }

    private fun <T> readOnMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the files to load" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}

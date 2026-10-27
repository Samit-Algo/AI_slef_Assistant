package com.heybuddy.ui.adddocument

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.HeyBuddyApp
import com.heybuddy.data.DocumentType
import com.heybuddy.files.PickedFile
import com.heybuddy.security.VaultLockState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A picked-but-not-saved file must not outlive a lock: its bytes are wiped and the Add screen's
 * state is cleared. Uses its own lock state, so the real app's vault is never locked or unlocked.
 */
@RunWith(AndroidJUnit4::class)
class AddDocumentLockTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sampleFile = File(context.cacheDir, "lock_test_sample.jpg")

    @After
    fun deleteSample() {
        sampleFile.delete()
    }

    @Test
    fun lockWipesThePickedFileAndClearsTheScreen() {
        writeSampleJpeg()
        val lockState = VaultLockState()
        lockState.markUnlocked()
        val viewModel = createViewModelOnMainThread(lockState)

        runOnMain {
            viewModel.onFilePicked(Uri.fromFile(sampleFile))
        }
        waitUntil { viewModel.uiState.value.stage == AddStage.READY }
        runOnMain {
            viewModel.onTypeChosen(DocumentType.PHOTO)
        }
        val picked = readOnMain { viewModel.pickedFileForTest() }
        assertNotNull("A picked file should be in memory before the lock", picked)
        assertTrue(picked!!.bytes.any { byte -> byte != ZERO })

        runOnMain { lockState.lock() }
        instrumentation.waitForIdleSync()

        assertTrue("Picked bytes must be zeros after lock", picked.bytes.all { byte -> byte == ZERO })
        assertNull(readOnMain<PickedFile?> { viewModel.pickedFileForTest() })
        assertEquals(AddDocumentUiState(), viewModel.uiState.value)
    }

    private fun createViewModelOnMainThread(lockState: VaultLockState): AddDocumentViewModel {
        val container = (context.applicationContext as HeyBuddyApp).container
        return readOnMain {
            // Saving is never reached in this test; the saver and unlocker are only needed to build it.
            AddDocumentViewModel(context, container.addDocumentSaver, container.biometricUnlocker, lockState)
        }
    }

    /** A real (fake-content) JPEG: a plain blue picture, nothing personal. */
    private fun writeSampleJpeg() {
        val bitmap = Bitmap.createBitmap(SAMPLE_SIDE, SAMPLE_SIDE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        sampleFile.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output) }
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
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the file to load" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val SAMPLE_SIDE = 200
        const val JPEG_QUALITY = 90
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
        const val ZERO: Byte = 0
    }
}

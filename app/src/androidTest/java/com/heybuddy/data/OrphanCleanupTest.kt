package com.heybuddy.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.security.requirePhoneUnlocked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Orphan file cleanup on a real phone (Task 1.10, owner rule B): only "<valid UUID>.bin" files
 * without a page row are removed; everything else stays (including pending pages); and it never
 * runs in the middle of a save's database step.
 */
@RunWith(AndroidJUnit4::class)
class OrphanCleanupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = TestVault(context, name = "orphan")

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        vault.deleteAll()
        vault.unlock()
    }

    @After
    fun cleanUp() {
        vault.deleteAll()
    }

    @Test
    fun removesOnlyOrphanVaultFilesAndKeepsEverythingElse() = runBlocking {
        val saved = vault.repository.saveDocument(ByteArrayInputStream(TestVault.sampleJpegBytes()), "Kept", DocumentType.PHOTO, MIME_JPEG)
        val orphan = File(vault.vaultDir, "$ORPHAN_ID.bin").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val unfinished = File(vault.vaultDir, "$ORPHAN_ID.bin.tmp").apply { writeBytes(byteArrayOf(1)) }
        val pending = File(vault.vaultDir, "$ORPHAN_ID.bin.pending").apply { writeBytes(byteArrayOf(3)) }
        val unknown = File(vault.vaultDir, "notes.txt").apply { writeBytes(byteArrayOf(2)) }

        val removed = vault.repository.removeOrphanFiles()

        assertEquals(1, removed)
        assertFalse("orphan .bin removed", orphan.exists())
        assertTrue("the real document's file stays", vault.pageFilesOf(saved.id).single().exists())
        assertTrue("a .tmp file is not ours to touch here", unfinished.exists())
        assertTrue("a page on the Add screen (.pending) is never touched", pending.exists())
        assertTrue("an unknown file is left alone", unknown.exists())
    }

    /**
     * A page still being encrypted is only a ".pending" file: cleanup may run meanwhile, but must
     * not touch it, and the page is saved normally afterwards (Task 1.10p).
     */
    @Test
    fun neverRemovesAPageThatIsBeingEncrypted() = runBlocking {
        val gatedInput = GatedInputStream(totalBytes = SAMPLE_BYTES)
        val save = async(Dispatchers.IO) {
            vault.repository.saveDocument(gatedInput, "Mid-save", DocumentType.OTHER, MIME_JPEG)
        }
        assertTrue("Save never started", gatedInput.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val removedMeanwhile = withTimeout(TIMEOUT_MILLIS) { vault.repository.removeOrphanFiles() }
        gatedInput.gate.countDown()
        val saved = withTimeout(TIMEOUT_MILLIS) { save.await() }

        assertEquals(0, removedMeanwhile)
        assertTrue(vault.pageFilesOf(saved.id).single().exists())
    }

    /**
     * During the database step of a save, the new .bin exists for a moment before its row.
     * Cleanup started meanwhile must wait for the save (same database lock), and must not remove it.
     */
    @Test
    fun neverRunsInTheMiddleOfTheDatabaseStep() = runBlocking {
        // The repository reads the clock only inside the database step, so a clock that waits for
        // a gate holds the save right there, while the database lock is held.
        val insideDatabaseStep = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val gatedVault = TestVault(context, name = "orphan_gated", clock = {
            insideDatabaseStep.countDown()
            gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            FIXED_TIME
        })
        try {
            gatedVault.deleteAll()
            gatedVault.unlock()
            val save = async(Dispatchers.IO) {
                gatedVault.repository.saveDocument(ByteArrayInputStream(ByteArray(SAMPLE_BYTES)), "Mid-save", DocumentType.OTHER, MIME_JPEG)
            }
            assertTrue("Save never reached the database", insideDatabaseStep.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val cleanup = async(Dispatchers.IO) { gatedVault.repository.removeOrphanFiles() }

            delay(WAIT_WHILE_SAVE_HELD_MILLIS)
            assertFalse("Cleanup must wait for the save", cleanup.isCompleted)

            gate.countDown()
            val saved = withTimeout(TIMEOUT_MILLIS) { save.await() }
            val removed = withTimeout(TIMEOUT_MILLIS) { cleanup.await() }

            assertEquals(0, removed)
            assertTrue(gatedVault.pageFilesOf(saved.id).single().exists())
        } finally {
            gate.countDown()
            gatedVault.deleteAll()
        }
    }

    /** Holds a save open until [gate] is released. */
    private class GatedInputStream(private val totalBytes: Int) : InputStream() {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        private var position = 0

        override fun read(): Int {
            started.countDown()
            gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (position >= totalBytes) return -1
            position++
            return FILLER_BYTE
        }
    }

    private companion object {
        const val ORPHAN_ID = "0a0b0c0d-1111-4222-8333-444455556666"
        const val MIME_JPEG = "image/jpeg"
        const val SAMPLE_BYTES = 20_000
        const val FILLER_BYTE = 42
        const val TIMEOUT_SECONDS = 10L
        const val TIMEOUT_MILLIS = 10_000L
        const val WAIT_WHILE_SAVE_HELD_MILLIS = 500L
        const val FIXED_TIME = 1_700_000_000_000L
    }
}

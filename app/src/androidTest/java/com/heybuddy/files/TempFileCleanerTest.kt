package com.heybuddy.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Checks the startup cleaner (rule 6). Uses its own test folders. */
@RunWith(AndroidJUnit4::class)
class TempFileCleanerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaultDir = File(context.filesDir, "vault_cleaner_test")
    private val viewTempDir = File(context.noBackupFilesDir, "view_tmp_cleaner_test")
    private val cleaner = TempFileCleaner(vaultDir, viewTempDir)

    @Before
    fun createFolders() {
        deleteTestFolders()
        vaultDir.mkdirs()
        viewTempDir.mkdirs()
    }

    @After
    fun cleanUp() {
        deleteTestFolders()
    }

    @Test
    fun deletesUnfinishedEncryptionsButKeepsFinishedDocuments() {
        val unfinished = createFile(vaultDir, "aaaa.bin.tmp")
        val finished = createFile(vaultDir, "bbbb.bin")

        cleaner.cleanOnStart()

        assertFalse(unfinished.exists())
        assertTrue(finished.exists())
    }

    /** Pages picked but never saved (Task 1.10p): their keys were only in memory, so they must go. */
    @Test
    fun deletesPendingPagesButKeepsSavedPages() {
        val pending = createFile(vaultDir, "cccc.bin.pending")
        val unfinishedPending = createFile(vaultDir, "dddd.bin.pending.tmp")
        val saved = createFile(vaultDir, "eeee.bin")

        cleaner.cleanOnStart()

        assertFalse(pending.exists())
        assertFalse(unfinishedPending.exists())
        assertTrue(saved.exists())
    }

    @Test
    fun deletesLeftoverPartFilesInViewTemp() {
        val leftoverPart = createFile(viewTempDir, "view.pdf.part")

        cleaner.cleanOnStart()

        assertFalse(leftoverPart.exists())
    }

    @Test
    fun emptiesViewTempCompletely() {
        val finishedView = createFile(viewTempDir, "view.pdf")

        cleaner.cleanOnStart()

        assertFalse(finishedView.exists())
    }

    @Test
    fun doesNothingWhenFoldersDoNotExist() {
        deleteTestFolders()

        cleaner.cleanOnStart()

        assertFalse(vaultDir.exists())
    }

    private fun createFile(folder: File, name: String): File {
        val file = File(folder, name)
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    private fun deleteTestFolders() {
        vaultDir.deleteRecursively()
        viewTempDir.deleteRecursively()
    }
}

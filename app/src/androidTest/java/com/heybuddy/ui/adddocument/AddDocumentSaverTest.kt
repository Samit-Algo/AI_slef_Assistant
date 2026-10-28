package com.heybuddy.ui.adddocument

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DocumentType
import com.heybuddy.data.VaultDatabaseHolder
import com.heybuddy.data.VaultRepository
import com.heybuddy.data.PreparedPage
import com.heybuddy.data.TestVault
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.DatabaseKeyProvider
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.TEST_AUTH_WINDOW_SECONDS
import com.heybuddy.security.KeyCheckResult
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.MasterKeyAccess
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.ReAuthPrompt
import com.heybuddy.security.VaultLockState
import com.heybuddy.security.requirePhoneUnlocked
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Checks the whole save flow behind the Add document screen (pages prepared, then saved), on a real phone.
 *
 * Everything is test-only (own database, folders, keys) and deleted after every test.
 * The database passphrase uses a key WITHOUT the fingerprint/PIN requirement. Document keys use:
 * - a no-auth key for most tests (no timing), and
 * - an auth-required key for the "Confirm it's you" tests (window must be closed; see there).
 */
@RunWith(AndroidJUnit4::class)
class AddDocumentSaverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaultDir = File(context.filesDir, "vault_saver_test")
    private val viewTempDir = File(context.noBackupFilesDir, "view_tmp_saver_test")
    private val passphraseFile = File(context.noBackupFilesDir, "db_passphrase_saver_test.bin")
    private val passphraseKey = MasterKeyManager(keyAlias = "hb_master_key_saver_db_test", requireUserAuthentication = false)
    private val documentKeyNoAuth = MasterKeyManager(keyAlias = "hb_master_key_saver_doc_test", requireUserAuthentication = false)
    // 30-second test window (not the app's 5 minutes), so "window closed" is reached quickly.
    private val documentKeyWithAuth = MasterKeyManager(
        keyAlias = "hb_master_key_saver_auth_test",
        authWindowSeconds = TEST_AUTH_WINDOW_SECONDS,
    )
    private val lockState = VaultLockState()
    private val keyProvider = DatabaseKeyProvider(KeyWrapper(passphraseKey), passphraseFile)
    private val holder = VaultDatabaseHolder(context, lockState, keyProvider, TEST_DATABASE_NAME)
    private val fileCrypto = FileCrypto(vaultDir, viewTempDir)
    private val sampleFile = File(context.cacheDir, "saver_test_sample.jpg")

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        deleteEverythingFromTests()
        lockState.markUnlocked()
        runBlocking { holder.awaitPendingWork() }
    }

    @After
    fun cleanUp() {
        lockState.lock()
        runBlocking { holder.awaitPendingWork() }
        deleteEverythingFromTests()
    }

    @Test
    fun savesThePagesInOrderWithTheDetectedType() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val documentId = repository.newDocumentId()
        val pages = listOf(prepare(repository, documentId), prepare(repository, documentId))

        val outcome = AddDocumentSaver(repository).save(documentId, pages, DocumentType.AADHAAR, "Aadhaar card", keyAccess(confirms = true))

        assertTrue("Unexpected outcome: $outcome", outcome is SaveOutcome.Saved)
        val saved = (outcome as SaveOutcome.Saved).document
        assertEquals(2, saved.pageCount)
        val details = repository.documentDetails(saved.id)
        assertEquals(pages.map { page -> page.pageId }, details.pages.map { page -> page.pageId })
        assertTrue(details.pages.all { page -> page.mimeType == "image/jpeg" })
        assertEquals(1, repository.observeDocuments().first().size)
    }

    @Test
    fun invalidNameIsRejectedAndThePagesStayPendingForAnotherTry() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val documentId = repository.newDocumentId()
        val page = prepare(repository, documentId)

        val outcome = AddDocumentSaver(repository).save(documentId, listOf(page), DocumentType.PHOTO, "Two\nlines", keyAccess(confirms = true))

        assertEquals(SaveOutcome.NameRejected(DisplayNameProblem.CONTROL_CHARACTER), outcome)
        assertTrue(repository.observeDocuments().first().isEmpty())
        assertTrue(fileCrypto.pendingFileFor(page.pageId).exists())
        assertFalse(fileCrypto.encryptedFileFor(page.pageId).exists())
    }

    @Test
    fun notEnoughFreeSpaceStopsBeforeWritingAnything() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val preparer = PagePreparer(context.contentResolver, repository) { NO_SPACE }

        val result = preparer.prepare(repository.newDocumentId(), sampleJpegUri())

        assertEquals(AddDocumentFailure.STORAGE_FULL, (result as PrepareResult.Failed).failure)
        assertTrue(vaultDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun lockedVaultGivesTheLockedMessage() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val documentId = repository.newDocumentId()
        val page = prepare(repository, documentId)
        lockState.lock()
        holder.awaitPendingWork()

        val preparer = PagePreparer(context.contentResolver, repository) { PLENTY_OF_SPACE }
        val prepareWhileLocked = preparer.prepare(documentId, sampleJpegUri())
        val saveWhileLocked = AddDocumentSaver(repository).save(documentId, listOf(page), DocumentType.PHOTO, "Sample photo", keyAccess(confirms = true))

        assertEquals(AddDocumentFailure.VAULT_LOCKED, (prepareWhileLocked as PrepareResult.Failed).failure)
        assertEquals(SaveOutcome.Failed(AddDocumentFailure.VAULT_LOCKED), saveWhileLocked)
    }

    @Test
    fun elevenPagesGiveTheTooManyPagesMessage() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val documentId = repository.newDocumentId()
        val pages = List(MAX_PAGES_PER_DOCUMENT + 1) {
            repository.preparePage(documentId, ByteArrayInputStream(ByteArray(TINY_BYTES)), "image/jpeg")
        }

        val outcome = AddDocumentSaver(repository).save(documentId, pages, DocumentType.OTHER, "Too many", keyAccess(confirms = true))

        assertEquals(SaveOutcome.Failed(AddDocumentFailure.TOO_MANY_PAGES), outcome)
        assertTrue(repository.observeDocuments().first().isEmpty())
        assertTrue(pages.none { page -> fileCrypto.encryptedFileFor(page.pageId).exists() })
    }

    @Test
    fun addPagesPutsThemAtTheEnd() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val saver = AddDocumentSaver(repository)
        val documentId = repository.newDocumentId()
        val firstPage = prepare(repository, documentId)
        saver.save(documentId, listOf(firstPage), DocumentType.PASSPORT, "Passport", keyAccess(confirms = true))
        val newPages = listOf(prepare(repository, documentId), prepare(repository, documentId))

        val failure = saver.addPages(documentId, newPages, keyAccess(confirms = true))

        assertNull(failure)
        val pageIds = repository.documentDetails(documentId).pages.map { page -> page.pageId }
        assertEquals(listOf(firstPage.pageId) + newPages.map { page -> page.pageId }, pageIds)
    }

    /**
     * With an auth-required key and no recent fingerprint/PIN, the save needs "Confirm it's you".
     * The user cancels, so the calm message comes back and nothing is saved; the pages stay
     * pending, so tapping Save again works once the user confirms.
     * Skipped (not failed) if the phone was unlocked within the key window, because then the key works.
     */
    @Test
    fun cancelledConfirmationGivesTheCalmMessageAndSavesNothing() = runBlocking {
        requireAuthWindowClosed()
        val repository = repositoryWith(documentKeyWithAuth)
        val documentId = repository.newDocumentId()
        val page = prepare(repository, documentId)

        val outcome = AddDocumentSaver(repository).save(documentId, listOf(page), DocumentType.PAN, "PAN card", keyAccess(confirms = false))

        assertEquals(SaveOutcome.Failed(AddDocumentFailure.CONFIRMATION_NEEDED), outcome)
        assertTrue(repository.observeDocuments().first().isEmpty())
        assertFalse(fileCrypto.encryptedFileFor(page.pageId).exists())
        assertTrue(fileCrypto.pendingFileFor(page.pageId).exists())
    }

    /**
     * The fake prompt says "confirmed" but no real fingerprint/PIN happened, so the one retry is
     * refused too. It must stop there (no loop), with the calm message and nothing saved.
     */
    @Test
    fun confirmedButStillRefusedStopsAfterOneRetry() = runBlocking {
        requireAuthWindowClosed()
        val repository = repositoryWith(documentKeyWithAuth)
        val documentId = repository.newDocumentId()
        val page = prepare(repository, documentId)
        val prompt = CountingPrompt(confirms = true)

        val outcome = AddDocumentSaver(repository).save(documentId, listOf(page), DocumentType.PAN, "PAN card", MasterKeyAccess(prompt))

        assertEquals(SaveOutcome.Failed(AddDocumentFailure.CONFIRMATION_NEEDED), outcome)
        assertEquals(1, prompt.timesShown)
        assertFalse(fileCrypto.encryptedFileFor(page.pageId).exists())
    }

    private suspend fun prepare(repository: VaultRepository, documentId: String): PreparedPage {
        val preparer = PagePreparer(context.contentResolver, repository) { PLENTY_OF_SPACE }
        val result = preparer.prepare(documentId, sampleJpegUri())
        assertTrue("Could not prepare: $result", result is PrepareResult.Prepared)
        return (result as PrepareResult.Prepared).page
    }

    /** A real JPEG (fake content) in the test's own cache folder, opened like a picked file. */
    private fun sampleJpegUri(): Uri {
        sampleFile.writeBytes(TestVault.sampleJpegBytes())
        return Uri.fromFile(sampleFile)
    }

    private fun repositoryWith(documentKeyManager: MasterKeyManager): VaultRepository {
        return VaultRepository(holder, lockState, KeyWrapper(documentKeyManager), fileCrypto)
    }

    private fun keyAccess(confirms: Boolean): MasterKeyAccess = MasterKeyAccess(CountingPrompt(confirms))

    private fun requireAuthWindowClosed() {
        val unlocker = BiometricUnlocker(documentKeyWithAuth, VaultLockState())
        val windowClosed = unlocker.checkMasterKeyUsable() == KeyCheckResult.NOT_AUTHENTICATED
        assumeTrue("Phone was unlocked within the key window; run again later", windowClosed)
    }

    private fun deleteEverythingFromTests() {
        context.deleteDatabase(TEST_DATABASE_NAME)
        vaultDir.deleteRecursively()
        viewTempDir.deleteRecursively()
        passphraseFile.delete()
        passphraseKey.deleteMasterKey()
        documentKeyNoAuth.deleteMasterKey()
        documentKeyWithAuth.deleteMasterKey()
        sampleFile.delete()
    }

    /** Answers at once and counts how often it was shown. */
    private class CountingPrompt(private val confirms: Boolean) : ReAuthPrompt {
        var timesShown = 0
            private set

        override fun ask(onConfirmed: () -> Unit, onDeclined: () -> Unit) {
            timesShown++
            if (confirms) onConfirmed() else onDeclined()
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "vault_saver_test.db"
        const val TINY_BYTES = 16
        const val PLENTY_OF_SPACE = Long.MAX_VALUE
        const val NO_SPACE = 0L
    }
}

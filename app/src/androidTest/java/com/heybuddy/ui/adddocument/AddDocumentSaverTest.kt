package com.heybuddy.ui.adddocument

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DocumentType
import com.heybuddy.data.VaultDatabaseHolder
import com.heybuddy.data.VaultRepository
import com.heybuddy.files.DetectedFileType
import com.heybuddy.files.PickedFile
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Checks the whole save flow behind the Add document screen, on a real phone.
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
    private val pickedFile = PickedFile(ByteArray(SAMPLE_BYTES) { 42 }, DetectedFileType.JPEG)

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
    fun savesTheFileWithTheDetectedType() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val saver = AddDocumentSaver(repository) { PLENTY_OF_SPACE }

        val outcome = saver.save(pickedFile, DocumentType.PHOTO, "Sample photo", keyAccess(confirms = true))

        assertTrue("Unexpected outcome: $outcome", outcome is SaveOutcome.Saved)
        val saved = (outcome as SaveOutcome.Saved).document
        assertEquals("image/jpeg", saved.mimeType)
        assertEquals(SAMPLE_BYTES.toLong(), saved.sizeBytes)
        assertEquals(1, repository.observeDocuments().first().size)
    }

    @Test
    fun invalidNameIsRejectedAndNothingIsSaved() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val saver = AddDocumentSaver(repository) { PLENTY_OF_SPACE }

        val outcome = saver.save(pickedFile, DocumentType.PHOTO, "Two\nlines", keyAccess(confirms = true))

        assertEquals(SaveOutcome.NameRejected(DisplayNameProblem.CONTROL_CHARACTER), outcome)
        assertTrue(repository.observeDocuments().first().isEmpty())
    }

    @Test
    fun notEnoughFreeSpaceStopsBeforeWritingAnything() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val saver = AddDocumentSaver(repository) { NO_SPACE }

        val outcome = saver.save(pickedFile, DocumentType.PHOTO, "Sample photo", keyAccess(confirms = true))

        assertEquals(SaveOutcome.Failed(AddDocumentFailure.STORAGE_FULL), outcome)
        assertTrue(vaultDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun lockedVaultGivesTheLockedMessage() = runBlocking {
        val repository = repositoryWith(documentKeyNoAuth)
        val saver = AddDocumentSaver(repository) { PLENTY_OF_SPACE }
        lockState.lock()
        holder.awaitPendingWork()

        val outcome = saver.save(pickedFile, DocumentType.PHOTO, "Sample photo", keyAccess(confirms = true))

        assertEquals(SaveOutcome.Failed(AddDocumentFailure.VAULT_LOCKED), outcome)
    }

    /**
     * With an auth-required key and no recent fingerprint/PIN, the save needs "Confirm it's you".
     * The user cancels, so the calm message comes back and nothing is saved.
     * Skipped (not failed) if the phone was unlocked within the key window, because then the key works.
     */
    @Test
    fun cancelledConfirmationGivesTheCalmMessageAndSavesNothing() = runBlocking {
        requireAuthWindowClosed()
        val repository = repositoryWith(documentKeyWithAuth)
        val saver = AddDocumentSaver(repository) { PLENTY_OF_SPACE }

        val outcome = saver.save(pickedFile, DocumentType.PAN, "PAN card", keyAccess(confirms = false))

        assertEquals(SaveOutcome.Failed(AddDocumentFailure.CONFIRMATION_NEEDED), outcome)
        assertTrue(repository.observeDocuments().first().isEmpty())
        assertTrue(vaultDir.listFiles().isNullOrEmpty())
    }

    /**
     * The fake prompt says "confirmed" but no real fingerprint/PIN happened, so the one retry is
     * refused too. It must stop there (no loop), with the calm message and nothing saved.
     */
    @Test
    fun confirmedButStillRefusedStopsAfterOneRetry() = runBlocking {
        requireAuthWindowClosed()
        val repository = repositoryWith(documentKeyWithAuth)
        val saver = AddDocumentSaver(repository) { PLENTY_OF_SPACE }
        val prompt = CountingPrompt(confirms = true)

        val outcome = saver.save(pickedFile, DocumentType.PAN, "PAN card", MasterKeyAccess(prompt))

        assertEquals(SaveOutcome.Failed(AddDocumentFailure.CONFIRMATION_NEEDED), outcome)
        assertEquals(1, prompt.timesShown)
        assertTrue(vaultDir.listFiles().isNullOrEmpty())
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
        const val SAMPLE_BYTES = 20_000
        const val PLENTY_OF_SPACE = Long.MAX_VALUE
        const val NO_SPACE = 0L
    }
}

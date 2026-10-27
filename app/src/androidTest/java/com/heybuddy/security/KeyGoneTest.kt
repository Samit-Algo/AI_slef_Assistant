package com.heybuddy.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.TestVault
import com.heybuddy.data.VaultEraser
import com.heybuddy.data.VaultFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.InvalidKeyException

/**
 * Rule 14 on a real phone, with a test vault only (the real vault is never touched):
 * recovery ONLY on certain proof, never a new key over an existing vault, and a full erase.
 */
@RunWith(AndroidJUnit4::class)
class KeyGoneTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = TestVault(context, name = "keygone")
    private val files = VaultFiles(
        databaseFile = context.getDatabasePath("vault_keygone_test.db"),
        passphraseFile = java.io.File(context.noBackupFilesDir, "db_passphrase_keygone_test.bin"),
        vaultDir = vault.vaultDir,
        viewTempDir = vault.viewTempDir,
    )

    // The same test key, but guarded the way the app guards the real one.
    private val guardedKey = MasterKeyManager(
        keyAlias = "hb_master_key_keygone_test",
        requireUserAuthentication = false,
        mayCreateKey = { !files.vaultDataExists() },
    )

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        vault.deleteAll()
    }

    @After
    fun cleanUp() {
        vault.deleteAll()
    }

    @Test
    fun androidsInvalidatedKeyErrorIsProof() {
        assertEquals(UnlockFailure.KEY_GONE, failureForKeyError(KeyPermanentlyInvalidatedException()))
        assertTrue(isProofKeyIsGone(IllegalStateException("wrapped", KeyPermanentlyInvalidatedException())))
    }

    @Test
    fun notAuthenticatedOrOtherKeyErrorsAreNotProof() {
        assertEquals(UnlockFailure.OTHER, failureForKeyError(UserNotAuthenticatedException()))
        assertEquals(UnlockFailure.OTHER, failureForKeyError(InvalidKeyException("Keystore operation failed")))
    }

    @Test
    fun anInvalidatedKeyStaysGoneEvenThoughItsNameStillExists() {
        val health = VaultHealthMonitor(keyExists = { true }, vaultDataExists = { true })

        health.reportKeyError(KeyPermanentlyInvalidatedException())
        health.refresh()

        assertEquals(VaultCondition.KEY_GONE, health.condition.value)
    }

    @Test
    fun aNewKeyIsNeverCreatedOverAnExistingVault() {
        createVaultWithOneDocument()
        guardedKey.deleteMasterKey()

        val outcome = runCatching { guardedKey.getOrCreateMasterKey() }

        assertTrue("Got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is MasterKeyGoneException)
        assertFalse("No new key may appear", guardedKey.hasMasterKey())
    }

    @Test
    fun aMissingKeyWithVaultDataShowsRecoveryAndTheEraserGivesAFreshStart() = runBlocking {
        createVaultWithOneDocument()
        val health = VaultHealthMonitor(keyExists = guardedKey::hasMasterKey, vaultDataExists = files::vaultDataExists)
        assertEquals(VaultCondition.OK, health.condition.value)

        // What Android does when the screen lock is removed: the key disappears.
        guardedKey.deleteMasterKey()
        health.refresh()
        assertEquals(VaultCondition.KEY_GONE, health.condition.value)

        VaultEraser(context, vault.lockState, vault.holder, guardedKey, health, files).eraseEverything()

        assertEquals(VaultCondition.NO_VAULT_YET, health.condition.value)
        assertFalse(files.databaseFile.exists())
        assertFalse(files.passphraseFile.exists())
        assertTrue(files.vaultDir.listFiles().isNullOrEmpty())
        assertFalse(guardedKey.hasMasterKey())
        // After a fresh start a new key may be created again.
        guardedKey.getOrCreateMasterKey()
        assertTrue(guardedKey.hasMasterKey())
    }

    private fun createVaultWithOneDocument() {
        vault.unlock()
        runBlocking {
            vault.repository.saveDocument(
                ByteArrayInputStream(TestVault.sampleJpegBytes()),
                "Sample photo",
                com.heybuddy.data.DocumentType.PHOTO,
                "image/jpeg",
            )
        }
        assertTrue(files.vaultDataExists())
    }
}

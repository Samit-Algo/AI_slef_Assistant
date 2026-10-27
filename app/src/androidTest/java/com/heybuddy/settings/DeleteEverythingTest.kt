package com.heybuddy.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.DocumentType
import com.heybuddy.data.TestVault
import com.heybuddy.data.VaultEraser
import com.heybuddy.data.VaultFiles
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.ReAuthPrompt
import com.heybuddy.security.VaultCondition
import com.heybuddy.security.VaultHealthMonitor
import com.heybuddy.security.requirePhoneUnlocked
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Settings → "Delete everything" on a real phone (spec "Done when" for Task 1.10): after it there
 * are no files, no database, no Keystore key, and the app is back at a first start. A cancelled
 * "Confirm it's you" deletes nothing. Uses a test vault only; the real vault is never touched.
 */
@RunWith(AndroidJUnit4::class)
class DeleteEverythingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = TestVault(context, name = "deleteall")
    private val files = VaultFiles(
        databaseFile = context.getDatabasePath("vault_deleteall_test.db"),
        passphraseFile = File(context.noBackupFilesDir, "db_passphrase_deleteall_test.bin"),
        vaultDir = vault.vaultDir,
        viewTempDir = vault.viewTempDir,
    )

    // The same key the test vault uses (same alias), so deleting it here deletes the vault's key.
    private val key = MasterKeyManager(keyAlias = "hb_master_key_deleteall_test", requireUserAuthentication = false)
    private val health = VaultHealthMonitor(keyExists = key::hasMasterKey, vaultDataExists = files::vaultDataExists)
    private val settings = AppSettings(MapStore())
    private val eraser = VaultEraser(context, vault.lockState, vault.holder, key, health, files, onErased = settings::resetAll)
    private val flow = DeleteEverythingFlow(erase = eraser::eraseEverything)

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
    fun confirmedDeletesEverythingAndGoesBackToAFirstStart() = runBlocking {
        createVaultWithOneDocument()
        settings.setTheme(ThemeChoice.DARK)
        settings.markOnboardingDone()

        val result = flow.run(FakePrompt(confirms = true))

        assertEquals(DeleteEverythingResult.ERASED, result)
        assertFalse("database", files.databaseFile.exists())
        assertFalse("passphrase file", files.passphraseFile.exists())
        assertTrue("encrypted files", files.vaultDir.listFiles().isNullOrEmpty())
        assertTrue("view_tmp", files.viewTempDir.listFiles().isNullOrEmpty())
        assertFalse("Keystore key", key.hasMasterKey())
        assertEquals(VaultCondition.NO_VAULT_YET, health.condition.value)
        assertFalse("onboarding shows again", settings.onboardingDone.value)
        assertEquals(ThemeChoice.FOLLOW_PHONE, settings.theme.value)
        assertFalse(eraser.erasing.value)
    }

    @Test
    fun cancelledConfirmItsYouDeletesNothing() = runBlocking {
        createVaultWithOneDocument()
        settings.markOnboardingDone()

        val result = flow.run(FakePrompt(confirms = false))

        assertEquals(DeleteEverythingResult.NOT_CONFIRMED, result)
        assertTrue(files.databaseFile.exists())
        assertTrue(key.hasMasterKey())
        assertEquals(1, vault.repository.observeDocuments().first().size)
        assertTrue(settings.onboardingDone.value)
    }

    private fun createVaultWithOneDocument() {
        vault.unlock()
        runBlocking {
            vault.repository.saveDocument(ByteArrayInputStream(TestVault.sampleJpegBytes()), "Sample photo", DocumentType.PHOTO, "image/jpeg")
        }
        // A leftover decrypted view file, to prove view_tmp is emptied too.
        files.viewTempDir.mkdirs()
        File(files.viewTempDir, "leftover.pdf").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(files.vaultDataExists())
    }

    /** Answers at once, like a user who confirms or cancels fingerprint/PIN. */
    private class FakePrompt(private val confirms: Boolean) : ReAuthPrompt {
        override fun ask(onConfirmed: () -> Unit, onDeclined: () -> Unit) {
            if (confirms) onConfirmed() else onDeclined()
        }
    }

    private class MapStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String): String? = values[key] as? String

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun clear() {
            values.clear()
        }
    }
}

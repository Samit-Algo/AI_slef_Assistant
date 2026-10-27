package com.heybuddy

import android.content.Context
import com.heybuddy.files.TempFileCleaner
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.DatabaseKeyProvider
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.DB_PASSPHRASE_FILE_NAME
import com.heybuddy.util.VAULT_FOLDER_NAME
import com.heybuddy.util.VIEW_TEMP_FOLDER_NAME
import java.io.File

/**
 * Creates and holds the app's shared objects (manual dependency injection, spec section 2).
 *
 * Later tasks add file crypto and the vault repository here, so every screen gets the
 * same instances.
 *
 * @param appContext The application context, safe to keep for the whole app's life.
 */
class AppContainer(private val appContext: Context) {

    /** The one manager for the vault's Keystore master key. */
    val masterKeyManager: MasterKeyManager = MasterKeyManager()

    /** The app's own lock. Starts locked every time the process starts. */
    val vaultLockState: VaultLockState = VaultLockState()

    /** Shows the fingerprint/PIN prompt and opens [vaultLockState] after a proven unlock. */
    val biometricUnlocker: BiometricUnlocker = BiometricUnlocker(masterKeyManager, vaultLockState)

    /** Wraps and unwraps document keys and the database passphrase with the master key. */
    val keyWrapper: KeyWrapper = KeyWrapper(masterKeyManager)

    /** The SQLCipher passphrase; its in-memory copy is wiped every time the vault locks. */
    val databaseKeyProvider: DatabaseKeyProvider = createDatabaseKeyProvider()

    // filesDir: app-private storage only, never external storage (spec 5.4).
    private val vaultDir: File = File(appContext.filesDir, VAULT_FOLDER_NAME)

    // noBackupFilesDir: decrypted files for viewing must never reach a backup (rules 6 and 7).
    private val viewTempDir: File = File(appContext.noBackupFilesDir, VIEW_TEMP_FOLDER_NAME)

    /** Encrypts documents into the vault and decrypts them only after full verification. */
    val fileCrypto: FileCrypto = FileCrypto(vaultDir, viewTempDir)

    /** Deletes half-finished encryptions and leftover decrypted files. */
    val tempFileCleaner: TempFileCleaner = TempFileCleaner(vaultDir, viewTempDir)

    private fun createDatabaseKeyProvider(): DatabaseKeyProvider {
        // noBackupFilesDir: app-private and never included in backups (rule 7).
        val passphraseFile = File(appContext.noBackupFilesDir, DB_PASSPHRASE_FILE_NAME)
        val provider = DatabaseKeyProvider(keyWrapper, passphraseFile)
        vaultLockState.addOnLockListener { provider.clear() }
        return provider
    }
}

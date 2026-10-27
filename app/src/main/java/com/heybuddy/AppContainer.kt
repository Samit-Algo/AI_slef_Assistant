package com.heybuddy

import android.content.Context
import com.heybuddy.data.VaultDatabaseHolder
import com.heybuddy.data.VaultRepository
import com.heybuddy.files.TempFileCleaner
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.DatabaseKeyProvider
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.DB_PASSPHRASE_FILE_NAME
import com.heybuddy.util.VAULT_DATABASE_NAME
import com.heybuddy.util.VAULT_FOLDER_NAME
import com.heybuddy.util.VIEW_TEMP_FOLDER_NAME
import java.io.File

/**
 * Creates and holds the app's shared objects (manual dependency injection, spec section 2).
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

    // noBackupFilesDir: app-private and never included in backups (rule 7).
    private val passphraseFile: File = File(appContext.noBackupFilesDir, DB_PASSPHRASE_FILE_NAME)

    /** The SQLCipher passphrase. [databaseHolder] wipes its in-memory copy on every lock. */
    val databaseKeyProvider: DatabaseKeyProvider = DatabaseKeyProvider(keyWrapper, passphraseFile)

    // filesDir: app-private storage only, never external storage (spec 5.4).
    private val vaultDir: File = File(appContext.filesDir, VAULT_FOLDER_NAME)

    // noBackupFilesDir: decrypted files for viewing must never reach a backup (rules 6 and 7).
    private val viewTempDir: File = File(appContext.noBackupFilesDir, VIEW_TEMP_FOLDER_NAME)

    /** Encrypts documents into the vault and decrypts them only after full verification. */
    val fileCrypto: FileCrypto = FileCrypto(vaultDir, viewTempDir)

    /** Deletes half-finished encryptions and leftover decrypted files. */
    val tempFileCleaner: TempFileCleaner = TempFileCleaner(vaultDir, viewTempDir)

    /**
     * Opens the SQLCipher database while unlocked. On lock it closes the database, then wipes
     * the passphrase (it registers itself with [vaultLockState]).
     */
    val databaseHolder: VaultDatabaseHolder =
        VaultDatabaseHolder(appContext, vaultLockState, databaseKeyProvider, VAULT_DATABASE_NAME)

    /** The only class screens use for documents. */
    val vaultRepository: VaultRepository =
        VaultRepository(databaseHolder, vaultLockState, keyWrapper, fileCrypto)
}

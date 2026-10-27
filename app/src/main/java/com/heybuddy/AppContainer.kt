package com.heybuddy

import android.content.Context
import android.os.storage.StorageManager
import com.heybuddy.data.VaultDatabaseHolder
import com.heybuddy.data.VaultEraser
import com.heybuddy.data.VaultFiles
import com.heybuddy.data.VaultRepository
import com.heybuddy.files.TempFileCleaner
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.DatabaseKeyProvider
import com.heybuddy.security.FileCrypto
import com.heybuddy.security.KeyWrapper
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.VaultHealthMonitor
import com.heybuddy.security.VaultLockState
import com.heybuddy.ui.AutoLockController
import com.heybuddy.ui.VaultNavigator
import com.heybuddy.ui.adddocument.AddDocumentSaver
import com.heybuddy.util.DB_PASSPHRASE_FILE_NAME
import com.heybuddy.util.VAULT_DATABASE_NAME
import com.heybuddy.util.VAULT_FOLDER_NAME
import com.heybuddy.util.VIEW_TEMP_FOLDER_NAME
import java.io.File
import java.io.IOException

/**
 * Creates and holds the app's shared objects (manual dependency injection, spec section 2).
 *
 * @param appContext The application context, safe to keep for the whole app's life.
 */
class AppContainer(private val appContext: Context) {

    /**
     * Where the vault's data lives: app-private only (spec 5.4). The passphrase and view_tmp are in
     * noBackupFilesDir, so they can never reach a backup (rules 6 and 7).
     */
    val vaultFiles: VaultFiles = VaultFiles(
        databaseFile = appContext.getDatabasePath(VAULT_DATABASE_NAME),
        passphraseFile = File(appContext.noBackupFilesDir, DB_PASSPHRASE_FILE_NAME),
        vaultDir = File(appContext.filesDir, VAULT_FOLDER_NAME),
        viewTempDir = File(appContext.noBackupFilesDir, VIEW_TEMP_FOLDER_NAME),
    )

    /**
     * The Keystore master key. A NEW key may only be created while no vault exists: if the key is
     * missing while vault data exists, Android removed it (rule 14), and a new key would hide that.
     */
    val masterKeyManager: MasterKeyManager = MasterKeyManager(mayCreateKey = { !vaultFiles.vaultDataExists() })

    /** Whether the vault can still be opened; decides "key gone" only on certain proof (rule 14). */
    val vaultHealthMonitor: VaultHealthMonitor = VaultHealthMonitor(
        keyExists = masterKeyManager::hasMasterKey,
        vaultDataExists = vaultFiles::vaultDataExists,
    )

    /** The app's own lock. Starts locked every time the process starts. */
    val vaultLockState: VaultLockState = VaultLockState()

    /** Locks automatically: on leaving the app, screen off, idle, and when a picker pass runs out (rule 9). */
    val autoLock: AutoLockController = AutoLockController(vaultLockState)

    /** Shows the fingerprint/PIN prompt and opens [vaultLockState] after a proven unlock. */
    val biometricUnlocker: BiometricUnlocker = BiometricUnlocker(
        masterKeyManager = masterKeyManager,
        vaultLockState = vaultLockState,
        healthMonitor = vaultHealthMonitor,
        onBeforeSystemScreen = autoLock::issueExternalScreenPass,
    )

    /** Wraps and unwraps document keys and the database passphrase with the master key. */
    val keyWrapper: KeyWrapper = KeyWrapper(masterKeyManager)

    /** The SQLCipher passphrase. [databaseHolder] wipes its in-memory copy on every lock. */
    val databaseKeyProvider: DatabaseKeyProvider = DatabaseKeyProvider(keyWrapper, vaultFiles.passphraseFile)

    /** Encrypts documents into the vault and decrypts them only after full verification. */
    val fileCrypto: FileCrypto = FileCrypto(vaultFiles.vaultDir, vaultFiles.viewTempDir)

    /** Deletes half-finished encryptions and leftover decrypted files. */
    val tempFileCleaner: TempFileCleaner = TempFileCleaner(vaultFiles.vaultDir, vaultFiles.viewTempDir)

    /**
     * Opens the SQLCipher database while unlocked. On lock it closes the database, then wipes
     * the passphrase (it registers itself with [vaultLockState]).
     */
    val databaseHolder: VaultDatabaseHolder =
        VaultDatabaseHolder(appContext, vaultLockState, databaseKeyProvider, VAULT_DATABASE_NAME)

    /** The only class screens use for documents. */
    val vaultRepository: VaultRepository =
        VaultRepository(databaseHolder, vaultLockState, keyWrapper, fileCrypto)

    /** Deletes the whole vault for good (recovery screen now, Settings in Task 1.10). */
    val vaultEraser: VaultEraser =
        VaultEraser(appContext, vaultLockState, databaseHolder, masterKeyManager, vaultHealthMonitor, vaultFiles)

    /** Which vault screen is showing; back to the list on every lock and unlock (memory only). */
    val vaultNavigator: VaultNavigator = VaultNavigator(vaultLockState)

    /** Saves picked files into the vault. */
    val addDocumentSaver: AddDocumentSaver = AddDocumentSaver(vaultRepository, ::freeSpaceForVault)

    // The vault folder lives inside filesDir, so filesDir's storage is the vault's storage.
    // getAllocatableBytes also counts cache Android can clear on its own, so a nearly full phone
    // is not wrongly told "storage full".
    private fun freeSpaceForVault(): Long {
        val filesDir = appContext.filesDir
        val storageManager = appContext.getSystemService(StorageManager::class.java)
        return try {
            val storageId = storageManager.getUuidForPath(filesDir)
            storageManager.getAllocatableBytes(storageId)
        } catch (notAvailable: IOException) {
            filesDir.usableSpace
        }
    }
}

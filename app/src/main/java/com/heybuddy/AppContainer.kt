package com.heybuddy

import android.content.Context
import android.content.Context.MODE_PRIVATE
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
import com.heybuddy.settings.AppSettings
import com.heybuddy.settings.DeleteEverythingFlow
import com.heybuddy.settings.SharedPreferencesStore
import com.heybuddy.ui.AutoLockController
import com.heybuddy.ui.VaultNavigator
import com.heybuddy.ocr.DocumentPageReader
import com.heybuddy.ocr.MergedTextReader
import com.heybuddy.ocr.MlKitTextReader
import com.heybuddy.ocr.TesseractTextReader
import com.heybuddy.ocr.ReadingSession
import com.heybuddy.ui.adddocument.AddDocumentSaver
import com.heybuddy.ui.adddocument.PagePreparer
import com.heybuddy.util.DB_PASSPHRASE_FILE_NAME
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SETTINGS_FILE_NAME
import com.heybuddy.util.SafeLog
import com.heybuddy.util.VAULT_DATABASE_NAME
import com.heybuddy.util.VAULT_FOLDER_NAME
import com.heybuddy.util.VIEW_TEMP_FOLDER_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /**
     * Theme, auto-lock time and "onboarding done". Not secret, so stored outside the vault in
     * app-private preferences (never backed up: rule 7 excludes everything).
     */
    val appSettings: AppSettings =
        AppSettings(SharedPreferencesStore(appContext.getSharedPreferences(SETTINGS_FILE_NAME, MODE_PRIVATE)))

    /** Locks automatically: on leaving the app, screen off, idle, and when a picker pass runs out (rule 9). */
    val autoLock: AutoLockController = AutoLockController(
        lockState = vaultLockState,
        idleLimitSeconds = { appSettings.autoLock.value.seconds },
    )

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
    val vaultEraser: VaultEraser = VaultEraser(
        context = appContext,
        lockState = vaultLockState,
        holder = databaseHolder,
        masterKeyManager = masterKeyManager,
        healthMonitor = vaultHealthMonitor,
        files = vaultFiles,
        // A real fresh start: theme and auto-lock back to defaults, and onboarding shows again.
        onErased = appSettings::resetAll,
    )

    /** Settings → "Delete everything": "Confirm it's you", then [vaultEraser]. */
    val deleteEverythingFlow: DeleteEverythingFlow = DeleteEverythingFlow(erase = vaultEraser::eraseEverything)

    /** Which vault screen is showing; back to the list on every lock and unlock (memory only). */
    val vaultNavigator: VaultNavigator = VaultNavigator(vaultLockState)

    /** Turns picked files into pending (encrypted, not yet saved) pages. */
    val pagePreparer: PagePreparer = PagePreparer(appContext.contentResolver, vaultRepository, ::freeSpaceForVault)

    /** Saves pending pages as a new document, or adds them to an existing one. */
    val addDocumentSaver: AddDocumentSaver = AddDocumentSaver(vaultRepository)

    /** Reading results of this unlocked session; cleared on every lock (spec 7.3-2). */
    val readingSession: ReadingSession = ReadingSession(vaultLockState)

    /**
     * Reads vault pages on the phone: ML Kit (English, Hindi) and Tesseract (Tamil), merged (spec
     * 7.4a). Both engines start on first use, so creating them here costs nothing.
     */
    val documentPageReader: DocumentPageReader = DocumentPageReader(
        repository = vaultRepository,
        reader = MergedTextReader(MlKitTextReader(), TesseractTextReader(appContext)),
        session = readingSession,
    )

    // For app-wide background work that must not depend on any screen (orphan cleanup).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Once after every unlock (owner decision): remove .bin files left by a crash.
        vaultLockState.addOnUnlockListener { appScope.launch { removeOrphanFilesSafely() } }
    }

    private suspend fun removeOrphanFilesSafely() {
        try {
            vaultRepository.removeOrphanFiles()
        } catch (failed: Exception) {
            // Not urgent: an orphan file is unreadable anyway; the next unlock tries again.
            SafeLog.error(LogEvent.ORPHAN_CLEANUP_FAILED, failed)
        }
    }

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

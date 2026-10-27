package com.heybuddy.data

import android.content.Context
import com.heybuddy.security.MasterKeyManager
import com.heybuddy.security.VaultHealthMonitor
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Deletes the whole vault for good (rule 13): used by the recovery screen (rule 14, Task 1.9) and
 * by Settings → "Delete everything" (Task 1.10).
 *
 * Order: lock and close the database first; then delete the master key, which makes everything
 * unreadable at once (crypto-shredding), even if a later file delete were to fail; then the
 * database (with its side files), the passphrase file, the encrypted files and view_tmp.
 *
 * @param context Any context; only the application context is kept.
 * @param lockState The app's lock; locked first so nothing is in use.
 * @param holder The database; waited on until it has closed.
 * @param masterKeyManager The Keystore master key to delete.
 * @param healthMonitor Told that the old vault is gone, so the app starts fresh.
 * @param files Where the vault's data lives.
 */
class VaultEraser(
    context: Context,
    private val lockState: VaultLockState,
    private val holder: VaultDatabaseHolder,
    private val masterKeyManager: MasterKeyManager,
    private val healthMonitor: VaultHealthMonitor,
    private val files: VaultFiles,
) {

    private val appContext: Context = context.applicationContext

    /** Deletes everything. Runs to the end once started, even if the screen goes away. */
    suspend fun eraseEverything() {
        withContext(Dispatchers.IO + NonCancellable) {
            lockState.lock()
            holder.awaitPendingWork()
            masterKeyManager.deleteMasterKey()
            // deleteDatabase also removes SQLite's -wal, -shm and -journal side files.
            appContext.deleteDatabase(files.databaseFile.name)
            files.passphraseFile.delete()
            files.vaultDir.deleteRecursively()
            files.viewTempDir.deleteRecursively()
        }
        healthMonitor.onVaultErased()
        SafeLog.info(LogEvent.VAULT_ERASED)
    }
}

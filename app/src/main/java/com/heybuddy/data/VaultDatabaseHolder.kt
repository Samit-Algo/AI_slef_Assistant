package com.heybuddy.data

import android.content.Context
import androidx.room.Room
import com.heybuddy.security.DatabaseKeyProvider
import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** The vault is locked, so the database is closed and must not be opened. */
class VaultLockedException : IllegalStateException("The vault is locked")

/**
 * Opens the SQLCipher database only while the vault is unlocked, and closes it on every lock.
 *
 * One [Mutex] makes database work and the close-on-lock take turns, so they never overlap.
 * Chosen approach for a lock during a save: the save FINISHES FIRST, then the database closes.
 * A finished save is always consistent (file and row both exist); stopping halfway would add
 * more failure paths. lock() itself never waits: the status flips to LOCKED at once (screens
 * switch, new work is refused), and the close runs as soon as the current work is done.
 *
 * Registers itself with [lockState] when created.
 *
 * @param context Any context; only the application context is kept.
 * @param lockState The app's lock. Database work is refused unless it says UNLOCKED.
 * @param keyProvider Gives a fresh copy of the passphrase for each open.
 * @param databaseName The database file name ([com.heybuddy.util.VAULT_DATABASE_NAME] in the app).
 */
class VaultDatabaseHolder(
    context: Context,
    private val lockState: VaultLockState,
    private val keyProvider: DatabaseKeyProvider,
    private val databaseName: String,
) {

    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()

    // Lock/unlock listeners must not block, so their work runs here under the mutex. One thread
    // at a time keeps it in request order: a quick lock-then-unlock always closes before it reopens.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // Only touched while holding the mutex.
    private var openDatabase: VaultDatabase? = null

    // SQLCipher keeps the array it is given for as long as the database is open (it does not
    // wipe it), so we keep a reference and wipe it ourselves right after closing.
    private var passphraseGivenToSqlCipher: ByteArray? = null

    init {
        lockState.addOnLockListener { onVaultLocked() }
        lockState.addOnUnlockListener { onVaultUnlocked() }
    }

    /**
     * Runs [work] with the open database, while no lock-close can happen.
     *
     * @param work The database work. Runs while holding the mutex, so keep it to one operation.
     * @return What [work] returned.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun <T> withDatabase(work: suspend (VaultDatabase) -> T): T {
        mutex.withLock {
            val database = openIfUnlocked()
            return work(database)
        }
    }

    /**
     * Waits until all queued background work (closing on lock, preparing on unlock) has run.
     * Used by tests; the app itself never needs to wait.
     */
    suspend fun awaitPendingWork() {
        val marker = backgroundScope.launch { mutex.withLock { } }
        marker.join()
    }

    /** Test hook: whether the database is open right now. */
    internal suspend fun isOpenForTest(): Boolean {
        mutex.withLock {
            return openDatabase?.isOpen == true
        }
    }

    /** The absolute path of the database file (for tests and Task 1.10's "Delete everything"). */
    fun databaseFilePath(): String = appContext.getDatabasePath(databaseName).absolutePath

    private fun onVaultLocked() {
        backgroundScope.launch {
            mutex.withLock {
                closeNow()
                // The database is closed, so the cached passphrase is no longer needed.
                keyProvider.clear()
            }
        }
    }

    private fun onVaultUnlocked() {
        // Read the passphrase now, while the fingerprint/PIN key window is surely open, so opening
        // the database later never needs the master key (and never asks for fingerprint/PIN).
        backgroundScope.launch {
            mutex.withLock {
                warmPassphraseCache()
            }
        }
    }

    private fun warmPassphraseCache() {
        try {
            val copy = keyProvider.getPassphrase()
            copy.fill(0)
        } catch (failure: Exception) {
            // Not fatal: the first database use will try again (and can ask for fingerprint/PIN).
            SafeLog.error(LogEvent.DB_PASSPHRASE_WARM_FAILED, failure)
        }
    }

    private fun openIfUnlocked(): VaultDatabase {
        if (lockState.status.value != LockStatus.UNLOCKED) {
            throw VaultLockedException()
        }
        return openDatabase ?: openNow()
    }

    private fun openNow(): VaultDatabase {
        SqlCipherLibrary.ensureLoaded()
        // A fresh copy every time: whatever SQLCipher does with it can never touch our cache.
        val passphraseCopy = keyProvider.getPassphrase()
        val factory = SupportOpenHelperFactory(passphraseCopy)
        val database = Room.databaseBuilder(appContext, VaultDatabase::class.java, databaseName)
            .openHelperFactory(factory)
            // Real migrations only, never a destructive fallback (see VaultDatabase).
            .addMigrations(*VaultDatabase.ALL_MIGRATIONS)
            .build()
        try {
            // Room opens lazily; open now so a wrong passphrase fails here, not in a later query.
            database.openHelper.writableDatabase
        } catch (openFailed: Exception) {
            database.close()
            passphraseCopy.fill(0)
            throw openFailed
        }
        openDatabase = database
        passphraseGivenToSqlCipher = passphraseCopy
        SafeLog.info(LogEvent.DATABASE_OPENED)
        return database
    }

    private fun closeNow() {
        val database = openDatabase ?: return
        database.close()
        openDatabase = null
        passphraseGivenToSqlCipher?.fill(0)
        passphraseGivenToSqlCipher = null
        SafeLog.info(LogEvent.DATABASE_CLOSED)
    }
}

/** Loads SQLCipher's native code once per process, before the first database open. */
private object SqlCipherLibrary {

    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("sqlcipher")
        loaded = true
    }
}

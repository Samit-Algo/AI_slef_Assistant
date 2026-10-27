package com.heybuddy.security

import android.util.AtomicFile
import com.heybuddy.util.DB_PASSPHRASE_ASSOCIATED_DATA
import com.heybuddy.util.DB_PASSPHRASE_BYTES
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Creates and unwraps the SQLCipher database passphrase (rule 5).
 *
 * The passphrase is 32 random bytes. On storage it exists only in wrapped form (encrypted by
 * the master key). The unwrapped bytes live in memory only while the vault is unlocked:
 * [clear] wipes them, and the app calls it on every lock.
 *
 * TODO(Task 1.6): SQLCipher's open-helper factory may wipe the byte array it is given.
 *  Always pass it a fresh copy from [getPassphrase], never anything we keep.
 *
 * @param keyWrapper Wraps and unwraps the passphrase with the master key.
 * @param passphraseFile Where the wrapped passphrase is stored (noBackupFilesDir in the app).
 * @param secureRandom Source of the passphrase bytes.
 */
class DatabaseKeyProvider(
    private val keyWrapper: KeyWrapper,
    private val passphraseFile: File,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    // Guards cachedPassphrase: unlock, database open and lock can happen on different threads.
    private val cacheLock = Any()
    private var cachedPassphrase: ByteArray? = null

    private val associatedData: ByteArray = DB_PASSPHRASE_ASSOCIATED_DATA.toByteArray(Charsets.UTF_8)

    /**
     * Returns the database passphrase, creating and saving it the first time.
     *
     * Needs the master key, so it can throw UserNotAuthenticatedException after the
     * fingerprint/PIN window; call it through [MasterKeyAccess].
     *
     * @return A NEW COPY of the passphrase. The caller must wipe it (fill with zeros) when done.
     *   Wiping the copy never affects the copy kept here.
     */
    fun getPassphrase(): ByteArray {
        synchronized(cacheLock) {
            val passphrase = cachedPassphrase ?: loadOrCreatePassphrase()
            cachedPassphrase = passphrase
            return passphrase.copyOf()
        }
    }

    /**
     * Wipes the in-memory passphrase. Safe to call when nothing is cached.
     *
     * Best effort only: we zero our own array, but the JVM may have made copies we cannot reach
     * (for example while moving memory around), and Keystore/Cipher may keep internal copies.
     */
    fun clear() {
        synchronized(cacheLock) {
            val passphrase = cachedPassphrase ?: return
            passphrase.fill(0)
            cachedPassphrase = null
        }
        SafeLog.info(LogEvent.DB_PASSPHRASE_CLEARED)
    }

    /** Test hook: the cached array itself (not a copy), so tests can check it gets wiped. */
    internal fun cachedPassphraseForTest(): ByteArray? {
        synchronized(cacheLock) {
            return cachedPassphrase
        }
    }

    private fun loadOrCreatePassphrase(): ByteArray {
        if (passphraseFile.exists()) {
            return readAndUnwrapPassphrase()
        }
        return createAndSavePassphrase()
    }

    private fun createAndSavePassphrase(): ByteArray {
        val passphrase = ByteArray(DB_PASSPHRASE_BYTES)
        secureRandom.nextBytes(passphrase)
        try {
            val wrappedPassphrase = keyWrapper.wrapBytes(passphrase, associatedData)
            val fileBytes = PassphraseFileFormat.encode(wrappedPassphrase)
            writeFileAtomically(fileBytes)
        } catch (saveFailed: Exception) {
            // Not saved (for example the unlock window expired): wipe it, a new one is made next time.
            passphrase.fill(0)
            throw saveFailed
        }
        SafeLog.info(LogEvent.DB_PASSPHRASE_CREATED)
        return passphrase
    }

    private fun readAndUnwrapPassphrase(): ByteArray {
        val atomicFile = AtomicFile(passphraseFile)
        val fileBytes = atomicFile.readFully()
        val wrappedPassphrase = PassphraseFileFormat.decode(fileBytes)
        return keyWrapper.unwrapBytes(wrappedPassphrase, associatedData)
    }

    // AtomicFile writes to a temporary file and renames it, so a crash never leaves half a file.
    private fun writeFileAtomically(fileBytes: ByteArray) {
        val atomicFile = AtomicFile(passphraseFile)
        val output = atomicFile.startWrite()
        try {
            output.write(fileBytes)
            atomicFile.finishWrite(output)
        } catch (writeFailed: IOException) {
            atomicFile.failWrite(output)
            throw writeFailed
        }
    }
}

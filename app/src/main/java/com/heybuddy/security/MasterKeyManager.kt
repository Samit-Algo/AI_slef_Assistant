package com.heybuddy.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.heybuddy.util.LogEvent
import com.heybuddy.util.MASTER_KEY_ALIAS
import com.heybuddy.util.MASTER_KEY_SIZE_BITS
import com.heybuddy.util.SafeLog
import com.heybuddy.util.VAULT_UNLOCK_WINDOW_SECONDS
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Where a Keystore key physically lives. Used by tests and the Phase 1 report. */
enum class KeyStorageLevel {
    /** A separate security chip (best). */
    STRONGBOX,

    /** The secure area inside the main processor (Trusted Execution Environment). */
    TRUSTED_ENVIRONMENT,

    /** Not in secure hardware. Should never happen on a Play-certified phone. */
    SOFTWARE,

    /** Android reported a level this code does not know. */
    UNKNOWN,
}

/**
 * Creates, finds and deletes the vault's master key in Android Keystore (rule 2).
 *
 * The key never leaves the Keystore: the app only gets a handle to it, and every
 * encrypt/decrypt happens inside secure hardware.
 *
 * Important: the master key being usable does NOT mean the vault is unlocked.
 * Simply unlocking the phone with its PIN also opens the key's 30-second window,
 * so the app's own lock state (Task 1.3) is what decides whether the vault is open.
 *
 * @param keyAlias Name of the key in Keystore. The app uses [MASTER_KEY_ALIAS]; tests pass
 *   a different name so they can never delete the real vault key.
 */
class MasterKeyManager(private val keyAlias: String = MASTER_KEY_ALIAS) {

    // Stops two callers from both seeing "no key" and creating two different keys.
    private val creationLock = Any()

    /**
     * Returns the master key, creating it the first time.
     *
     * Creating the key does not need a fingerprint/PIN, but the phone must have a screen lock.
     *
     * @return A Keystore handle to the master key (its bytes are never available).
     */
    fun getOrCreateMasterKey(): SecretKey {
        synchronized(creationLock) {
            val existingKey = findExistingKey()
            if (existingKey != null) return existingKey
            return createKey()
        }
    }

    /**
     * Tells whether the master key exists.
     *
     * @return true if the key is in Keystore.
     */
    fun hasMasterKey(): Boolean {
        val keyStore = loadKeyStore()
        return keyStore.containsAlias(keyAlias)
    }

    /**
     * Deletes the master key. Everything it protected becomes unreadable forever.
     * Does nothing if the key does not exist.
     */
    fun deleteMasterKey() {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(keyAlias)) return
        keyStore.deleteEntry(keyAlias)
        SafeLog.info(LogEvent.MASTER_KEY_DELETED)
    }

    /**
     * Tells where the master key physically lives.
     *
     * @return The storage level, or null if the key does not exist.
     */
    fun getKeyStorageLevel(): KeyStorageLevel? {
        val key = findExistingKey() ?: return null
        val keyInfo = readKeyInfo(key)
        return toStorageLevel(keyInfo)
    }

    /**
     * Reads the Keystore's description of the key: its auth rules and hardware level.
     *
     * @param key A key handle from this manager.
     * @return The key's [KeyInfo].
     */
    fun readKeyInfo(key: SecretKey): KeyInfo {
        val keyFactory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        return keyFactory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    private fun findExistingKey(): SecretKey? {
        val keyStore = loadKeyStore()
        return keyStore.getKey(keyAlias, null) as SecretKey?
    }

    private fun createKey(): SecretKey {
        // Only StrongBoxUnavailableException means "try again without StrongBox".
        // Any other error is a real problem and must reach the caller.
        return try {
            val key = generateKey(useStrongBox = true)
            SafeLog.info(LogEvent.MASTER_KEY_CREATED_STRONGBOX)
            key
        } catch (strongBoxMissing: StrongBoxUnavailableException) {
            val key = generateKey(useStrongBox = false)
            SafeLog.info(LogEvent.MASTER_KEY_CREATED_TEE)
            key
        }
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey {
        val keySpec = buildKeySpec(useStrongBox)
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun buildKeySpec(useStrongBox: Boolean): KeyGenParameterSpec {
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val allowedAuthTypes = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL

        return KeyGenParameterSpec.Builder(keyAlias, purposes)
            .setKeySize(MASTER_KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Keystore picks a fresh random IV for every encryption. Our code must never
            // supply its own IV: reusing one with GCM would break the encryption.
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(VAULT_UNLOCK_WINDOW_SECONDS, allowedAuthTypes)
            // PIN unlock is allowed too, so wiping the key when a new fingerprint is added
            // gives little extra security but would destroy the whole vault (there is no backup).
            // Removing the screen lock still invalidates the key; Task 1.9 handles that (rule 14).
            .setInvalidatedByBiometricEnrollment(false)
            // The key cannot be used while the phone is locked, even inside the 30-second window.
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(useStrongBox)
            .build()
    }

    private fun toStorageLevel(keyInfo: KeyInfo): KeyStorageLevel {
        // securityLevel only exists on Android 12+; on Android 11 we can only tell "hardware or not".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val inHardware = keyInfo.isInsideSecureHardware
            return if (inHardware) KeyStorageLevel.TRUSTED_ENVIRONMENT else KeyStorageLevel.SOFTWARE
        }
        return when (keyInfo.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyStorageLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyStorageLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyStorageLevel.SOFTWARE
            else -> KeyStorageLevel.UNKNOWN
        }
    }

    private fun loadKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore
    }

    private companion object {
        /** Name of Android's built-in Keystore provider. */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

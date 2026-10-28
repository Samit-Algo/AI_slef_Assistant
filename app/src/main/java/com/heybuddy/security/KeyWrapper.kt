package com.heybuddy.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters

/**
 * Wraps (encrypts) and unwraps keys with the Keystore master key (rules 3 and 5).
 *
 * Page keys use Tink's own encrypted-keyset format, so we do not invent a file format.
 * Every wrap is bound to its owner with associated data: unwrapping with a different owner fails.
 *
 * Every call needs the master key, so it can throw UserNotAuthenticatedException once the
 * fingerprint/PIN window has passed. Callers in the app go through [MasterKeyAccess], which asks again.
 *
 * About wiping key material: Tink keeps keys inside its own KeysetHandle objects, which cannot be
 * wiped from our code. We only hold them as long as needed and then drop the reference.
 *
 * @param masterKeyManager Gives access to the master key.
 */
class KeyWrapper(private val masterKeyManager: MasterKeyManager) {

    /**
     * Creates a new random key for one page (rule 3: streaming AEAD, AES256_GCM_HKDF_4KB).
     *
     * @return The new page key. Not wrapped yet.
     */
    fun generatePageKey(): KeysetHandle {
        return KeysetHandle.generateNew(
            PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB,
            RegistryConfiguration.get(),
        )
    }

    /**
     * Encrypts a NEW page's key with the master key, bound to its page and document (rule 4).
     *
     * @param pageKey The key to wrap.
     * @param binding The new page's binding. Must be the current format: new pages never use the
     *   old "document:<id>" label (owner decision, Task 1.10p).
     * @return The wrapped key, safe to store in the database.
     * @throws IllegalArgumentException If [binding] is not the current format.
     */
    fun wrapPageKey(pageKey: KeysetHandle, binding: PageBinding): ByteArray {
        require(binding.isCurrentFormat) { "New pages must use the current page format" }
        val masterAead = loadMasterAead()
        return TinkProtoKeysetFormat.serializeEncryptedKeyset(
            pageKey,
            masterAead,
            binding.associatedData(),
            RegistryConfiguration.get(),
        )
    }

    /**
     * Decrypts a wrapped page key.
     *
     * @param wrappedKey Bytes from [wrapPageKey] (or, for a migrated page, from before Task 1.10p).
     * @param binding The page the key must belong to.
     * @return The page key.
     * @throws java.security.GeneralSecurityException If the bytes were changed, belong to another
     *   page, or the master key cannot be used right now.
     */
    fun unwrapPageKey(wrappedKey: ByteArray, binding: PageBinding): KeysetHandle {
        val masterAead = loadMasterAead()
        return TinkProtoKeysetFormat.parseEncryptedKeyset(
            wrappedKey,
            masterAead,
            binding.associatedData(),
            RegistryConfiguration.get(),
        )
    }

    /**
     * Encrypts raw secret bytes (used for the database passphrase) with the master key.
     *
     * @param secret The bytes to protect. Not changed or wiped here; the caller owns them.
     * @param associatedData Names the owner of these bytes.
     * @return Keystore AES-GCM output: Keystore's random IV followed by the ciphertext.
     */
    fun wrapBytes(secret: ByteArray, associatedData: ByteArray): ByteArray {
        val masterAead = loadMasterAead()
        return masterAead.encrypt(secret, associatedData)
    }

    /**
     * Decrypts bytes from [wrapBytes].
     *
     * @param wrappedSecret Bytes from [wrapBytes].
     * @param associatedData Must match what was used to wrap.
     * @return The secret bytes. The caller must wipe them when done.
     * @throws java.security.GeneralSecurityException If the bytes were changed, belong to another
     *   owner, or the master key cannot be used right now.
     */
    fun unwrapBytes(wrappedSecret: ByteArray, associatedData: ByteArray): ByteArray {
        val masterAead = loadMasterAead()
        return masterAead.decrypt(wrappedSecret, associatedData)
    }

    private fun loadMasterAead(): Aead {
        masterKeyManager.getOrCreateMasterKey()
        // Tink's plain Keystore AEAD: Keystore picks the IV, and errors such as
        // UserNotAuthenticatedException reach us unchanged. (Tink's AndroidKeystoreAesGcm
        // would add a silent retry and write its own log lines, which we do not want.)
        return AndroidKeystore.getAead(masterKeyManager.keyAlias)
    }
}

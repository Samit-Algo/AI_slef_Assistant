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
 * Document keys use Tink's own encrypted-keyset format, so we do not invent a file format.
 * Every wrap is bound to its owner with associated data: unwrapping with a different owner fails.
 *
 * Every call needs the master key, so it can throw UserNotAuthenticatedException once the
 * 30-second window has passed. Callers in the app go through [MasterKeyAccess], which asks again.
 *
 * About wiping key material: Tink keeps keys inside its own KeysetHandle objects, which cannot be
 * wiped from our code. We only hold them as long as needed and then drop the reference.
 *
 * @param masterKeyManager Gives access to the master key.
 */
class KeyWrapper(private val masterKeyManager: MasterKeyManager) {

    /**
     * Creates a new random key for one document (rule 3: streaming AEAD, AES256_GCM_HKDF_4KB).
     *
     * @return The new document key. Not wrapped yet.
     */
    fun generateDocumentKey(): KeysetHandle {
        return KeysetHandle.generateNew(
            PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB,
            RegistryConfiguration.get(),
        )
    }

    /**
     * Encrypts a document key with the master key, bound to its document.
     *
     * @param documentKey The key to wrap.
     * @param documentId The owning document's id (rule 4: binds the key to its record).
     * @return The wrapped key, safe to store in the database.
     */
    fun wrapDocumentKey(documentKey: KeysetHandle, documentId: String): ByteArray {
        val masterAead = loadMasterAead()
        val associatedData = documentAssociatedData(documentId)
        return TinkProtoKeysetFormat.serializeEncryptedKeyset(
            documentKey,
            masterAead,
            associatedData,
            RegistryConfiguration.get(),
        )
    }

    /**
     * Decrypts a wrapped document key.
     *
     * @param wrappedKey Bytes from [wrapDocumentKey].
     * @param documentId The document the key must belong to.
     * @return The document key.
     * @throws java.security.GeneralSecurityException If the bytes were changed, belong to another
     *   owner, or the master key cannot be used right now.
     */
    fun unwrapDocumentKey(wrappedKey: ByteArray, documentId: String): KeysetHandle {
        val masterAead = loadMasterAead()
        val associatedData = documentAssociatedData(documentId)
        return TinkProtoKeysetFormat.parseEncryptedKeyset(
            wrappedKey,
            masterAead,
            associatedData,
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

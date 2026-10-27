package com.heybuddy.security

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Sample content used to check that two document keys are the same key. */
internal val SAMPLE_CONTENT: ByteArray = "sample document bytes for key tests".toByteArray(Charsets.UTF_8)

/** Associated data used only inside these key-comparison helpers. */
private val SAMPLE_ASSOCIATED_DATA: ByteArray = "test-file".toByteArray(Charsets.UTF_8)

/** Encrypts [SAMPLE_CONTENT] with a document key. */
internal fun encryptSample(documentKey: KeysetHandle): ByteArray {
    val streamingAead = documentKey.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    val output = ByteArrayOutputStream()
    streamingAead.newEncryptingStream(output, SAMPLE_ASSOCIATED_DATA).use { encrypting ->
        encrypting.write(SAMPLE_CONTENT)
    }
    return output.toByteArray()
}

/** Decrypts output of [encryptSample] with a (possibly different) document key. */
internal fun decryptSample(documentKey: KeysetHandle, ciphertext: ByteArray): ByteArray {
    val streamingAead = documentKey.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    val input = ByteArrayInputStream(ciphertext)
    return streamingAead.newDecryptingStream(input, SAMPLE_ASSOCIATED_DATA).use { decrypting ->
        decrypting.readBytes()
    }
}

/** Returns a copy of [bytes] with one byte in the middle changed. */
internal fun tamperWith(bytes: ByteArray): ByteArray {
    val tampered = bytes.copyOf()
    val middle = tampered.size / 2
    tampered[middle] = (tampered[middle].toInt() xor 0x01).toByte()
    return tampered
}

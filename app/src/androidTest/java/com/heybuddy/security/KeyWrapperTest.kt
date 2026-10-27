package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException

/**
 * Logic tests for [KeyWrapper] (rules 3 and 4).
 *
 * Uses a test key WITHOUT the fingerprint/PIN requirement, so results never depend on the
 * 30-second window. The auth-required behaviour is tested in [AuthRequiredKeyWrapTest].
 */
@RunWith(AndroidJUnit4::class)
class KeyWrapperTest {

    private val masterKeyManager = MasterKeyManager(
        keyAlias = TEST_KEY_ALIAS,
        requireUserAuthentication = false,
    )
    private val keyWrapper = KeyWrapper(masterKeyManager)

    @Before
    fun removeLeftoverKey() {
        requirePhoneUnlocked()
        masterKeyManager.deleteMasterKey()
    }

    @After
    fun deleteTestKey() {
        // @After runs even when a test fails, so the test key never stays on the phone.
        masterKeyManager.deleteMasterKey()
    }

    @Test
    fun wrapThenUnwrapGivesTheSameKey() {
        val documentKey = keyWrapper.generateDocumentKey()

        val wrappedKey = keyWrapper.wrapDocumentKey(documentKey, DOCUMENT_A)
        val unwrappedKey = keyWrapper.unwrapDocumentKey(wrappedKey, DOCUMENT_A)

        // Same key = what one encrypts, the other decrypts.
        val ciphertext = encryptSample(documentKey)
        val decrypted = decryptSample(unwrappedKey, ciphertext)
        assertArrayEquals(SAMPLE_CONTENT, decrypted)
    }

    @Test
    fun tamperedWrappedKeyFails() {
        val documentKey = keyWrapper.generateDocumentKey()
        val wrappedKey = keyWrapper.wrapDocumentKey(documentKey, DOCUMENT_A)

        val tamperedKey = tamperWith(wrappedKey)

        assertThrows(GeneralSecurityException::class.java) {
            keyWrapper.unwrapDocumentKey(tamperedKey, DOCUMENT_A)
        }
    }

    @Test
    fun keyWrappedForOneDocumentDoesNotOpenForAnother() {
        val documentKey = keyWrapper.generateDocumentKey()
        val wrappedKey = keyWrapper.wrapDocumentKey(documentKey, DOCUMENT_A)

        assertThrows(GeneralSecurityException::class.java) {
            keyWrapper.unwrapDocumentKey(wrappedKey, DOCUMENT_B)
        }
    }

    @Test
    fun wrappedBytesOnlyOpenWithTheirOwnLabel() {
        val secret = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val wrapped = keyWrapper.wrapBytes(secret, LABEL_ONE)

        val unwrapped = keyWrapper.unwrapBytes(wrapped, LABEL_ONE)

        assertArrayEquals(secret, unwrapped)
        assertThrows(GeneralSecurityException::class.java) {
            keyWrapper.unwrapBytes(wrapped, LABEL_TWO)
        }
    }

    private companion object {
        const val TEST_KEY_ALIAS = "hb_master_key_wrap_test"
        const val DOCUMENT_A = "11111111-1111-1111-1111-111111111111"
        const val DOCUMENT_B = "22222222-2222-2222-2222-222222222222"
        val LABEL_ONE: ByteArray = "label-one".toByteArray(Charsets.UTF_8)
        val LABEL_TWO: ByteArray = "label-two".toByteArray(Charsets.UTF_8)
    }
}

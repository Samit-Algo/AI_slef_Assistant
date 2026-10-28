package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heybuddy.util.PAGE_FORMAT_LEGACY
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
 * fingerprint/PIN window. The auth-required behaviour is tested in [AuthRequiredKeyWrapTest].
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
        val documentKey = keyWrapper.generatePageKey()

        val wrappedKey = keyWrapper.wrapPageKey(documentKey, PAGE_IN_A)
        val unwrappedKey = keyWrapper.unwrapPageKey(wrappedKey, PAGE_IN_A)

        // Same key = what one encrypts, the other decrypts.
        val ciphertext = encryptSample(documentKey)
        val decrypted = decryptSample(unwrappedKey, ciphertext)
        assertArrayEquals(SAMPLE_CONTENT, decrypted)
    }

    @Test
    fun tamperedWrappedKeyFails() {
        val documentKey = keyWrapper.generatePageKey()
        val wrappedKey = keyWrapper.wrapPageKey(documentKey, PAGE_IN_A)

        val tamperedKey = tamperWith(wrappedKey)

        assertThrows(GeneralSecurityException::class.java) {
            keyWrapper.unwrapPageKey(tamperedKey, PAGE_IN_A)
        }
    }

    @Test
    fun keyWrappedForOneDocumentDoesNotOpenForAnother() {
        val documentKey = keyWrapper.generatePageKey()
        val wrappedKey = keyWrapper.wrapPageKey(documentKey, PAGE_IN_A)

        assertThrows(GeneralSecurityException::class.java) {
            keyWrapper.unwrapPageKey(wrappedKey, SAME_PAGE_ID_IN_B)
        }
    }

    @Test
    fun keyWrappedForOnePageDoesNotOpenForAnotherPageOfTheSameDocument() {
        val pageKey = keyWrapper.generatePageKey()
        val wrappedKey = keyWrapper.wrapPageKey(pageKey, PAGE_IN_A)

        assertThrows(GeneralSecurityException::class.java) {
            keyWrapper.unwrapPageKey(wrappedKey, OTHER_PAGE_IN_A)
        }
    }

    /** Owner rule (Task 1.10p): new keys are never wrapped with the old "document:<id>" label. */
    @Test
    fun theLegacyBindingCannotBeUsedToWrapANewKey() {
        val legacy = PageBinding.forStoredPage(DOCUMENT_A, DOCUMENT_A, PAGE_FORMAT_LEGACY)

        assertThrows(IllegalArgumentException::class.java) {
            keyWrapper.wrapPageKey(keyWrapper.generatePageKey(), legacy)
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
        const val PAGE_ONE = "44444444-4444-4444-4444-444444444444"
        const val PAGE_TWO = "55555555-5555-5555-5555-555555555555"
        val PAGE_IN_A: PageBinding = PageBinding.forNewPage(DOCUMENT_A, PAGE_ONE)
        val OTHER_PAGE_IN_A: PageBinding = PageBinding.forNewPage(DOCUMENT_A, PAGE_TWO)
        val SAME_PAGE_ID_IN_B: PageBinding = PageBinding.forNewPage(DOCUMENT_B, PAGE_ONE)
        val LABEL_ONE: ByteArray = "label-one".toByteArray(Charsets.UTF_8)
        val LABEL_TWO: ByteArray = "label-two".toByteArray(Charsets.UTF_8)
    }
}

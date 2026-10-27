package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heybuddy.util.VAULT_UNLOCK_WINDOW_SECONDS
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.security.GeneralSecurityException

/**
 * Timing tests with an auth-required test key (same rules as hb_master_key).
 *
 * HOW TO RUN: unlock the phone with PIN, then start this class within about 10 seconds.
 * Do not touch or lock the phone; the second test waits about 35 seconds.
 * Methods run in name order so the quick round trip happens while the window is still open.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AuthRequiredKeyWrapTest {

    private val masterKeyManager = MasterKeyManager(keyAlias = TEST_KEY_ALIAS)
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
    fun test1_roundTripWorksRightAfterUnlock() {
        val documentKey = keyWrapper.generateDocumentKey()

        val wrappedKey = wrapOrExplain(documentKey)
        val unwrappedKey = keyWrapper.unwrapDocumentKey(wrappedKey, DOCUMENT_ID)

        val ciphertext = encryptSample(documentKey)
        assertArrayEquals(SAMPLE_CONTENT, decryptSample(unwrappedKey, ciphertext))
    }

    @Test
    fun test2_unwrapFailsOnceTheWindowHasExpired() {
        withScreenKeptOn { checkUnwrapFailsAfterWindow() }
    }

    private fun checkUnwrapFailsAfterWindow() {
        val documentKey = keyWrapper.generateDocumentKey()
        val wrappedKey = wrapOrExplain(documentKey)

        Thread.sleep(WAIT_PAST_WINDOW_MILLIS)

        try {
            keyWrapper.unwrapDocumentKey(wrappedKey, DOCUMENT_ID)
            fail("Unwrap still worked after the 30-second window")
        } catch (refused: GeneralSecurityException) {
            assertTrue("Refused for another reason: $refused", isMissingUserAuthentication(refused))
        }
    }

    /** Wraps the key; if the window is already closed, fails with instructions instead of a confusing error. */
    private fun wrapOrExplain(documentKey: com.google.crypto.tink.KeysetHandle): ByteArray {
        try {
            return keyWrapper.wrapDocumentKey(documentKey, DOCUMENT_ID)
        } catch (refused: GeneralSecurityException) {
            if (isMissingUserAuthentication(refused)) {
                fail("The unlock window was already closed. Unlock the phone with PIN, then run this test within 10 seconds.")
            }
            throw refused
        }
    }

    private companion object {
        const val TEST_KEY_ALIAS = "hb_master_key_wrap_auth_test"
        const val DOCUMENT_ID = "33333333-3333-3333-3333-333333333333"

        /** The 30-second window plus 5 seconds of margin. */
        const val WAIT_PAST_WINDOW_MILLIS = (VAULT_UNLOCK_WINDOW_SECONDS + 5) * 1000L
    }
}

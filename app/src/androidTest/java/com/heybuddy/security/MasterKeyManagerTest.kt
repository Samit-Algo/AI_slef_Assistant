package com.heybuddy.security

import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heybuddy.MainActivity
import com.heybuddy.util.MASTER_KEY_SIZE_BITS
import com.heybuddy.util.VAULT_UNLOCK_WINDOW_SECONDS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.InvalidKeyException
import java.security.KeyStore
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Checks [MasterKeyManager] against rule 2 on a real phone.
 *
 * Needs a phone with a screen lock (PIN, pattern or password).
 * Uses its own key name, so the real vault key is never touched.
 */
@RunWith(AndroidJUnit4::class)
class MasterKeyManagerTest {

    private val manager = MasterKeyManager(keyAlias = TEST_KEY_ALIAS)

    @Before
    fun removeLeftoverKey() {
        // A crashed earlier run could leave a key behind; every test starts from "no key".
        manager.deleteMasterKey()
    }

    @After
    fun deleteTestKey() {
        // @After runs even when a test fails, so the test key never stays on the phone.
        manager.deleteMasterKey()
    }

    @Test
    fun createsKeyWhenNoneExists() {
        assertFalse(manager.hasMasterKey())

        manager.getOrCreateMasterKey()

        assertTrue(manager.hasMasterKey())
    }

    @Test
    fun reusesExistingKeyInsteadOfCreatingANewOne() {
        manager.getOrCreateMasterKey()
        val firstCreationDate = readCreationDate()

        manager.getOrCreateMasterKey()
        val secondCreationDate = readCreationDate()

        assertEquals(firstCreationDate, secondCreationDate)
    }

    @Test
    fun deletesKeyOnRequest() {
        manager.getOrCreateMasterKey()

        manager.deleteMasterKey()

        assertFalse(manager.hasMasterKey())
        assertNull(manager.getKeyStorageLevel())
    }

    @Test
    fun keyIsInSecureHardwareAndCannotBeExported() {
        val key = manager.getOrCreateMasterKey()

        // Keystore keys return null here: the app never sees the key bytes.
        assertNull(key.encoded)

        val storageLevel = manager.getKeyStorageLevel()
        val inSecureHardware = storageLevel == KeyStorageLevel.STRONGBOX ||
            storageLevel == KeyStorageLevel.TRUSTED_ENVIRONMENT
        assertTrue("Key storage level was $storageLevel", inSecureHardware)
        println("$STORAGE_REPORT_PREFIX$storageLevel")
    }

    @Test
    fun keyRequiresFingerprintOrPinWithThirtySecondWindow() {
        val key = manager.getOrCreateMasterKey()
        val keyInfo = manager.readKeyInfo(key)
        val expectedAuthTypes = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL

        assertTrue(keyInfo.isUserAuthenticationRequired)
        assertEquals(VAULT_UNLOCK_WINDOW_SECONDS, keyInfo.userAuthenticationValidityDurationSeconds)
        assertEquals(expectedAuthTypes, keyInfo.userAuthenticationType)
        assertFalse(keyInfo.isInvalidatedByBiometricEnrollment)
        assertEquals(MASTER_KEY_SIZE_BITS, keyInfo.keySize)
    }

    /**
     * Unlocking the phone with its PIN also opens the key's 30-second window, so whether the
     * key is refused right now depends on when the phone was last unlocked.
     *
     * To make the result certain: if the first try is allowed, we are inside the window,
     * so we wait until the window has surely passed and try again. The second try must be refused.
     * Do not touch the phone or lock it while this test runs (it can take up to about 40 seconds).
     */
    @Test
    fun keyIsRefusedOnceTheAuthWindowHasExpired() {
        // If the screen turns off and the phone locks during the wait, Keystore refuses with
        // "device locked" instead, which would not prove that the 30-second window expired.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            checkKeyIsRefusedAfterWindow()
        }
    }

    private fun checkKeyIsRefusedAfterWindow() {
        val key = manager.getOrCreateMasterKey()

        val refusedAtOnce = isRefusedForMissingAuth(key)
        if (refusedAtOnce) return

        Thread.sleep(WAIT_PAST_WINDOW_MILLIS)

        val refusedAfterWaiting = isRefusedForMissingAuth(key)
        assertTrue("Key was still usable after the 30-second window", refusedAfterWaiting)
    }

    /** Tries to start an encryption. True if Keystore refused because nobody authenticated recently. */
    private fun isRefusedForMissingAuth(key: SecretKey): Boolean {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        return try {
            // No IV passed: Keystore must generate it (randomized encryption required).
            cipher.init(Cipher.ENCRYPT_MODE, key)
            false
        } catch (notAuthenticated: UserNotAuthenticatedException) {
            true
        } catch (otherRefusal: InvalidKeyException) {
            fail("Key was refused for a reason other than an expired window (did the phone lock?): $otherRefusal")
            false
        }
    }

    private fun readCreationDate(): Date {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val creationDate = keyStore.getCreationDate(TEST_KEY_ALIAS)
        assertNotNull(creationDate)
        return creationDate
    }

    private companion object {
        const val TEST_KEY_ALIAS = "hb_master_key_test"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        /** The 30-second window plus 5 seconds of margin for clock and scheduling delays. */
        const val WAIT_PAST_WINDOW_MILLIS = (VAULT_UNLOCK_WINDOW_SECONDS + 5) * 1000L

        /** Marker so the storage level is easy to find in the test output. */
        const val STORAGE_REPORT_PREFIX = "HB_KEY_STORAGE_LEVEL="
    }
}

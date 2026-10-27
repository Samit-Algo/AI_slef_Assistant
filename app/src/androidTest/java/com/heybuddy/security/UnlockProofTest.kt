package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heybuddy.util.VAULT_UNLOCK_WINDOW_SECONDS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the unlock proof step on a real phone: once the 30-second window has passed,
 * the proof must say NOT_AUTHENTICATED, so a fake "success" callback alone can never open the vault.
 *
 * Uses its own key name, so the real vault key is never touched.
 */
@RunWith(AndroidJUnit4::class)
class UnlockProofTest {

    private val masterKeyManager = MasterKeyManager(keyAlias = TEST_KEY_ALIAS)
    private val unlocker = BiometricUnlocker(masterKeyManager, VaultLockState())

    @Before
    fun removeLeftoverKey() {
        masterKeyManager.deleteMasterKey()
    }

    @After
    fun deleteTestKey() {
        // @After runs even when a test fails, so the test key never stays on the phone.
        masterKeyManager.deleteMasterKey()
    }

    /**
     * If the phone was unlocked in the last 30 seconds, the first check passes; we then wait
     * past the window and check again. Do not touch or lock the phone (up to about 40 seconds).
     */
    @Test
    fun proofFailsOnceTheAuthWindowHasExpired() {
        withScreenKeptOn { checkProofFailsAfterWindow() }
    }

    private fun checkProofFailsAfterWindow() {
        val firstCheck = unlocker.checkMasterKeyUsable()
        println("$REPORT_PREFIX first=$firstCheck")
        if (firstCheck == KeyCheckResult.NOT_AUTHENTICATED) return

        Thread.sleep(WAIT_PAST_WINDOW_MILLIS)

        val secondCheck = unlocker.checkMasterKeyUsable()
        println("$REPORT_PREFIX second=$secondCheck")
        assertEquals(KeyCheckResult.NOT_AUTHENTICATED, secondCheck)
    }

    private companion object {
        const val TEST_KEY_ALIAS = "hb_master_key_unlock_test"

        /** The 30-second window plus 5 seconds of margin. */
        const val WAIT_PAST_WINDOW_MILLIS = (VAULT_UNLOCK_WINDOW_SECONDS + 5) * 1000L

        /** Marker so the results are easy to find in logcat. */
        const val REPORT_PREFIX = "HB_UNLOCK_PROOF"
    }
}

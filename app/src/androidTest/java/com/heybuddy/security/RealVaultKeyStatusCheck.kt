package com.heybuddy.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only check: reports whether the REAL vault key (hb_master_key) exists on this phone.
 *
 * It never creates or deletes anything and always passes; the answer is printed to logcat
 * with the marker HB_REAL_KEY_PRESENT. Used before manual tests that must run without a vault key.
 */
@RunWith(AndroidJUnit4::class)
class RealVaultKeyStatusCheck {

    @Test
    fun reportWhetherRealKeyExists() {
        val realKeyManager = MasterKeyManager()
        val keyExists = realKeyManager.hasMasterKey()
        println("HB_REAL_KEY_PRESENT=$keyExists")
    }
}

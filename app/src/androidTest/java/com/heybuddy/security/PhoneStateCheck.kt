package com.heybuddy.security

import android.app.KeyguardManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.MainActivity
import org.junit.Assert.assertFalse

/**
 * Fingerprint/PIN window for auth-required TEST keys only. The app's key uses
 * VAULT_UNLOCK_WINDOW_SECONDS (5 minutes); expiry tests use 30 seconds so they finish quickly,
 * and "unlock the phone, then start the test within 10 seconds" still leaves room.
 */
internal const val TEST_AUTH_WINDOW_SECONDS: Int = 30

/** The test window plus 5 seconds of margin for clock and scheduling delays. */
internal const val WAIT_PAST_TEST_WINDOW_MILLIS: Long = (TEST_AUTH_WINDOW_SECONDS + 5) * 1000L

/**
 * Fails with a plain instruction if the phone is locked.
 *
 * All vault keys use setUnlockedDeviceRequired(true), so on a locked phone every key test would
 * fail with a confusing "Keystore operation failed" instead.
 */
internal fun requirePhoneUnlocked() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    assertFalse(
        "The phone is locked. Unlock it, then run the tests again.",
        keyguardManager.isDeviceLocked,
    )
}

/**
 * Runs [block] while the app's screen keeps the display on.
 *
 * Without it the phone may lock during a long wait, and Keystore then refuses with
 * "device locked" instead of "window expired", which proves nothing.
 *
 * DO NOT TAP THE SCREEN while this runs: the app shows its Unlock button, and a real unlock
 * opens a fresh fingerprint/PIN window and spoils the test. (A blank test-only screen is not possible:
 * the test runner can only open screens that belong to the app itself.)
 */
internal fun withScreenKeptOn(block: () -> Unit) {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        block()
    }
}

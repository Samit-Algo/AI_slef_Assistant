package com.heybuddy

import android.graphics.Color
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.security.LockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rules 8 and 9 on the real activity: FLAG_SECURE, tapjacking filter, and locking when the app
 * leaves the screen. Uses the app's own in-memory lock only (no files, no keys are touched).
 */
@RunWith(AndroidJUnit4::class)
class SecureWindowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = (instrumentation.targetContext.applicationContext as HeyBuddyApp).container

    @Test
    fun theWindowIsSecureAndDropsTouchesThroughOverlays() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue("FLAG_SECURE must be set", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(activity.window.decorView.filterTouchesWhenObscured)
            }
        }
    }

    /**
     * With FLAG_SECURE, Android either refuses the screenshot completely (returns nothing) or
     * gives a black picture, depending on the phone. Both mean the app's content cannot be captured.
     */
    @Test
    fun aScreenshotOfTheAppShowsNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // In a long test run the previous screen can still be on top for a moment; a screenshot
            // then shows THAT screen (for example the home-screen wallpaper), which proves nothing.
            waitUntilInFront(scenario)
            Thread.sleep(SETTLE_MILLIS)

            val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@use
            // Sample the middle of the screen (the app's content, away from the system bars).
            val middle = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)

            assertEquals("Expected pure black, got #%06X".format(middle and 0xFFFFFF), Color.BLACK, middle)
        }
    }

    @Test
    fun leavingTheScreenLocksTheVault() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.runOnMainSync { container.vaultLockState.markUnlocked() }

            // The activity is stopped, as when the user goes Home.
            scenario.moveToState(Lifecycle.State.CREATED)

            assertEquals(LockStatus.LOCKED, container.vaultLockState.status.value)
        }
    }

    @Test
    fun ourOwnPickerDoesNotLockTheVault() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.runOnMainSync {
                container.vaultLockState.markUnlocked()
                container.autoLock.issueExternalScreenPass()
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            val whileAway = container.vaultLockState.status.value
            scenario.moveToState(Lifecycle.State.RESUMED)
            val afterReturn = container.vaultLockState.status.value

            assertEquals(LockStatus.UNLOCKED, whileAway)
            assertEquals(LockStatus.UNLOCKED, afterReturn)
            instrumentation.runOnMainSync { container.vaultLockState.lock() }
        }
    }

    private fun waitUntilInFront(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + FRONT_TIMEOUT_MILLIS
        while (true) {
            var hasFocus = false
            scenario.onActivity { activity -> hasFocus = activity.hasWindowFocus() }
            if (hasFocus) return
            check(System.currentTimeMillis() < deadline) { "Hey Buddy never came to the front; keep the phone unlocked and run again" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 800L
        const val FRONT_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}

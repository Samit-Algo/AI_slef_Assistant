package com.heybuddy

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test: launching a picker from MainActivity must not crash.
 *
 * An old androidx.fragment (pulled in by androidx.biometric) rejected the request codes that
 * androidx.activity uses, so both picker buttons closed the app. The intent below goes nowhere on
 * purpose, so no real picker opens: getting "no app for this" (ActivityNotFoundException) proves
 * the launch got past the request-code check that used to crash.
 */
@RunWith(AndroidJUnit4::class)
class PickerLaunchTest {

    @Test
    fun launchingFromMainActivityPassesTheRequestCodeCheck() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var launchError: Throwable? = null
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register(
                    "picker-launch-test",
                    ActivityResultContracts.StartActivityForResult(),
                ) { }
                try {
                    launcher.launch(Intent(NO_APP_HANDLES_THIS))
                } catch (error: Throwable) {
                    launchError = error
                } finally {
                    launcher.unregister()
                }
            }
            assertTrue("Launch failed the wrong way: $launchError", launchError is ActivityNotFoundException)
        }
    }

    private companion object {
        const val NO_APP_HANDLES_THIS = "com.heybuddy.test.ACTION_NOBODY_HANDLES"
    }
}

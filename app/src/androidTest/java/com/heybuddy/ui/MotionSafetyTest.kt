package com.heybuddy.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heybuddy.MainActivity
import com.heybuddy.ui.theme.HeyBuddyTheme
import com.heybuddy.ui.theme.LocalReduceMotion
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Motion must never weaken safety (spec 5.14 motion rules 2 and 3), checked frame by frame on a
 * real phone. Uses the app's own screen hosts with sample text only; the real vault is not used.
 */
@RunWith(AndroidJUnit4::class)
class MotionSafetyTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
    }

    /** Rule 2: when the vault locks, its content is gone in the very next frame, with no fade. */
    @Test
    fun lockingHidesTheVaultInTheSameFrame() {
        var topScreen by mutableStateOf(TopScreen.VAULT)
        scenario.onActivity { activity ->
            activity.setContent {
                HeyBuddyTheme {
                    TopScreenHost(screen = topScreen) { shown ->
                        if (shown == TopScreen.VAULT) Text(SAMPLE_VAULT_TEXT) else Text(SAMPLE_UNLOCK_TEXT)
                    }
                }
            }
        }
        composeRule.onNodeWithText(SAMPLE_VAULT_TEXT).assertExists()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread { topScreen = TopScreen.UNLOCK }
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(SAMPLE_VAULT_TEXT).assertDoesNotExist()
        composeRule.onNodeWithText(SAMPLE_UNLOCK_TEXT).assertExists()
    }

    /** Rule 3: with "Remove animations", moving between vault screens is instant. */
    @Test
    fun removeAnimationsMakesScreenChangesInstant() {
        var vaultScreen by mutableStateOf<VaultScreen>(VaultScreen.List)
        scenario.onActivity { activity ->
            activity.setContent {
                HeyBuddyTheme {
                    CompositionLocalProvider(LocalReduceMotion provides true) {
                        VaultScreenHost(screen = vaultScreen) { shown ->
                            if (shown == VaultScreen.List) Text(SAMPLE_LIST_TEXT) else Text(SAMPLE_SETTINGS_TEXT)
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText(SAMPLE_LIST_TEXT).assertExists()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread { vaultScreen = VaultScreen.Settings }
        // One frame to notice the change, one to jump to the new screen. (Compose's default size
        // spring used to keep the old screen for about 24 frames; it is now left out everywhere.)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText(SAMPLE_LIST_TEXT).assertDoesNotExist()
        composeRule.onNodeWithText(SAMPLE_SETTINGS_TEXT).assertExists()
    }

    private companion object {
        const val SAMPLE_VAULT_TEXT = "Sample vault content"
        const val SAMPLE_UNLOCK_TEXT = "Sample unlock screen"
        const val SAMPLE_LIST_TEXT = "Sample list"
        const val SAMPLE_SETTINGS_TEXT = "Sample settings"
    }
}

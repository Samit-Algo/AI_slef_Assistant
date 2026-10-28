package com.heybuddy.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import com.heybuddy.security.UnlockFailure
import com.heybuddy.ui.theme.MAX_MOVEMENT_MILLIS
import com.heybuddy.ui.theme.MIN_MOVEMENT_MILLIS
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.calmSpring
import com.heybuddy.ui.theme.isHapticAllowed
import com.heybuddy.ui.theme.motionSpec
import com.heybuddy.ui.theme.softOpeningSpring
import com.heybuddy.ui.unlock.isRealUnlockFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approved motion rules of spec 5.14 (Task 1.10m), where they can be checked without a screen. */
class MotionRulesTest {

    @Test
    fun everyMovementTakesBetween150And400Milliseconds() {
        for (movement in MotionDurations.ALL_MOVEMENTS) {
            assertTrue("$movement ms is outside 150–400 ms", movement in MIN_MOVEMENT_MILLIS..MAX_MOVEMENT_MILLIS)
        }
    }

    /** Owner decision A: loops move for at most 400 ms, then rest 1.2 s. */
    @Test
    fun loopsMoveBrieflyThenRest() {
        assertEquals(1_200, MotionDurations.LOOP_REST_MILLIS)
        assertTrue(MotionDurations.SHIMMER_SWEEP_MILLIS <= MAX_MOVEMENT_MILLIS)
        assertTrue(MotionDurations.ICON_NUDGE_MILLIS <= MAX_MOVEMENT_MILLIS)
    }

    @Test
    fun removeAnimationsMakesEveryTimingInstant() {
        assertTrue(motionSpec<Float>(MotionDurations.SCREEN_MILLIS, reduceMotion = true) is SnapSpec)
        assertTrue(calmSpring<Float>(reduceMotion = true) is SnapSpec)
        assertTrue(softOpeningSpring<Float>(reduceMotion = true) is SnapSpec)
        assertFalse(motionSpec<Float>(MotionDurations.SCREEN_MILLIS, reduceMotion = false) is SnapSpec)
    }

    /** Rule 2: leaving the vault (locking) is never animated, with or without "Remove animations". */
    @Test
    fun leavingTheVaultIsAlwaysInstant() {
        val notVault = TopScreen.entries.filter { screen -> screen != TopScreen.VAULT }
        for (target in notVault) {
            for (reduceMotion in listOf(false, true)) {
                val transform = topScreenTransition(target, reduceMotion)
                assertEquals("$target enter", EnterTransition.None, transform.targetContentEnter)
                assertEquals("$target exit", ExitTransition.None, transform.initialContentExit)
                assertEquals("$target size", null, transform.sizeTransform)
            }
        }
    }

    /** Owner decision: cancelling the unlock prompt is a choice, not an error: no shake, no haptic. */
    @Test
    fun onlyRealUnlockFailuresShake() {
        assertFalse(isRealUnlockFailure(UnlockFailure.CANCELLED))
        val realFailures = UnlockFailure.entries.filter { failure -> failure != UnlockFailure.CANCELLED }
        for (failure in realFailures) {
            assertTrue("$failure should shake", isRealUnlockFailure(failure))
        }
    }

    /** Owner decision: touch vibration off in the phone's settings means no haptics from Hey Buddy. */
    @Test
    fun hapticsFollowThePhonesTouchVibrationSetting() {
        assertFalse(isHapticAllowed(touchFeedbackSetting = 0))
        assertTrue(isHapticAllowed(touchFeedbackSetting = 1))
    }
}

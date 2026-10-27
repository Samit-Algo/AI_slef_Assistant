package com.heybuddy.ui.components

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the tapjacking test on a touch's flags. */
class ObscuredTouchTest {

    @Test
    fun aNormalTouchIsAllowed() {
        assertFalse(isTouchObscured(0))
    }

    @Test
    fun aTouchRightUnderAnOverlayIsBlocked() {
        assertTrue(isTouchObscured(MotionEvent.FLAG_WINDOW_IS_OBSCURED))
    }

    @Test
    fun aTouchWhileAnOverlayCoversAnotherPartIsBlocked() {
        assertTrue(isTouchObscured(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
    }

    @Test
    fun otherFlagsDoNotCount() {
        // FLAG_TAINTED is about the event's origin, not about overlays.
        assertFalse(isTouchObscured(TAINTED_FLAG))
    }

    private companion object {
        /** MotionEvent.FLAG_TAINTED (hidden in the SDK): 0x80000000. */
        const val TAINTED_FLAG = Int.MIN_VALUE
    }
}

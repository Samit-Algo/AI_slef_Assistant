package com.heybuddy.ui.components

import android.view.MotionEvent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter

/**
 * Tells whether another app's window covered Hey Buddy when this touch happened (tapjacking).
 *
 * "Obscured" means an overlay was right over the touch; "partially obscured" means an overlay was
 * over some other part of our window, which is enough to trick someone (for example by covering
 * the text next to a button). Sensitive buttons refuse both.
 *
 * @param motionEventFlags MotionEvent.getFlags() of the touch.
 * @return true if any overlay covered our window.
 */
fun isTouchObscured(motionEventFlags: Int): Boolean {
    val overlayFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
    return motionEventFlags and overlayFlags != 0
}

/**
 * Shows the "another app is drawing over Hey Buddy" message. Provided by the app's root screen,
 * so any sensitive button anywhere can report a blocked tap.
 */
val LocalObscuredTouchReporter = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Makes a button ignore touches while another app draws over Hey Buddy (tapjacking protection).
 * Normal touches pass through untouched. Use it on sensitive buttons: Unlock, Save, Rename, Delete.
 *
 * @param onBlocked Called once per blocked tap, to explain why nothing happened.
 * @return The modifier.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(onBlocked: () -> Unit): Modifier {
    return this.pointerInteropFilter { event ->
        if (!isTouchObscured(event.flags)) return@pointerInteropFilter false
        // Consume the whole gesture so the button never sees it; explain once, when the finger lifts.
        if (event.actionMasked == MotionEvent.ACTION_UP) onBlocked()
        true
    }
}

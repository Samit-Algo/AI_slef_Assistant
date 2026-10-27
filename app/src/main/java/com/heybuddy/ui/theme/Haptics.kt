package com.heybuddy.ui.theme

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * A light "confirm" tap, used on successful unlock and save (spec 5.14).
 *
 * Uses the view's built-in haptic feedback, so the app needs no VIBRATE permission, and the phone's
 * own "touch vibration" setting is respected.
 *
 * @param view Any view in the current window (for example LocalView.current).
 */
fun performConfirmHaptic(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}

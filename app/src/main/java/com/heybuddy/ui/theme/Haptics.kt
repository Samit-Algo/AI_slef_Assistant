package com.heybuddy.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * A light "confirm" tap: successful unlock, save, rename and page added (spec 5.14).
 *
 * @param view Any view in the current window (for example LocalView.current).
 */
fun performConfirmHaptic(view: View) {
    performIfTouchVibrationOn(view, HapticFeedbackConstants.CONFIRM)
}

/**
 * A light "reject" tap, only for a REAL unlock failure (never when the user cancels; owner
 * decision, Task 1.10m).
 *
 * @param view Any view in the current window.
 */
fun performRejectHaptic(view: View) {
    performIfTouchVibrationOn(view, HapticFeedbackConstants.REJECT)
}

/**
 * Whether haptics may play for this setting value (owner decision, Task 1.10m: touch vibration
 * off in the phone's settings means no haptics from Hey Buddy).
 *
 * @param touchFeedbackSetting The phone's "touch vibration" value: 0 = off, anything else = on.
 * @return true if a haptic may play.
 */
fun isHapticAllowed(touchFeedbackSetting: Int): Boolean = touchFeedbackSetting != TOUCH_FEEDBACK_OFF

// Uses the view's built-in haptic feedback, so the app needs no VIBRATE permission. Android
// already skips it when touch vibration is off, but we check the setting ourselves too, so the
// owner's rule holds on every phone maker's Android, not only where the system does it.
private fun performIfTouchVibrationOn(view: View, feedback: Int) {
    if (!isTouchVibrationOn(view.context)) return
    view.performHapticFeedback(feedback)
}

@Suppress("DEPRECATION") // Still the setting behind "Touch feedback"; its replacement needs the vibrator service.
private fun isTouchVibrationOn(context: Context): Boolean {
    val value = Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, TOUCH_FEEDBACK_ON)
    return isHapticAllowed(value)
}

private const val TOUCH_FEEDBACK_OFF = 0
private const val TOUCH_FEEDBACK_ON = 1

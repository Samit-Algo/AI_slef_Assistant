package com.heybuddy.ui.unlock

import androidx.annotation.StringRes
import com.heybuddy.R
import com.heybuddy.security.UnlockFailure

/**
 * Picks the message the user sees when an unlock fails.
 *
 * @param failure Why the unlock failed.
 * @return The string resource of the message.
 */
@StringRes
fun messageForFailure(failure: UnlockFailure): Int {
    return when (failure) {
        UnlockFailure.CANCELLED -> R.string.unlock_error_cancelled
        UnlockFailure.TOO_MANY_TRIES -> R.string.unlock_error_too_many_tries
        UnlockFailure.FINGERPRINT_LOCKED_LONG -> R.string.unlock_error_fingerprint_locked
        UnlockFailure.KEY_GONE -> R.string.unlock_error_key_gone
        UnlockFailure.OTHER -> R.string.unlock_error_other
    }
}

/**
 * Whether an unlock failure is a REAL error that earns the gentle shake and the reject haptic
 * (spec 5.14, item 2). Cancelling the prompt is the user's choice, not an error (owner decision,
 * Task 1.10m): it only shows the calm message.
 *
 * @param failure Why the unlock failed.
 * @return true for lockouts, too many tries, an unusable key and other prompt errors.
 */
fun isRealUnlockFailure(failure: UnlockFailure): Boolean {
    return when (failure) {
        UnlockFailure.CANCELLED -> false
        UnlockFailure.TOO_MANY_TRIES -> true
        UnlockFailure.FINGERPRINT_LOCKED_LONG -> true
        UnlockFailure.KEY_GONE -> true
        UnlockFailure.OTHER -> true
    }
}

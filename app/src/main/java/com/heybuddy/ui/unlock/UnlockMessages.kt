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
        UnlockFailure.KEY_NOT_USABLE -> R.string.unlock_error_key_not_usable
        UnlockFailure.OTHER -> R.string.unlock_error_other
    }
}

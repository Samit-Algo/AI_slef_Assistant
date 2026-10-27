package com.heybuddy.ui.unlock

import com.heybuddy.R
import com.heybuddy.security.UnlockFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks that each failure shows its own message (point 4 of the Task 1.3 plan). */
class UnlockMessagesTest {

    @Test
    fun eachFailureHasItsMessage() {
        assertEquals(R.string.unlock_error_cancelled, messageForFailure(UnlockFailure.CANCELLED))
        assertEquals(R.string.unlock_error_too_many_tries, messageForFailure(UnlockFailure.TOO_MANY_TRIES))
        assertEquals(R.string.unlock_error_fingerprint_locked, messageForFailure(UnlockFailure.FINGERPRINT_LOCKED_LONG))
        assertEquals(R.string.unlock_error_key_gone, messageForFailure(UnlockFailure.KEY_GONE))
        assertEquals(R.string.unlock_error_other, messageForFailure(UnlockFailure.OTHER))
    }
}

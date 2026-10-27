package com.heybuddy.security

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks that each BiometricPrompt error becomes the right reason. */
class PromptErrorMappingTest {

    @Test
    fun cancelErrorsMeanCancelled() {
        assertEquals(UnlockFailure.CANCELLED, failureForPromptError(BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(UnlockFailure.CANCELLED, failureForPromptError(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertEquals(UnlockFailure.CANCELLED, failureForPromptError(BiometricPrompt.ERROR_CANCELED))
    }

    @Test
    fun shortLockoutMeansTooManyTries() {
        assertEquals(UnlockFailure.TOO_MANY_TRIES, failureForPromptError(BiometricPrompt.ERROR_LOCKOUT))
    }

    @Test
    fun permanentLockoutMeansFingerprintLockedLong() {
        val reason = failureForPromptError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT)

        assertEquals(UnlockFailure.FINGERPRINT_LOCKED_LONG, reason)
    }

    @Test
    fun otherErrorsMeanOther() {
        assertEquals(UnlockFailure.OTHER, failureForPromptError(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertEquals(UnlockFailure.OTHER, failureForPromptError(BiometricPrompt.ERROR_TIMEOUT))
    }
}

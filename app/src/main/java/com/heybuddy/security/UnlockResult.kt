package com.heybuddy.security

import androidx.biometric.BiometricPrompt

/** Why an unlock attempt did not open the vault. */
enum class UnlockFailure {
    /** The user closed the prompt, or Android closed it (for example, the app went to the background). */
    CANCELLED,

    /** Too many wrong fingerprints; fingerprint is paused for a short time. */
    TOO_MANY_TRIES,

    /** Too many wrong fingerprints over time; fingerprint stays off until the phone is unlocked with PIN. */
    FINGERPRINT_LOCKED_LONG,

    /**
     * CERTAIN: the master key is gone for good (rule 14), so the recovery screen is shown.
     * Only set on proof (see isProofKeyIsGone), never on a guess.
     */
    KEY_GONE,

    /**
     * Anything else: Keystore busy, phone locked, timeout, an unknown error, or the key not usable
     * right now. Shows the normal "Unlock didn't work. Please try again." and NEVER offers deletion.
     */
    OTHER,
}

/** The outcome of one unlock attempt. */
sealed interface UnlockResult {
    /** The unlock was proven with the master key and the vault is now open. */
    data object Success : UnlockResult

    /**
     * The vault stays locked.
     *
     * @property reason Why it failed, used to pick the message for the user.
     */
    data class Failed(val reason: UnlockFailure) : UnlockResult
}

/**
 * Turns a BiometricPrompt error code into the reason we show to the user.
 *
 * @param errorCode One of the `BiometricPrompt.ERROR_*` codes.
 * @return The matching [UnlockFailure].
 */
fun failureForPromptError(errorCode: Int): UnlockFailure {
    return when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> UnlockFailure.CANCELLED

        BiometricPrompt.ERROR_LOCKOUT -> UnlockFailure.TOO_MANY_TRIES
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> UnlockFailure.FINGERPRINT_LOCKED_LONG
        else -> UnlockFailure.OTHER
    }
}

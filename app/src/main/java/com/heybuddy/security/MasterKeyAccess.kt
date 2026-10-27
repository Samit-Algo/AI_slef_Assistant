package com.heybuddy.security

import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.StringRes
import com.heybuddy.R
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog

/** Asks the user to confirm with fingerprint/PIN again. Lets tests use a fake prompt. */
interface ReAuthPrompt {

    /**
     * Shows the prompt.
     *
     * @param onConfirmed Called once if the user confirmed.
     * @param onDeclined Called once if the user cancelled or the prompt failed.
     */
    fun ask(onConfirmed: () -> Unit, onDeclined: () -> Unit)
}

/** The outcome of an action that needed the master key. */
sealed interface KeyAccessResult<out T> {

    /**
     * The action worked.
     *
     * @property value What the action returned.
     */
    data class Success<T>(val value: T) : KeyAccessResult<T>

    /** The user did not confirm (or confirming did not help). Nothing broke; they can simply try again. */
    data object NeedsConfirmation : KeyAccessResult<Nothing> {

        /** The calm message to show for this result. */
        @StringRes
        val message: Int = R.string.key_access_confirm_again
    }

    /**
     * The action failed for a reason other than the unlock window, for example damaged data.
     *
     * @property error What went wrong. Never shown to the user or logged as text.
     */
    data class Failed(val error: Throwable) : KeyAccessResult<Nothing>
}

/**
 * Runs actions that need the master key, and handles the expired 30-second window calmly.
 *
 * If the window has passed, it shows the fingerprint/PIN prompt again and retries the action
 * ONCE. It never crashes and never retries more than once.
 *
 * @param prompt Shows the fingerprint/PIN prompt.
 */
class MasterKeyAccess(private val prompt: ReAuthPrompt) {

    /**
     * Runs [action], asking for fingerprint/PIN and retrying once if the window has expired.
     *
     * @param action The work that uses the master key. Runs on the calling thread, and the retry
     *   runs on the prompt's callback thread (the main thread in the app).
     * @param onResult Called exactly once with the outcome.
     */
    fun <T> run(action: () -> T, onResult: (KeyAccessResult<T>) -> Unit) {
        val firstAttempt = attempt(action)
        if (firstAttempt != null) {
            onResult(firstAttempt)
            return
        }
        SafeLog.info(LogEvent.KEY_REAUTH_REQUESTED)
        prompt.ask(
            onConfirmed = { retryOnce(action, onResult) },
            onDeclined = {
                SafeLog.info(LogEvent.KEY_REAUTH_DECLINED)
                onResult(KeyAccessResult.NeedsConfirmation)
            },
        )
    }

    private fun <T> retryOnce(action: () -> T, onResult: (KeyAccessResult<T>) -> Unit) {
        val secondAttempt = attempt(action)
        if (secondAttempt != null) {
            onResult(secondAttempt)
            return
        }
        // Still not authenticated right after confirming: stop here instead of looping.
        SafeLog.info(LogEvent.KEY_REAUTH_DECLINED)
        onResult(KeyAccessResult.NeedsConfirmation)
    }

    /** Runs the action once. Returns null only when the unlock window has expired. */
    private fun <T> attempt(action: () -> T): KeyAccessResult<T>? {
        return try {
            val value = action()
            KeyAccessResult.Success(value)
        } catch (error: Exception) {
            if (isMissingUserAuthentication(error)) null else KeyAccessResult.Failed(error)
        }
    }
}

/** Longest cause chain we follow, so a broken chain that loops back on itself cannot hang us. */
private const val MAX_CAUSE_DEPTH = 16

/**
 * Tells whether an error means "no fingerprint/PIN in the last 30 seconds".
 *
 * Checks the whole cause chain, because libraries such as Tink may wrap the Keystore's
 * UserNotAuthenticatedException inside their own GeneralSecurityException.
 *
 * @param error Any error thrown by a master key action.
 * @return true if a UserNotAuthenticatedException is anywhere in the chain.
 */
fun isMissingUserAuthentication(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is UserNotAuthenticatedException) return true
        current = current.cause
        depth++
    }
    return false
}

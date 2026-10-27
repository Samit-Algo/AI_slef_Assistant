package com.heybuddy.settings

import com.heybuddy.security.ReAuthPrompt
import com.heybuddy.util.DELETE_EVERYTHING_CONFIRMATION_WORD
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Tells whether the user typed the confirmation word exactly ("DELETE": capitals, no spaces).
 * Strict on purpose: the word is a deliberate pause before an action that cannot be undone.
 *
 * @param typed What is in the text field.
 * @return true only for an exact match.
 */
fun isDeleteEverythingConfirmed(typed: String): Boolean = typed == DELETE_EVERYTHING_CONFIRMATION_WORD

/** The outcome of a "Delete everything" attempt. */
enum class DeleteEverythingResult {
    /** Everything was deleted. */
    ERASED,

    /** The user cancelled (or failed) "Confirm it's you"; nothing was deleted. */
    NOT_CONFIRMED,
}

/**
 * "Delete everything" after the typed "DELETE": ALWAYS asks "Confirm it's you" first, even inside
 * the key's unlock window, so a phone left unlocked on a table cannot be wiped by someone else.
 * Only after a confirmed fingerprint/PIN does it erase.
 *
 * @param erase Deletes the whole vault (VaultEraser in the app; a fake in tests).
 */
class DeleteEverythingFlow(private val erase: suspend () -> Unit) {

    /**
     * Asks for fingerprint/PIN, then erases if confirmed.
     *
     * @param prompt Shows "Confirm it's you".
     * @return What happened.
     */
    suspend fun run(prompt: ReAuthPrompt): DeleteEverythingResult {
        val confirmed = askToConfirm(prompt)
        if (!confirmed) {
            SafeLog.info(LogEvent.DELETE_EVERYTHING_CANCELLED)
            return DeleteEverythingResult.NOT_CONFIRMED
        }
        SafeLog.info(LogEvent.DELETE_EVERYTHING_CONFIRMED)
        erase()
        return DeleteEverythingResult.ERASED
    }

    private suspend fun askToConfirm(prompt: ReAuthPrompt): Boolean {
        return suspendCancellableCoroutine { continuation ->
            prompt.ask(
                onConfirmed = { if (continuation.isActive) continuation.resume(true) },
                onDeclined = { if (continuation.isActive) continuation.resume(false) },
            )
        }
    }
}

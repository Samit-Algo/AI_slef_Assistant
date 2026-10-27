package com.heybuddy.security

import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Checks the calm re-ask in [MasterKeyAccess] with a fake prompt (no real key, no timing).
 * Runs on the phone because UserNotAuthenticatedException is a real Android class.
 */
@RunWith(AndroidJUnit4::class)
class MasterKeyAccessTest {

    /** A prompt that answers at once, and counts how often it was shown. */
    private class FakePrompt(private val userConfirms: Boolean) : ReAuthPrompt {
        var timesShown = 0
            private set

        override fun ask(onConfirmed: () -> Unit, onDeclined: () -> Unit) {
            timesShown++
            if (userConfirms) onConfirmed() else onDeclined()
        }
    }

    /** An action that fails with the given errors in turn, then succeeds. Counts its runs. */
    private class ScriptedAction(private val errorsToThrow: List<Exception>) : () -> String {
        var timesRun = 0
            private set

        override fun invoke(): String {
            val error = errorsToThrow.getOrNull(timesRun)
            timesRun++
            if (error != null) throw error
            return SUCCESS_VALUE
        }
    }

    @Test
    fun worksFirstTimeWithoutAPrompt() {
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(errorsToThrow = emptyList())

        val result = runAndCapture(prompt, action)

        assertEquals(KeyAccessResult.Success(SUCCESS_VALUE), result)
        assertEquals(0, prompt.timesShown)
        assertEquals(1, action.timesRun)
    }

    @Test
    fun expiredWindowAsksAgainAndRetriesOnce() {
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(errorsToThrow = listOf(UserNotAuthenticatedException()))

        val result = runAndCapture(prompt, action)

        assertEquals(KeyAccessResult.Success(SUCCESS_VALUE), result)
        assertEquals(1, prompt.timesShown)
        assertEquals(2, action.timesRun)
    }

    @Test
    fun expiredWindowWrappedByALibraryIsStillRecognised() {
        val wrapped = GeneralSecurityException("library error", UserNotAuthenticatedException())
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(errorsToThrow = listOf(wrapped))

        val result = runAndCapture(prompt, action)

        assertEquals(KeyAccessResult.Success(SUCCESS_VALUE), result)
        assertEquals(1, prompt.timesShown)
    }

    @Test
    fun userCancelsSoNoRetryAndACalmResult() {
        val prompt = FakePrompt(userConfirms = false)
        val action = ScriptedAction(errorsToThrow = listOf(UserNotAuthenticatedException()))

        val result = runAndCapture(prompt, action)

        assertEquals(KeyAccessResult.NeedsConfirmation, result)
        assertEquals(1, action.timesRun)
    }

    @Test
    fun refusedAgainAfterConfirmingSoNoThirdTry() {
        val twoRefusals = listOf(UserNotAuthenticatedException(), UserNotAuthenticatedException())
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(errorsToThrow = twoRefusals)

        val result = runAndCapture(prompt, action)

        assertEquals(KeyAccessResult.NeedsConfirmation, result)
        assertEquals(1, prompt.timesShown)
        assertEquals(2, action.timesRun)
    }

    @Test
    fun otherErrorsAreReportedWithoutAPrompt() {
        val damagedData = IOException("damaged")
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(errorsToThrow = listOf(damagedData))

        val result = runAndCapture(prompt, action)

        assertTrue(result is KeyAccessResult.Failed)
        assertEquals(damagedData, (result as KeyAccessResult.Failed).error)
        assertEquals(0, prompt.timesShown)
    }

    private fun runAndCapture(prompt: ReAuthPrompt, action: ScriptedAction): KeyAccessResult<String> {
        val access = MasterKeyAccess(prompt)
        val results = mutableListOf<KeyAccessResult<String>>()
        access.run(action) { result -> results.add(result) }
        // onResult must be called exactly once.
        assertEquals(1, results.size)
        return results.single()
    }

    private companion object {
        const val SUCCESS_VALUE = "done"
    }
}

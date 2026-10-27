package com.heybuddy.security

import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.GeneralSecurityException

/** Checks [MasterKeyAccess.runSuspending]: same rules as [MasterKeyAccess.run], for coroutines. */
@RunWith(AndroidJUnit4::class)
class MasterKeyAccessSuspendTest {

    /** A prompt that answers at once, and counts how often it was shown. */
    private class FakePrompt(private val userConfirms: Boolean) : ReAuthPrompt {
        var timesShown = 0
            private set

        override fun ask(onConfirmed: () -> Unit, onDeclined: () -> Unit) {
            timesShown++
            if (userConfirms) onConfirmed() else onDeclined()
        }
    }

    /** Fails with the given errors in turn, then succeeds. Counts its runs. */
    private class ScriptedAction(private val errorsToThrow: List<Exception>) {
        var timesRun = 0
            private set

        suspend fun invoke(): String {
            val error = errorsToThrow.getOrNull(timesRun)
            timesRun++
            if (error != null) throw error
            return SUCCESS_VALUE
        }
    }

    @Test
    fun worksFirstTimeWithoutAPrompt() = runBlocking {
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(emptyList())

        val result = MasterKeyAccess(prompt).runSuspending { action.invoke() }

        assertEquals(KeyAccessResult.Success(SUCCESS_VALUE), result)
        assertEquals(0, prompt.timesShown)
    }

    @Test
    fun expiredWindowAsksAgainAndRetriesOnce() = runBlocking {
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(listOf(UserNotAuthenticatedException()))

        val result = MasterKeyAccess(prompt).runSuspending { action.invoke() }

        assertEquals(KeyAccessResult.Success(SUCCESS_VALUE), result)
        assertEquals(1, prompt.timesShown)
        assertEquals(2, action.timesRun)
    }

    @Test
    fun wrappedExpiredWindowIsRecognised() = runBlocking {
        val wrapped = GeneralSecurityException("library error", UserNotAuthenticatedException())
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(listOf(wrapped))

        val result = MasterKeyAccess(prompt).runSuspending { action.invoke() }

        assertEquals(KeyAccessResult.Success(SUCCESS_VALUE), result)
    }

    @Test
    fun userCancelsSoNoRetry() = runBlocking {
        val prompt = FakePrompt(userConfirms = false)
        val action = ScriptedAction(listOf(UserNotAuthenticatedException()))

        val result = MasterKeyAccess(prompt).runSuspending { action.invoke() }

        assertEquals(KeyAccessResult.NeedsConfirmation, result)
        assertEquals(1, action.timesRun)
    }

    @Test
    fun refusedAgainAfterConfirmingSoNoThirdTry() = runBlocking {
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(listOf(UserNotAuthenticatedException(), UserNotAuthenticatedException()))

        val result = MasterKeyAccess(prompt).runSuspending { action.invoke() }

        assertEquals(KeyAccessResult.NeedsConfirmation, result)
        assertEquals(2, action.timesRun)
    }

    @Test
    fun otherErrorsAreReportedWithoutAPrompt() = runBlocking {
        val prompt = FakePrompt(userConfirms = true)
        val action = ScriptedAction(listOf(IOException("damaged")))

        val result = MasterKeyAccess(prompt).runSuspending { action.invoke() }

        assertTrue(result is KeyAccessResult.Failed)
        assertEquals(0, prompt.timesShown)
    }

    private companion object {
        const val SUCCESS_VALUE = "done"
    }
}

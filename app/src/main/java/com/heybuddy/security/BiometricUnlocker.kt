package com.heybuddy.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import java.security.InvalidKeyException
import javax.crypto.Cipher

/** Result of trying the master key once. */
enum class KeyCheckResult {
    /** The key worked: secure hardware accepted a recent fingerprint/PIN. */
    USABLE,

    /** Not usable right now: no fingerprint/PIN within the key window, phone locked, Keystore busy. */
    NOT_AUTHENTICATED,

    /** CERTAIN: the key is gone for good (deleted, or permanently invalidated; rule 14). */
    GONE,
}

/**
 * Shows the fingerprint/PIN prompt and opens the vault after a proven unlock.
 *
 * Allows the same unlock methods as the master key (rule 2): strong biometrics or the
 * phone's PIN, pattern or password.
 *
 * @param masterKeyManager Gives access to the master key used to prove the unlock.
 * @param vaultLockState The app's lock, opened only after the proof succeeds. A prompt still open
 *   when the vault locks is cancelled, so it can never open anything afterwards.
 * @param healthMonitor Told about CERTAIN proof that the key is gone (rule 14). Null in tests.
 * @param onBeforeSystemScreen Called right before a prompt is shown, so auto-lock knows the app
 *   is not really being left (rule 9).
 */
class BiometricUnlocker(
    private val masterKeyManager: MasterKeyManager,
    private val vaultLockState: VaultLockState,
    private val healthMonitor: VaultHealthMonitor? = null,
    private val onBeforeSystemScreen: () -> Unit = {},
) {

    // The prompt on screen right now, if any. Only touched on the main thread.
    private var activePrompt: BiometricPrompt? = null

    init {
        vaultLockState.addOnLockListener { cancelActivePrompt() }
    }

    /**
     * Tells whether a strong fingerprint/face is set up. If not, the prompt still offers the PIN.
     *
     * @param context Any context.
     * @return true if strong biometrics are ready to use.
     */
    fun isFingerprintSetUp(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val status = biometricManager.canAuthenticate(BIOMETRIC_STRONG)
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Asks for fingerprint or PIN, proves it with the master key, then opens the vault.
     *
     * @param activity The activity that shows the prompt.
     * @param title Prompt title shown to the user.
     * @param subtitle Prompt subtitle shown to the user.
     * @param onResult Called once with the outcome, on the main thread.
     */
    fun unlock(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (UnlockResult) -> Unit,
    ) {
        // The key is created BEFORE the prompt (on first use only), so the fingerprint/PIN the
        // user gives applies to a key that already exists, and a key problem shows up before we
        // ask the user for anything.
        val keyProblem = ensureMasterKeyExists()
        if (keyProblem != null) {
            onResult(UnlockResult.Failed(keyProblem))
            return
        }
        val callback = createUnlockCallback(onResult)
        showPrompt(activity, title, subtitle, callback)
    }

    /**
     * Asks the user to confirm with fingerprint/PIN again, without changing the vault lock.
     * Used by [MasterKeyAccess] when the fingerprint/PIN window has expired.
     *
     * @param activity The activity that shows the prompt.
     * @param title Prompt title shown to the user.
     * @param subtitle Prompt subtitle shown to the user.
     * @param onConfirmed Called once if the user confirmed.
     * @param onDeclined Called once if the user cancelled or the prompt failed.
     */
    fun confirmIdentity(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onConfirmed: () -> Unit,
        onDeclined: () -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                activePrompt = null
                onConfirmed()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                activePrompt = null
                val reason = failureForPromptError(errorCode)
                SafeLog.info(logEventForFailure(reason))
                onDeclined()
            }
        }
        showPrompt(activity, title, subtitle, callback)
    }

    /**
     * Uses the master key once by encrypting a small fixed value.
     *
     * @return Whether the key can be used right now, or is CERTAINLY gone.
     */
    fun checkMasterKeyUsable(): KeyCheckResult {
        return try {
            val key = masterKeyManager.getOrCreateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            // No IV passed: Keystore generates it (randomized encryption required, rule 2).
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.doFinal(PROOF_VALUE)
            KeyCheckResult.USABLE
        } catch (notAuthenticated: UserNotAuthenticatedException) {
            KeyCheckResult.NOT_AUTHENTICATED
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            healthMonitor?.reportKeyError(invalidated)
            KeyCheckResult.GONE
        } catch (missing: MasterKeyGoneException) {
            healthMonitor?.reportKeyError(missing)
            KeyCheckResult.GONE
        } catch (otherRefusal: InvalidKeyException) {
            // For example "device is locked" (setUnlockedDeviceRequired): not usable right now.
            KeyCheckResult.NOT_AUTHENTICATED
        } catch (unsure: Exception) {
            // Keystore busy, a timeout, anything unexpected: NOT proof that the key is gone.
            SafeLog.error(LogEvent.UNLOCK_PROOF_FAILED, unsure)
            KeyCheckResult.NOT_AUTHENTICATED
        }
    }

    /** @return null if the key is ready, otherwise why it is not (KEY_GONE only on proof). */
    private fun ensureMasterKeyExists(): UnlockFailure? {
        return try {
            masterKeyManager.getOrCreateMasterKey()
            null
        } catch (problem: Exception) {
            SafeLog.error(LogEvent.MASTER_KEY_CREATE_FAILED, problem)
            healthMonitor?.reportKeyError(problem)
            failureForKeyError(problem)
        }
    }

    // The callback must not hold the activity: the prompt can outlive it (for example on rotation).
    private fun showPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        callback: BiometricPrompt.AuthenticationCallback,
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, callback)
        onBeforeSystemScreen()
        activePrompt = prompt
        prompt.authenticate(promptInfo)
    }

    private fun cancelActivePrompt() {
        val prompt = activePrompt ?: return
        activePrompt = null
        prompt.cancelAuthentication()
    }

    private fun createUnlockCallback(onResult: (UnlockResult) -> Unit): BiometricPrompt.AuthenticationCallback {
        return object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                activePrompt = null
                val unlockResult = finishUnlockAfterPrompt()
                onResult(unlockResult)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                activePrompt = null
                val reason = failureForPromptError(errorCode)
                // errString comes from the system and is never logged; only our fixed event is.
                SafeLog.info(logEventForFailure(reason))
                onResult(UnlockResult.Failed(reason))
            }

            // onAuthenticationFailed (one wrong finger) is not handled: the prompt shows
            // "Not recognised" itself and lets the user try again.
        }
    }

    private fun logEventForFailure(reason: UnlockFailure): LogEvent {
        return when (reason) {
            UnlockFailure.CANCELLED -> LogEvent.UNLOCK_CANCELLED
            UnlockFailure.TOO_MANY_TRIES,
            UnlockFailure.FINGERPRINT_LOCKED_LONG,
            -> LogEvent.UNLOCK_FINGERPRINT_LOCKOUT
            UnlockFailure.KEY_GONE -> LogEvent.MASTER_KEY_GONE
            UnlockFailure.OTHER -> LogEvent.UNLOCK_PROMPT_ERROR_OTHER
        }
    }

    private fun finishUnlockAfterPrompt(): UnlockResult {
        // Do not trust the "success" callback alone. It is only a message inside our own app
        // process, which a tampered phone could fake. Using the master key proves that the
        // secure hardware itself accepted the fingerprint/PIN.
        val keyCheck = checkMasterKeyUsable()
        if (keyCheck == KeyCheckResult.GONE) {
            SafeLog.info(LogEvent.MASTER_KEY_GONE)
            return UnlockResult.Failed(UnlockFailure.KEY_GONE)
        }
        if (keyCheck != KeyCheckResult.USABLE) {
            SafeLog.info(LogEvent.UNLOCK_PROOF_FAILED)
            return UnlockResult.Failed(UnlockFailure.OTHER)
        }
        vaultLockState.markUnlocked()
        SafeLog.info(LogEvent.VAULT_UNLOCKED)
        return UnlockResult.Success
    }

    private companion object {
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        /** Fixed, meaningless bytes encrypted only to prove the key works. The result is thrown away. */
        val PROOF_VALUE: ByteArray = "hey-buddy-unlock-check".toByteArray(Charsets.UTF_8)
    }
}

/**
 * Turns a master key error into an unlock failure. KEY_GONE ONLY for certain proof (rule 14);
 * every other error is OTHER ("Unlock didn't work. Please try again."), never an offer to delete.
 *
 * @param error The error from creating or using the master key.
 * @return The failure to show.
 */
fun failureForKeyError(error: Throwable): UnlockFailure {
    return if (isProofKeyIsGone(error)) UnlockFailure.KEY_GONE else UnlockFailure.OTHER
}

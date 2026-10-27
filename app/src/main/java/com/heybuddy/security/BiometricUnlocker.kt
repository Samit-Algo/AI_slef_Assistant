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
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import javax.crypto.Cipher

/** Result of trying the master key once. */
enum class KeyCheckResult {
    /** The key worked: secure hardware accepted a recent fingerprint/PIN. */
    USABLE,

    /** No fingerprint/PIN in the last 30 seconds (or the phone is locked). */
    NOT_AUTHENTICATED,

    /** Android destroyed the key, usually because the screen lock was removed (rule 14, Task 1.9). */
    INVALIDATED,
}

/**
 * Shows the fingerprint/PIN prompt and opens the vault after a proven unlock.
 *
 * Allows the same unlock methods as the master key (rule 2): strong biometrics or the
 * phone's PIN, pattern or password.
 *
 * @param masterKeyManager Gives access to the master key used to prove the unlock.
 * @param vaultLockState The app's lock, opened only after the proof succeeds.
 */
class BiometricUnlocker(
    private val masterKeyManager: MasterKeyManager,
    private val vaultLockState: VaultLockState,
) {

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
        // The key is created BEFORE the prompt, so the fingerprint/PIN the user gives applies
        // to a key that already exists, and a key creation problem shows up before we ask
        // the user for anything.
        val keyReady = ensureMasterKeyExists()
        if (!keyReady) {
            onResult(UnlockResult.Failed(UnlockFailure.KEY_NOT_USABLE))
            return
        }
        showPrompt(activity, title, subtitle, onResult)
    }

    /**
     * Uses the master key once by encrypting a small fixed value.
     *
     * @return Whether the key can be used right now.
     */
    fun checkMasterKeyUsable(): KeyCheckResult {
        val key = masterKeyManager.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        return try {
            // No IV passed: Keystore generates it (randomized encryption required, rule 2).
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.doFinal(PROOF_VALUE)
            KeyCheckResult.USABLE
        } catch (notAuthenticated: UserNotAuthenticatedException) {
            KeyCheckResult.NOT_AUTHENTICATED
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            KeyCheckResult.INVALIDATED
        } catch (otherRefusal: InvalidKeyException) {
            // For example "device is locked" (setUnlockedDeviceRequired): not usable right now.
            KeyCheckResult.NOT_AUTHENTICATED
        }
    }

    private fun ensureMasterKeyExists(): Boolean {
        return try {
            masterKeyManager.getOrCreateMasterKey()
            true
        } catch (creationFailed: GeneralSecurityException) {
            SafeLog.error(LogEvent.MASTER_KEY_CREATE_FAILED, creationFailed)
            false
        } catch (noScreenLock: IllegalStateException) {
            // Keystore throws this when the phone has no secure screen lock.
            SafeLog.error(LogEvent.MASTER_KEY_CREATE_FAILED, noScreenLock)
            false
        }
    }

    private fun showPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (UnlockResult) -> Unit,
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        val executor = ContextCompat.getMainExecutor(activity)
        // The callback must not hold the activity: the prompt can outlive it (for example on rotation).
        val callback = createPromptCallback(onResult)
        val prompt = BiometricPrompt(activity, executor, callback)
        prompt.authenticate(promptInfo)
    }

    private fun createPromptCallback(onResult: (UnlockResult) -> Unit): BiometricPrompt.AuthenticationCallback {
        return object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val unlockResult = finishUnlockAfterPrompt()
                onResult(unlockResult)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
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
            UnlockFailure.KEY_NOT_USABLE,
            UnlockFailure.OTHER,
            -> LogEvent.UNLOCK_PROMPT_ERROR_OTHER
        }
    }

    private fun finishUnlockAfterPrompt(): UnlockResult {
        // Do not trust the "success" callback alone. It is only a message inside our own app
        // process, which a tampered phone could fake. Using the master key proves that the
        // secure hardware itself accepted the fingerprint/PIN.
        val keyCheck = checkMasterKeyUsable()
        if (keyCheck != KeyCheckResult.USABLE) {
            // TODO(Task 1.9): INVALIDATED gets its own recovery screen (rule 14).
            SafeLog.info(LogEvent.UNLOCK_PROOF_FAILED)
            return UnlockResult.Failed(UnlockFailure.KEY_NOT_USABLE)
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

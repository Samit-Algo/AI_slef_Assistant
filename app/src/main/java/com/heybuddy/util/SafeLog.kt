package com.heybuddy.util

import android.util.Log
import com.heybuddy.BuildConfig

/**
 * The only messages the app is allowed to log (rule 12).
 *
 * Callers pick from this fixed list instead of passing free text, so a file name,
 * document name or document content can never end up in the log by mistake.
 *
 * @property message The fixed text written to the log.
 */
enum class LogEvent(val message: String) {
    APP_STARTED("App started"),
    MASTER_KEY_CREATED_STRONGBOX("Master key created in StrongBox"),
    MASTER_KEY_CREATED_TEE("StrongBox not available; master key created in normal secure hardware"),
    MASTER_KEY_DELETED("Master key deleted"),
    MASTER_KEY_CREATE_FAILED("Master key could not be created"),
    UNLOCK_CANCELLED("Unlock cancelled"),
    UNLOCK_FINGERPRINT_LOCKOUT("Fingerprint lockout"),
    UNLOCK_PROMPT_ERROR_OTHER("Unlock prompt error (other)"),
    UNLOCK_PROOF_FAILED("Unlock prompt succeeded but the master key was not usable"),
    VAULT_UNLOCKED("Vault unlocked"),
    DB_PASSPHRASE_CREATED("Database passphrase created and saved in wrapped form"),
    DB_PASSPHRASE_CLEARED("Database passphrase wiped from memory"),
    KEY_REAUTH_REQUESTED("Unlock window expired; asking for fingerprint/PIN again"),
    KEY_REAUTH_DECLINED("Fingerprint/PIN not confirmed; key action not retried"),
    DB_PASSPHRASE_WARM_FAILED("Could not prepare the database passphrase after unlock"),
    DATABASE_OPENED("Vault database opened"),
    DATABASE_CLOSED("Vault database closed"),
    DOCUMENT_SAVED("Document saved"),
    DOCUMENT_SAVE_ROLLED_BACK("Document save failed; its encrypted file was removed"),
    DOCUMENT_RENAMED("Document renamed"),
    DOCUMENT_DELETED("Document deleted"),
}

/**
 * Logging wrapper that never logs personal data (rule 12).
 *
 * It writes only in debug builds, and only messages from [LogEvent].
 */
object SafeLog {

    private const val TAG = "HeyBuddy"

    /**
     * Logs a normal event, for example a step finishing.
     *
     * @param event The fixed event to log.
     */
    fun info(event: LogEvent) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, event.message)
    }

    /**
     * Logs a failure.
     *
     * Only the error's class name is logged, never its message: exception messages
     * often contain file paths or other personal data.
     *
     * @param event The fixed event to log.
     * @param error The error that caused it, if any.
     */
    fun error(event: LogEvent, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val errorType = error?.javaClass?.simpleName ?: "none"
        Log.e(TAG, "${event.message} (error type: $errorType)")
    }
}

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
    DOCUMENT_SAVE_ROLLED_BACK("Document save failed; its pages were put back as not saved"),
    PAGES_ADDED("Pages added to a document"),
    PAGE_REMOVED("Page removed from a document"),
    PAGE_READ("Page read on the phone (line count only)"),
    READING_RESULTS_CLEARED("Reading results cleared"),
    DOCUMENT_RENAMED("Document renamed"),
    DOCUMENT_DELETED("Document deleted"),
    MASTER_KEY_GONE("Master key is gone for good; showing the recovery screen"),
    AUTO_LOCK_BACKGROUND("Vault locked: app left the screen"),
    AUTO_LOCK_PASS_EXPIRED("Vault locked: away from the app longer than the 2-minute pass"),
    AUTO_LOCK_SCREEN_OFF("Vault locked: screen turned off"),
    AUTO_LOCK_IDLE("Vault locked: no touch for the idle time"),
    VAULT_ERASED("Vault erased: key, database and files deleted"),
    TOUCH_BLOCKED_OVERLAY("Tap ignored: another app is drawing over Hey Buddy"),
    ORPHAN_FILES_REMOVED("Orphan encrypted files removed"),
    ORPHAN_CLEANUP_FAILED("Orphan file cleanup could not run"),
    DELETE_EVERYTHING_CONFIRMED("Delete everything confirmed with fingerprint/PIN"),
    DELETE_EVERYTHING_CANCELLED("Delete everything cancelled at fingerprint/PIN"),
    ONBOARDING_FINISHED("Onboarding finished"),
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
     * Logs a fixed event with a count, for example how many orphan files were removed.
     * A number never identifies a person or a document, so it is safe to log.
     *
     * @param event The fixed event to log.
     * @param count The number to append.
     */
    fun info(event: LogEvent, count: Int) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "${event.message}: $count")
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

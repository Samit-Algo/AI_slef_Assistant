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

package com.heybuddy.security

import com.heybuddy.util.EXTERNAL_SCREEN_PASS_SECONDS
import com.heybuddy.util.IDLE_LOCK_SECONDS

/** What the app should do after a lifecycle or screen event. */
enum class LockDecision {
    /** Lock the vault now. */
    LOCK_NOW,

    /** Keep the vault open (for example our own picker is in front, within its time limit). */
    STAY_UNLOCKED,
}

private const val MILLIS_PER_SECOND = 1000L

/**
 * Decides when leaving the app locks the vault (rule 9). Pure logic, so it can be tested with a
 * fake clock; the activity feeds it events.
 *
 * The exception for our own system screens relies only on our OWN intent: right before our code
 * opens a picker or a fingerprint/PIN prompt, it calls [issueExternalScreenPass]. The app never
 * tries to guess which app is on top. The pass is used up by the next trip to the background,
 * is cancelled when the app is back in front, and is valid for at most
 * [EXTERNAL_SCREEN_PASS_SECONDS], so it can never keep the vault open for long.
 *
 * @param clock Milliseconds that only go forward (SystemClock.elapsedRealtime in the app), so
 *   changing the phone's date or time cannot stretch the limit.
 */
class BackgroundLockPolicy(private val clock: () -> Long) {

    private val passLimitMillis = EXTERNAL_SCREEN_PASS_SECONDS * MILLIS_PER_SECOND

    // When the current pass was issued, or null if there is none.
    private var passIssuedAt: Long? = null

    // True while the app is in the background thanks to a pass.
    private var awayWithPass = false

    /** Called right before our own code opens a picker or a fingerprint/PIN prompt. */
    fun issueExternalScreenPass() {
        passIssuedAt = clock()
    }

    /**
     * The app's screen stopped being visible.
     *
     * @param isChangingConfigurations True for a rotation (the screen is rebuilt, not left).
     * @return Whether to lock now.
     */
    fun onAppStopped(isChangingConfigurations: Boolean): LockDecision {
        if (isChangingConfigurations) return LockDecision.STAY_UNLOCKED
        if (hasValidPass()) {
            awayWithPass = true
            return LockDecision.STAY_UNLOCKED
        }
        clearPass()
        return LockDecision.LOCK_NOW
    }

    /**
     * The app's screen is visible again. Ends any pass.
     *
     * @return LOCK_NOW if the app was away on a pass for longer than the limit.
     */
    fun onAppStarted(): LockDecision {
        val overstayed = awayWithPass && !hasValidPass()
        clearPass()
        return if (overstayed) LockDecision.LOCK_NOW else LockDecision.STAY_UNLOCKED
    }

    /**
     * The screen turned off. Always locks, pass or not.
     *
     * @return LOCK_NOW.
     */
    fun onScreenOff(): LockDecision {
        clearPass()
        return LockDecision.LOCK_NOW
    }

    /** The vault locked for any reason: a pass must not survive it. */
    fun onLocked() {
        clearPass()
    }

    /**
     * How long until the current pass runs out, so a background timer can lock right then.
     *
     * @return Milliseconds left, or null if there is no pass.
     */
    fun millisUntilPassExpires(): Long? {
        val issuedAt = passIssuedAt ?: return null
        val left = issuedAt + passLimitMillis - clock()
        return left.coerceAtLeast(0L)
    }

    private fun hasValidPass(): Boolean {
        val issuedAt = passIssuedAt ?: return false
        return clock() - issuedAt <= passLimitMillis
    }

    private fun clearPass() {
        passIssuedAt = null
        awayWithPass = false
    }
}

/**
 * Decides when the vault locks for being left untouched (rule 9). Pure logic with a fake-able clock.
 *
 * @param clock Milliseconds that only go forward (SystemClock.elapsedRealtime in the app).
 * @param idleLimitSeconds The idle time; read every time, so a change in Settings applies at once.
 */
class IdleLockPolicy(
    private val clock: () -> Long,
    private val idleLimitSeconds: () -> Int = { IDLE_LOCK_SECONDS },
) {

    private val idleLimitMillis: Long
        get() = idleLimitSeconds() * MILLIS_PER_SECOND

    private var lastActivityAt: Long = clock()

    /** A touch on the screen, or a letter typed into one of our fields. */
    fun onUserActivity() {
        lastActivityAt = clock()
    }

    /**
     * How long until the idle lock, if nothing else happens.
     *
     * @return Milliseconds left (0 means: lock now).
     */
    fun millisUntilIdleLock(): Long {
        val left = lastActivityAt + idleLimitMillis - clock()
        return left.coerceAtLeast(0L)
    }

    /** True once the idle time has passed with no activity. */
    fun isIdleTooLong(): Boolean = millisUntilIdleLock() == 0L
}

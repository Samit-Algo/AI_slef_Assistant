package com.heybuddy.ui

import android.os.SystemClock
import com.heybuddy.security.BackgroundLockPolicy
import com.heybuddy.security.IdleLockPolicy
import com.heybuddy.security.LockDecision
import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.IDLE_LOCK_SECONDS
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Locks the vault automatically (rule 9): when the app leaves the screen, when the screen turns
 * off, after a pass to our own system screen runs out, and after the chosen idle time without a
 * touch. The decisions live in [BackgroundLockPolicy] and [IdleLockPolicy]; this class only feeds
 * them events and runs the timers.
 *
 * All methods are called on the main thread.
 *
 * @param lockState The app's lock.
 * @param idleLimitSeconds The idle time chosen in Settings (1, 2 or 5 minutes).
 * @param clock Milliseconds that only go forward (elapsedRealtime), so date changes cannot cheat.
 */
class AutoLockController(
    private val lockState: VaultLockState,
    idleLimitSeconds: () -> Int = { IDLE_LOCK_SECONDS },
    clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val backgroundPolicy = BackgroundLockPolicy(clock)
    private val idlePolicy = IdleLockPolicy(clock, idleLimitSeconds)
    private var passTimer: Job? = null
    private var idleTimer: Job? = null

    // The visible activity's scope, or null while the app is in the background.
    private var visibleScope: CoroutineScope? = null

    init {
        lockState.addOnLockListener {
            backgroundPolicy.onLocked()
            cancelTimers()
        }
        // After every unlock the idle countdown starts fresh (it stops while locked).
        lockState.addOnUnlockListener {
            idlePolicy.onUserActivity()
            visibleScope?.let { scope -> startIdleTimer(scope) }
        }
    }

    /** Right before our own code opens a picker or a fingerprint/PIN prompt (one-time pass). */
    fun issueExternalScreenPass() {
        backgroundPolicy.issueExternalScreenPass()
    }

    /** A touch on our screen, or a letter typed into one of our fields. */
    fun onUserActivity() {
        idlePolicy.onUserActivity()
    }

    /**
     * The idle time was changed in Settings. The running countdown may be sleeping for the OLD
     * (longer) time, so it is restarted to use the new one at once.
     */
    fun onIdleLimitChanged() {
        idlePolicy.onUserActivity()
        val scope = visibleScope ?: return
        if (lockState.status.value == LockStatus.UNLOCKED) startIdleTimer(scope)
    }

    /**
     * The activity became visible.
     *
     * @param scope Where the idle timer runs (the activity's lifecycle scope).
     */
    fun onAppStarted(scope: CoroutineScope) {
        visibleScope = scope
        passTimer?.cancel()
        passTimer = null
        if (backgroundPolicy.onAppStarted() == LockDecision.LOCK_NOW) {
            lockWith(LogEvent.AUTO_LOCK_PASS_EXPIRED)
        }
        idlePolicy.onUserActivity()
        if (lockState.status.value == LockStatus.UNLOCKED) startIdleTimer(scope)
    }

    /**
     * The activity stopped being visible.
     *
     * @param isChangingConfigurations True for a rotation (not leaving the app).
     * @param scope Where the pass timer runs (the activity's lifecycle scope, alive while stopped).
     */
    fun onAppStopped(isChangingConfigurations: Boolean, scope: CoroutineScope) {
        visibleScope = null
        idleTimer?.cancel()
        idleTimer = null
        val decision = backgroundPolicy.onAppStopped(isChangingConfigurations)
        if (decision == LockDecision.LOCK_NOW) {
            lockWith(LogEvent.AUTO_LOCK_BACKGROUND)
            return
        }
        startPassTimer(scope)
    }

    /** The screen turned off: always lock, pass or not. */
    fun onScreenOff() {
        backgroundPolicy.onScreenOff()
        lockWith(LogEvent.AUTO_LOCK_SCREEN_OFF)
    }

    // While away on a pass, lock exactly when it runs out. If the phone freezes the app and this
    // never fires, onAppStarted still locks on return (the policy checks the time itself).
    private fun startPassTimer(scope: CoroutineScope) {
        val millisLeft = backgroundPolicy.millisUntilPassExpires() ?: return
        passTimer?.cancel()
        passTimer = scope.launch {
            delay(millisLeft)
            lockWith(LogEvent.AUTO_LOCK_PASS_EXPIRED)
        }
    }

    // One waiting task, not a check every second: it sleeps until the idle time could be over.
    private fun startIdleTimer(scope: CoroutineScope) {
        idleTimer?.cancel()
        idleTimer = scope.launch {
            while (true) {
                delay(idlePolicy.millisUntilIdleLock())
                if (idlePolicy.isIdleTooLong()) {
                    lockWith(LogEvent.AUTO_LOCK_IDLE)
                    return@launch
                }
            }
        }
    }

    private fun lockWith(reason: LogEvent) {
        if (lockState.status.value == LockStatus.LOCKED) return
        SafeLog.info(reason)
        lockState.lock()
    }

    private fun cancelTimers() {
        passTimer?.cancel()
        passTimer = null
        idleTimer?.cancel()
        idleTimer = null
    }
}

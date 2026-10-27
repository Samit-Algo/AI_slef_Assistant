package com.heybuddy.security

import com.heybuddy.util.EXTERNAL_SCREEN_PASS_SECONDS
import com.heybuddy.util.IDLE_LOCK_SECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the auto-lock decisions (rule 9) with a fake clock. */
class AutoLockPolicyTest {

    private var now = 1_000_000L
    private val policy = BackgroundLockPolicy { now }
    private val passLimit = EXTERNAL_SCREEN_PASS_SECONDS * 1000L

    @Test
    fun leavingTheAppWithoutAPassLocks() {
        assertEquals(LockDecision.LOCK_NOW, policy.onAppStopped(isChangingConfigurations = false))
    }

    @Test
    fun aRotationNeverLocks() {
        assertEquals(LockDecision.STAY_UNLOCKED, policy.onAppStopped(isChangingConfigurations = true))
    }

    @Test
    fun ourOwnPickerDoesNotLock() {
        policy.issueExternalScreenPass()

        val leaving = policy.onAppStopped(isChangingConfigurations = false)
        now += 30_000L
        val coming = policy.onAppStarted()

        assertEquals(LockDecision.STAY_UNLOCKED, leaving)
        assertEquals(LockDecision.STAY_UNLOCKED, coming)
    }

    @Test
    fun aPassWorksOnlyOnce() {
        policy.issueExternalScreenPass()
        policy.onAppStopped(isChangingConfigurations = false)
        policy.onAppStarted()

        val secondTrip = policy.onAppStopped(isChangingConfigurations = false)

        assertEquals(LockDecision.LOCK_NOW, secondTrip)
    }

    @Test
    fun beingAwayLongerThanTheLimitLocksOnReturn() {
        policy.issueExternalScreenPass()
        policy.onAppStopped(isChangingConfigurations = false)

        now += passLimit + 1
        val coming = policy.onAppStarted()

        assertEquals(LockDecision.LOCK_NOW, coming)
    }

    @Test
    fun aPassIssuedLongAgoNoLongerCounts() {
        policy.issueExternalScreenPass()
        now += passLimit + 1

        assertEquals(LockDecision.LOCK_NOW, policy.onAppStopped(isChangingConfigurations = false))
    }

    @Test
    fun screenOffLocksEvenWithAPass() {
        policy.issueExternalScreenPass()
        policy.onAppStopped(isChangingConfigurations = false)

        assertEquals(LockDecision.LOCK_NOW, policy.onScreenOff())
        assertNull("The pass must be gone", policy.millisUntilPassExpires())
    }

    @Test
    fun lockingCancelsThePass() {
        policy.issueExternalScreenPass()

        policy.onLocked()

        assertEquals(LockDecision.LOCK_NOW, policy.onAppStopped(isChangingConfigurations = false))
    }

    @Test
    fun theTimerKnowsHowLongThePassHasLeft() {
        policy.issueExternalScreenPass()
        now += 20_000L

        assertEquals(passLimit - 20_000L, policy.millisUntilPassExpires())
    }

    @Test
    fun idleLocksAfterTheIdleTime() {
        val idle = IdleLockPolicy(clock = { now })

        now += IDLE_LOCK_SECONDS * 1000L - 1
        val justBefore = idle.isIdleTooLong()
        now += 1
        val atLimit = idle.isIdleTooLong()

        assertFalse(justBefore)
        assertTrue(atLimit)
    }

    @Test
    fun aTouchOrTypingResetsTheIdleTime() {
        val idle = IdleLockPolicy(clock = { now })
        now += 100_000L

        idle.onUserActivity()
        now += 100_000L

        assertFalse(idle.isIdleTooLong())
        assertEquals(IDLE_LOCK_SECONDS * 1000L - 100_000L, idle.millisUntilIdleLock())
    }
}

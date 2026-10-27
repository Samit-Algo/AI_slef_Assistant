package com.heybuddy.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the app's own lock (point 3 of the Task 1.3 plan). */
class VaultLockStateTest {

    @Test
    fun startsLocked() {
        val lockState = VaultLockState()

        assertEquals(LockStatus.LOCKED, lockState.status.value)
    }

    @Test
    fun unlocksAndLocksAgain() {
        val lockState = VaultLockState()

        lockState.markUnlocked()
        val afterUnlock = lockState.status.value
        lockState.lock()
        val afterLock = lockState.status.value

        assertEquals(LockStatus.UNLOCKED, afterUnlock)
        assertEquals(LockStatus.LOCKED, afterLock)
    }
}

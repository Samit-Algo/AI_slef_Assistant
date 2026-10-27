package com.heybuddy.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The idle lock follows the time chosen in Settings (1, 2 or 5 minutes), and changes apply at once. */
class IdleLimitTest {

    private var now = 0L
    private var limitSeconds = 60

    private val policy = IdleLockPolicy(clock = { now }, idleLimitSeconds = { limitSeconds })

    @Test
    fun oneMinuteLocksAfterOneMinute() {
        now = 59_999L
        assertFalse(policy.isIdleTooLong())

        now = 60_000L
        assertTrue(policy.isIdleTooLong())
    }

    @Test
    fun fiveMinutesDoesNotLockAfterTwo() {
        limitSeconds = 300

        now = 120_000L
        assertFalse(policy.isIdleTooLong())

        now = 300_000L
        assertTrue(policy.isIdleTooLong())
    }

    @Test
    fun aShorterChoiceAppliesAtOnce() {
        limitSeconds = 300
        now = 90_000L
        assertFalse(policy.isIdleTooLong())

        limitSeconds = 60

        assertTrue(policy.isIdleTooLong())
    }

    @Test
    fun aTouchStartsTheCountdownAgain() {
        now = 50_000L
        policy.onUserActivity()

        now = 100_000L

        assertFalse(policy.isIdleTooLong())
    }
}

package com.heybuddy.ui

import com.heybuddy.security.VaultLockState
import org.junit.Assert.assertEquals
import org.junit.Test

/** After ANY unlock the vault must open at HOME, never resume the Add screen. */
class VaultNavigatorTest {

    @Test
    fun startsAtHome() {
        val navigator = VaultNavigator(VaultLockState())

        assertEquals(VaultScreen.HOME, navigator.screen.value)
    }

    @Test
    fun lockingGoesBackToHome() {
        val lockState = VaultLockState()
        val navigator = VaultNavigator(lockState)
        lockState.markUnlocked()
        navigator.open(VaultScreen.ADD_DOCUMENT)

        lockState.lock()

        assertEquals(VaultScreen.HOME, navigator.screen.value)
    }

    @Test
    fun unlockingAlwaysStartsAtHome() {
        val lockState = VaultLockState()
        val navigator = VaultNavigator(lockState)
        navigator.open(VaultScreen.ADD_DOCUMENT)

        lockState.markUnlocked()

        assertEquals(VaultScreen.HOME, navigator.screen.value)
    }

    @Test
    fun aFreshStartAfterThePhoneClosedTheAppIsAtHome() {
        // The phone closing the app loses everything in memory; a new navigator is what comes back.
        val beforeClose = VaultNavigator(VaultLockState())
        beforeClose.open(VaultScreen.ADD_DOCUMENT)

        val afterRestart = VaultNavigator(VaultLockState())

        assertEquals(VaultScreen.HOME, afterRestart.screen.value)
    }
}

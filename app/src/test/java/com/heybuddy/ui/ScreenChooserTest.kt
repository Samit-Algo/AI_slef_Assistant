package com.heybuddy.ui

import com.heybuddy.security.LockStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks which top-level screen is shown (rule 10 and the app lock). */
class ScreenChooserTest {

    @Test
    fun noScreenLockBlocksTheVaultEvenWhenUnlocked() {
        assertEquals(TopScreen.NO_SCREEN_LOCK, chooseTopScreen(isDeviceSecure = false, lockStatus = LockStatus.LOCKED))
        assertEquals(TopScreen.NO_SCREEN_LOCK, chooseTopScreen(isDeviceSecure = false, lockStatus = LockStatus.UNLOCKED))
    }

    @Test
    fun lockedVaultShowsUnlockScreen() {
        assertEquals(TopScreen.UNLOCK, chooseTopScreen(isDeviceSecure = true, lockStatus = LockStatus.LOCKED))
    }

    @Test
    fun unlockedVaultShowsVault() {
        assertEquals(TopScreen.VAULT, chooseTopScreen(isDeviceSecure = true, lockStatus = LockStatus.UNLOCKED))
    }
}

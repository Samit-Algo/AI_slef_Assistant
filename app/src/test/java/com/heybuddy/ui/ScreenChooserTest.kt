package com.heybuddy.ui

import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultCondition
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks which top-level screen is shown (rule 10, rule 14 and the app lock). */
class ScreenChooserTest {

    @Test
    fun noScreenLockBlocksTheVaultEvenWhenUnlocked() {
        assertEquals(TopScreen.NO_SCREEN_LOCK, chooseTopScreen(false, LockStatus.LOCKED, VaultCondition.OK))
        assertEquals(TopScreen.NO_SCREEN_LOCK, chooseTopScreen(false, LockStatus.UNLOCKED, VaultCondition.OK))
    }

    @Test
    fun noScreenLockComesBeforeRecoverySoTheLockIsSetFirst() {
        assertEquals(TopScreen.NO_SCREEN_LOCK, chooseTopScreen(false, LockStatus.LOCKED, VaultCondition.KEY_GONE))
    }

    @Test
    fun keyGoneShowsRecovery() {
        assertEquals(TopScreen.RECOVERY, chooseTopScreen(true, LockStatus.LOCKED, VaultCondition.KEY_GONE))
    }

    @Test
    fun lockedVaultShowsUnlockScreen() {
        assertEquals(TopScreen.UNLOCK, chooseTopScreen(true, LockStatus.LOCKED, VaultCondition.OK))
        assertEquals(TopScreen.UNLOCK, chooseTopScreen(true, LockStatus.LOCKED, VaultCondition.NO_VAULT_YET))
    }

    @Test
    fun unlockedVaultShowsVault() {
        assertEquals(TopScreen.VAULT, chooseTopScreen(true, LockStatus.UNLOCKED, VaultCondition.OK))
    }
}

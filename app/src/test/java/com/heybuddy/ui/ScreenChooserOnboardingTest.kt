package com.heybuddy.ui

import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultCondition
import org.junit.Assert.assertEquals
import org.junit.Test

/** When onboarding and the "Deleting…" screen appear (Task 1.10). */
class ScreenChooserOnboardingTest {

    @Test
    fun aFirstStartShowsOnboarding() {
        val screen = chooseTopScreen(true, LockStatus.LOCKED, VaultCondition.NO_VAULT_YET, onboardingDone = false)

        assertEquals(TopScreen.ONBOARDING, screen)
    }

    @Test
    fun onboardingComesBeforeTheScreenLockCheck() {
        val screen = chooseTopScreen(false, LockStatus.LOCKED, VaultCondition.NO_VAULT_YET, onboardingDone = false)

        assertEquals(TopScreen.ONBOARDING, screen)
    }

    @Test
    fun afterOnboardingANewUserGoesToUnlock() {
        val screen = chooseTopScreen(true, LockStatus.LOCKED, VaultCondition.NO_VAULT_YET, onboardingDone = true)

        assertEquals(TopScreen.UNLOCK, screen)
    }

    @Test
    fun anExistingVaultNeverShowsOnboarding() {
        // For example after updating from a version without onboarding: the flag is not set yet.
        val okVault = chooseTopScreen(true, LockStatus.LOCKED, VaultCondition.OK, onboardingDone = false)
        val brokenVault = chooseTopScreen(true, LockStatus.LOCKED, VaultCondition.KEY_GONE, onboardingDone = false)

        assertEquals(TopScreen.UNLOCK, okVault)
        assertEquals(TopScreen.RECOVERY, brokenVault)
    }

    @Test
    fun whileErasingOnlyTheDeletingScreenShows() {
        for (status in LockStatus.entries) {
            for (condition in VaultCondition.entries) {
                val screen = chooseTopScreen(true, status, condition, onboardingDone = false, erasing = true)
                assertEquals(TopScreen.ERASING, screen)
            }
        }
    }
}

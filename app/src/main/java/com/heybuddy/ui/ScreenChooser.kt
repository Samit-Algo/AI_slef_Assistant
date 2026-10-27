package com.heybuddy.ui

import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultCondition

/** The top-level screens the app can show. */
enum class TopScreen {
    ERASING,
    ONBOARDING,
    NO_SCREEN_LOCK,
    RECOVERY,
    UNLOCK,
    VAULT,
}

/**
 * Decides which top-level screen to show.
 *
 * Order matters:
 * 1. While the vault is being deleted, a plain "Deleting…" screen (nothing can be tapped).
 * 2. Onboarding, only on a first start (no vault yet and onboarding not finished).
 * 3. Without a screen lock the vault is blocked (rule 10), so a user who removed the lock is
 *    first asked to set one again.
 * 4. Only if the master key is CERTAINLY gone, the recovery screen (rule 14).
 * 5. Otherwise Unlock or the vault.
 *
 * @param isDeviceSecure Whether the phone has a PIN, pattern or password.
 * @param lockStatus The app's own lock status.
 * @param condition The vault's key condition.
 * @param onboardingDone Whether the user already finished onboarding.
 * @param erasing Whether "Delete everything" is running right now.
 * @return The screen to show.
 */
fun chooseTopScreen(
    isDeviceSecure: Boolean,
    lockStatus: LockStatus,
    condition: VaultCondition,
    onboardingDone: Boolean = true,
    erasing: Boolean = false,
): TopScreen {
    if (erasing) return TopScreen.ERASING
    val firstStart = condition == VaultCondition.NO_VAULT_YET && !onboardingDone
    if (firstStart) return TopScreen.ONBOARDING
    if (!isDeviceSecure) return TopScreen.NO_SCREEN_LOCK
    if (condition == VaultCondition.KEY_GONE) return TopScreen.RECOVERY
    return when (lockStatus) {
        LockStatus.LOCKED -> TopScreen.UNLOCK
        LockStatus.UNLOCKED -> TopScreen.VAULT
    }
}

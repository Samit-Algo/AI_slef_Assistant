package com.heybuddy.ui

import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultCondition

/** The top-level screens the app can show. */
enum class TopScreen {
    NO_SCREEN_LOCK,
    RECOVERY,
    UNLOCK,
    VAULT,
}

/**
 * Decides which top-level screen to show.
 *
 * Order matters: without a screen lock the vault is blocked first (rule 10), so a user who removed
 * the lock is first asked to set one again. Then, only if the master key is CERTAINLY gone, the
 * recovery screen (rule 14). Otherwise Unlock or the vault.
 *
 * @param isDeviceSecure Whether the phone has a PIN, pattern or password.
 * @param lockStatus The app's own lock status.
 * @param condition The vault's key condition.
 * @return The screen to show.
 */
fun chooseTopScreen(isDeviceSecure: Boolean, lockStatus: LockStatus, condition: VaultCondition): TopScreen {
    if (!isDeviceSecure) return TopScreen.NO_SCREEN_LOCK
    if (condition == VaultCondition.KEY_GONE) return TopScreen.RECOVERY
    return when (lockStatus) {
        LockStatus.LOCKED -> TopScreen.UNLOCK
        LockStatus.UNLOCKED -> TopScreen.VAULT
    }
}

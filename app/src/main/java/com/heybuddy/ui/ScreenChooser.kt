package com.heybuddy.ui

import com.heybuddy.security.LockStatus

/** The top-level screens the app can show. */
enum class TopScreen {
    NO_SCREEN_LOCK,
    UNLOCK,
    VAULT,
}

/**
 * Decides which top-level screen to show.
 *
 * The screen-lock check comes first: without a screen lock the vault is blocked (rule 10),
 * even if it was unlocked a moment ago.
 *
 * @param isDeviceSecure Whether the phone has a PIN, pattern or password.
 * @param lockStatus The app's own lock status.
 * @return The screen to show.
 */
fun chooseTopScreen(isDeviceSecure: Boolean, lockStatus: LockStatus): TopScreen {
    if (!isDeviceSecure) return TopScreen.NO_SCREEN_LOCK
    return when (lockStatus) {
        LockStatus.LOCKED -> TopScreen.UNLOCK
        LockStatus.UNLOCKED -> TopScreen.VAULT
    }
}

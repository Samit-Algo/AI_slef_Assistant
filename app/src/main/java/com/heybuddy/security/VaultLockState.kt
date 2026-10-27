package com.heybuddy.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the vault is open for the user right now. */
enum class LockStatus {
    LOCKED,
    UNLOCKED,
}

/**
 * The app's own lock: the single source of truth for "is the vault open?".
 *
 * Kept in memory only, never saved, so every new app process starts [LockStatus.LOCKED].
 * The Keystore key being usable is NOT enough to count as unlocked (see [MasterKeyManager]).
 * The idle timer is added in Task 1.9.
 */
class VaultLockState {

    private val mutableStatus = MutableStateFlow(LockStatus.LOCKED)

    /** The current lock status. Screens watch this to decide what to show. */
    val status: StateFlow<LockStatus> = mutableStatus.asStateFlow()

    /** Opens the vault. Only [BiometricUnlocker] should call this, after it has proven the unlock. */
    fun markUnlocked() {
        mutableStatus.value = LockStatus.UNLOCKED
    }

    /** Closes the vault. */
    fun lock() {
        mutableStatus.value = LockStatus.LOCKED
    }
}

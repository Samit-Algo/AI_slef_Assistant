package com.heybuddy.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

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

    // Thread-safe lists: listeners may be added while lock()/markUnlocked() run on another thread.
    private val lockListeners = CopyOnWriteArrayList<() -> Unit>()
    private val unlockListeners = CopyOnWriteArrayList<() -> Unit>()

    /** The current lock status. Screens watch this to decide what to show. */
    val status: StateFlow<LockStatus> = mutableStatus.asStateFlow()

    /**
     * Registers something to run every time the vault locks, for example wiping secrets from memory.
     *
     * @param listener Called on the thread that calls [lock]. Must not block.
     */
    fun addOnLockListener(listener: () -> Unit) {
        lockListeners.add(listener)
    }

    /**
     * Registers something to run right after every unlock, while the fingerprint/PIN key window is open.
     *
     * @param listener Called on the thread that calls [markUnlocked]. Must not block.
     */
    fun addOnUnlockListener(listener: () -> Unit) {
        unlockListeners.add(listener)
    }

    /** Opens the vault. Only [BiometricUnlocker] should call this, after it has proven the unlock. */
    fun markUnlocked() {
        mutableStatus.value = LockStatus.UNLOCKED
        for (listener in unlockListeners) {
            listener()
        }
    }

    /**
     * Closes the vault and runs every lock listener, so decrypted secrets do not outlive the lock.
     * The status changes first, so screens and new operations see "locked" at once.
     */
    fun lock() {
        mutableStatus.value = LockStatus.LOCKED
        for (listener in lockListeners) {
            listener()
        }
    }
}

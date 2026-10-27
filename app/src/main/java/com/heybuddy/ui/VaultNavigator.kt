package com.heybuddy.ui

import com.heybuddy.security.VaultLockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screens inside the unlocked vault. */
enum class VaultScreen {
    HOME,
    ADD_DOCUMENT,
}

/**
 * Which vault screen is showing. After ANY unlock the app must land on [VaultScreen.HOME].
 *
 * Kept in memory only, on purpose: never in Android's saved screen state. Saved state survives the
 * phone closing the app in the background, which used to bring a user back to the Add screen
 * after unlocking. Resets to HOME on every lock and every unlock.
 *
 * Registers itself with [lockState] when created.
 *
 * @param lockState The app's lock.
 */
class VaultNavigator(lockState: VaultLockState) {

    private val mutableScreen = MutableStateFlow(VaultScreen.HOME)

    /** The vault screen to show. */
    val screen: StateFlow<VaultScreen> = mutableScreen.asStateFlow()

    init {
        lockState.addOnLockListener { backToHome() }
        // Also on unlock, so even a lock that was missed can never resume a deeper screen.
        lockState.addOnUnlockListener { backToHome() }
    }

    /**
     * Shows a vault screen.
     *
     * @param target The screen to show.
     */
    fun open(target: VaultScreen) {
        mutableScreen.value = target
    }

    /** Goes back to the vault home screen. */
    fun backToHome() {
        mutableScreen.value = VaultScreen.HOME
    }
}

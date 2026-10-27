package com.heybuddy.ui

import com.heybuddy.security.VaultLockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screens inside the unlocked vault. */
sealed interface VaultScreen {

    /** The vault list. Every unlock lands here. */
    data object List : VaultScreen

    /** Adding a new document. */
    data object AddDocument : VaultScreen

    /**
     * Viewing one document.
     *
     * @property documentId The document being viewed.
     */
    data class Viewer(val documentId: String) : VaultScreen
}

/**
 * Which vault screen is showing. After ANY unlock the app must land on [VaultScreen.List].
 *
 * Kept in memory only, on purpose: never in Android's saved screen state. Saved state survives the
 * phone closing the app in the background, which used to bring a user back to the Add screen
 * after unlocking. Resets to the list on every lock and every unlock.
 *
 * Registers itself with [lockState] when created.
 *
 * @param lockState The app's lock.
 */
class VaultNavigator(lockState: VaultLockState) {

    private val mutableScreen = MutableStateFlow<VaultScreen>(VaultScreen.List)

    /** The vault screen to show. */
    val screen: StateFlow<VaultScreen> = mutableScreen.asStateFlow()

    init {
        lockState.addOnLockListener { backToList() }
        // Also on unlock, so even a lock that was missed can never resume a deeper screen.
        lockState.addOnUnlockListener { backToList() }
    }

    /**
     * Shows a vault screen.
     *
     * @param target The screen to show.
     */
    fun open(target: VaultScreen) {
        mutableScreen.value = target
    }

    /** Goes back to the vault list. */
    fun backToList() {
        mutableScreen.value = VaultScreen.List
    }

    /**
     * Handles the system Back gesture or button for the vault screens.
     *
     * Screens do their own tidy-up first (the viewer frees its document, the Add screen wipes the
     * picked file), then call this.
     *
     * @return true if Back was used (the app went back to the list); false on the list itself,
     *   where Back should leave the app normally.
     */
    fun handleBack(): Boolean {
        if (mutableScreen.value == VaultScreen.List) return false
        backToList()
        return true
    }
}

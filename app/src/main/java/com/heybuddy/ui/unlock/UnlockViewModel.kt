package com.heybuddy.ui.unlock

import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import com.heybuddy.R
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.UnlockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Unlock screen shows.
 *
 * @property errorMessage The last failure message, or null if none.
 * @property showNoFingerprintNote True when no fingerprint is set up, so we explain that PIN works.
 */
data class UnlockUiState(
    @StringRes val errorMessage: Int? = null,
    val showNoFingerprintNote: Boolean = false,
)

/**
 * Holds the Unlock screen's state and starts unlock attempts.
 *
 * @param unlocker Shows the prompt and proves the unlock.
 */
class UnlockViewModel(private val unlocker: BiometricUnlocker) : ViewModel() {

    private val mutableUiState = MutableStateFlow(UnlockUiState())

    /** The current screen state. */
    val uiState: StateFlow<UnlockUiState> = mutableUiState.asStateFlow()

    /**
     * Refreshes the "no fingerprint" note. Called whenever the screen comes back into view,
     * because the user may have added or removed a fingerprint in Settings meanwhile.
     *
     * @param activity The current activity.
     */
    fun refreshFingerprintNote(activity: FragmentActivity) {
        val fingerprintReady = unlocker.isFingerprintSetUp(activity)
        mutableUiState.value = mutableUiState.value.copy(showNoFingerprintNote = !fingerprintReady)
    }

    /**
     * Starts an unlock attempt when the user taps the Unlock button.
     *
     * @param activity The activity that shows the prompt.
     */
    fun onUnlockClicked(activity: FragmentActivity) {
        clearError()
        val title = activity.getString(R.string.unlock_prompt_title)
        val subtitle = activity.getString(R.string.unlock_prompt_subtitle)
        unlocker.unlock(activity, title, subtitle, ::handleResult)
    }

    private fun handleResult(result: UnlockResult) {
        // On success the vault lock state changes and the app moves away from this screen.
        if (result is UnlockResult.Failed) {
            val message = messageForFailure(result.reason)
            mutableUiState.value = mutableUiState.value.copy(errorMessage = message)
        }
    }

    private fun clearError() {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
    }
}

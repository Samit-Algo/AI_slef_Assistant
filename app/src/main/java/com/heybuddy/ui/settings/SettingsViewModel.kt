package com.heybuddy.ui.settings

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heybuddy.R
import com.heybuddy.security.BiometricReAuthPrompt
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.LockStatus
import com.heybuddy.security.ReAuthPrompt
import com.heybuddy.security.VaultLockState
import com.heybuddy.settings.AppSettings
import com.heybuddy.settings.AutoLockChoice
import com.heybuddy.settings.DeleteEverythingFlow
import com.heybuddy.settings.DeleteEverythingResult
import com.heybuddy.settings.ThemeChoice
import com.heybuddy.settings.isDeleteEverythingConfirmed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * One entry in "Open-source licences".
 *
 * @property libraryName What the software is.
 * @property licenceName Which licence it uses.
 * @property licenceText The full licence text, shipped inside the app (no internet needed).
 * @property note A plain explanation shown above the text, or null.
 */
enum class LicenceEntry(
    @StringRes val libraryName: Int,
    @StringRes val licenceName: Int,
    @RawRes val licenceText: Int,
    @StringRes val note: Int? = null,
) {
    INTER(R.string.licence_name_inter, R.string.licence_type_ofl, R.raw.licence_inter_ofl),
    MATERIAL_SYMBOLS(R.string.licence_name_symbols, R.string.licence_type_apache, R.raw.licence_apache_2_0),
    ANDROIDX(R.string.licence_name_androidx, R.string.licence_type_apache, R.raw.licence_apache_2_0),
    KOTLIN(R.string.licence_name_kotlin, R.string.licence_type_apache, R.raw.licence_apache_2_0),
    TINK(R.string.licence_name_tink, R.string.licence_type_apache, R.raw.licence_apache_2_0),
    SMALL_LIBRARIES(R.string.licence_name_small, R.string.licence_type_apache, R.raw.licence_apache_2_0),
    SQLCIPHER(R.string.licence_name_sqlcipher, R.string.licence_type_sqlcipher, R.raw.licence_sqlcipher),

    // Not open source itself: ML Kit's terms are summarised in the note; the text below it lists
    // the open-source parts ML Kit bundles (spec 7.4).
    ML_KIT(R.string.licence_name_mlkit, R.string.licence_type_mlkit, R.raw.licence_mlkit_third_party, R.string.licence_note_mlkit),

    // Tamil reading (Task 2.3): Tesseract and the parts built into it, each with its own licence.
    TESSERACT(R.string.licence_name_tesseract, R.string.licence_type_tesseract, R.raw.licence_tesseract),
}

/**
 * The "Delete everything" dialog.
 *
 * @property typed What is in the "Type DELETE" field.
 * @property running True while "Confirm it's you" or the deletion runs.
 */
data class DeleteAllDialogState(val typed: String = "", val running: Boolean = false) {
    /** The red button works only after "DELETE" is typed exactly. */
    val canConfirm: Boolean
        get() = isDeleteEverythingConfirmed(typed) && !running
}

/**
 * What the Settings screen shows besides the stored settings.
 *
 * @property deleteDialog The "Delete everything" dialog, or null when closed.
 * @property notConfirmedNotice True after fingerprint/PIN was cancelled: "Nothing was deleted".
 * @property openLicence The licence text being read, or null for the main Settings page.
 */
data class SettingsUiState(
    val deleteDialog: DeleteAllDialogState? = null,
    val notConfirmedNotice: Boolean = false,
    val openLicence: LicenceEntry? = null,
)

/**
 * Runs the Settings screen: theme, auto-lock time, licences, and "Delete everything".
 *
 * @param settings The stored, non-secret settings.
 * @param deleteFlow Asks "Confirm it's you", then erases the vault.
 * @param unlocker Shows the fingerprint/PIN prompt.
 * @param onIdleLimitChanged Tells auto-lock that the idle time changed (restarts its countdown).
 * @param lockState The app's lock; the screen state is cleared when it locks.
 */
class SettingsViewModel(
    private val settings: AppSettings,
    private val deleteFlow: DeleteEverythingFlow,
    private val unlocker: BiometricUnlocker,
    private val onIdleLimitChanged: () -> Unit,
    lockState: VaultLockState,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(SettingsUiState())

    /** Dialog and licence state. */
    val uiState: StateFlow<SettingsUiState> = mutableUiState.asStateFlow()

    /** The chosen theme. */
    val theme: StateFlow<ThemeChoice> = settings.theme

    /** The chosen auto-lock time. */
    val autoLock: StateFlow<AutoLockChoice> = settings.autoLock

    init {
        viewModelScope.launch {
            lockState.status.filter { status -> status == LockStatus.LOCKED }.collect {
                mutableUiState.value = SettingsUiState()
            }
        }
    }

    /** @param choice The theme to use from now on. */
    fun setTheme(choice: ThemeChoice) {
        settings.setTheme(choice)
    }

    /** @param choice The idle time to use from now on (applies at once). */
    fun setAutoLock(choice: AutoLockChoice) {
        settings.setAutoLock(choice)
        onIdleLimitChanged()
    }

    /** @param entry The licence to read. */
    fun openLicence(entry: LicenceEntry) {
        mutableUiState.value = mutableUiState.value.copy(openLicence = entry)
    }

    /** Opens "Delete everything?" with an empty field. */
    fun openDeleteAll() {
        mutableUiState.value = mutableUiState.value.copy(deleteDialog = DeleteAllDialogState(), notConfirmedNotice = false)
    }

    /** @param text What the "Type DELETE" field now contains. */
    fun onDeleteTextChanged(text: String) {
        val dialog = mutableUiState.value.deleteDialog ?: return
        if (dialog.running) return
        mutableUiState.value = mutableUiState.value.copy(deleteDialog = dialog.copy(typed = text))
    }

    /** Closes "Delete everything?" without deleting. */
    fun closeDeleteAll() {
        val dialog = mutableUiState.value.deleteDialog ?: return
        if (dialog.running) return
        mutableUiState.value = mutableUiState.value.copy(deleteDialog = null)
    }

    /**
     * After "DELETE" was typed: asks "Confirm it's you", then deletes everything.
     *
     * @param activity Shows the fingerprint/PIN prompt.
     */
    fun confirmDeleteAll(activity: FragmentActivity) {
        val prompt = BiometricReAuthPrompt(activity, unlocker, R.string.delete_all_prompt_subtitle)
        confirmDeleteAllWith(prompt)
    }

    /** Test hook: [confirmDeleteAll] with any prompt (tests use a fake one). */
    internal fun confirmDeleteAllWith(prompt: ReAuthPrompt) {
        val dialog = mutableUiState.value.deleteDialog ?: return
        if (!dialog.canConfirm) return
        mutableUiState.value = mutableUiState.value.copy(deleteDialog = dialog.copy(running = true))
        viewModelScope.launch {
            val result = deleteFlow.run(prompt)
            if (result == DeleteEverythingResult.NOT_CONFIRMED) {
                mutableUiState.value = SettingsUiState(notConfirmedNotice = true)
            }
            // ERASED: the vault locked and erased itself; the app shows onboarding next.
        }
    }

    /**
     * Handles Back: closes the licence text or the dialog first.
     *
     * @return true if Back was used here; false if the Settings screen itself should close.
     */
    fun onBack(): Boolean {
        val current = mutableUiState.value
        if (current.deleteDialog != null) {
            closeDeleteAll()
            return true
        }
        if (current.openLicence != null) {
            mutableUiState.value = current.copy(openLicence = null)
            return true
        }
        return false
    }
}

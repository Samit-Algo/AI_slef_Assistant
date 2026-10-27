package com.heybuddy.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.heybuddy.util.IDLE_LOCK_SECONDS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which colour theme the app uses. */
enum class ThemeChoice {
    FOLLOW_PHONE,
    LIGHT,
    DARK,
}

/**
 * How long the vault may stay untouched before it locks itself (rule 9).
 *
 * @property seconds The idle time in seconds.
 */
enum class AutoLockChoice(val seconds: Int) {
    ONE_MINUTE(60),
    TWO_MINUTES(120),
    FIVE_MINUTES(300),
}

/** The few key-value operations [AppSettings] needs. Lets tests use a plain map instead of Android. */
interface SettingsStore {

    /** @return The stored text for [key], or null if nothing is stored. */
    fun getString(key: String): String?

    /** Stores [value] under [key]. */
    fun putString(key: String, value: String)

    /** @return The stored flag for [key], or [default] if nothing is stored. */
    fun getBoolean(key: String, default: Boolean): Boolean

    /** Stores [value] under [key]. */
    fun putBoolean(key: String, value: Boolean)

    /** Removes everything. */
    fun clear()
}

/**
 * [SettingsStore] backed by Android's SharedPreferences.
 *
 * @param preferences The app-private preferences file (never backed up: rule 7 excludes everything).
 */
class SharedPreferencesStore(private val preferences: SharedPreferences) : SettingsStore {

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit { putBoolean(key, value) }
    }

    override fun clear() {
        preferences.edit { clear() }
    }
}

/**
 * The app's few non-secret settings: theme, auto-lock time, and whether onboarding was finished.
 *
 * Stored OUTSIDE the vault on purpose: the Unlock, onboarding and recovery screens are shown while
 * the vault is locked and still need the theme. None of this is personal data.
 *
 * @param store Where the values are kept.
 */
class AppSettings(private val store: SettingsStore) {

    private val mutableTheme = MutableStateFlow(readTheme())
    private val mutableAutoLock = MutableStateFlow(readAutoLock())
    private val mutableOnboardingDone = MutableStateFlow(store.getBoolean(KEY_ONBOARDING_DONE, false))

    /** The chosen theme. */
    val theme: StateFlow<ThemeChoice> = mutableTheme.asStateFlow()

    /** The chosen auto-lock time. */
    val autoLock: StateFlow<AutoLockChoice> = mutableAutoLock.asStateFlow()

    /** True once the user tapped "Set up my vault" on the last onboarding card. */
    val onboardingDone: StateFlow<Boolean> = mutableOnboardingDone.asStateFlow()

    /**
     * Saves a new theme.
     *
     * @param choice The theme to use from now on.
     */
    fun setTheme(choice: ThemeChoice) {
        store.putString(KEY_THEME, choice.name)
        mutableTheme.value = choice
    }

    /**
     * Saves a new auto-lock time.
     *
     * @param choice The idle time to use from now on.
     */
    fun setAutoLock(choice: AutoLockChoice) {
        store.putString(KEY_AUTO_LOCK, choice.name)
        mutableAutoLock.value = choice
    }

    /** Records that onboarding was finished, so it is not shown again. */
    fun markOnboardingDone() {
        store.putBoolean(KEY_ONBOARDING_DONE, true)
        mutableOnboardingDone.value = true
    }

    /** Back to first-start defaults (used by "Delete everything"), so onboarding shows again. */
    fun resetAll() {
        store.clear()
        mutableTheme.value = ThemeChoice.FOLLOW_PHONE
        mutableAutoLock.value = DEFAULT_AUTO_LOCK
        mutableOnboardingDone.value = false
    }

    // An unknown stored value (for example from a future version) falls back to the default.
    private fun readTheme(): ThemeChoice {
        val stored = store.getString(KEY_THEME) ?: return ThemeChoice.FOLLOW_PHONE
        return ThemeChoice.entries.firstOrNull { choice -> choice.name == stored } ?: ThemeChoice.FOLLOW_PHONE
    }

    private fun readAutoLock(): AutoLockChoice {
        val stored = store.getString(KEY_AUTO_LOCK) ?: return DEFAULT_AUTO_LOCK
        return AutoLockChoice.entries.firstOrNull { choice -> choice.name == stored } ?: DEFAULT_AUTO_LOCK
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_AUTO_LOCK = "auto_lock"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        /** The default matches IDLE_LOCK_SECONDS (2 minutes), the value used before Settings existed. */
        val DEFAULT_AUTO_LOCK: AutoLockChoice =
            AutoLockChoice.entries.first { choice -> choice.seconds == IDLE_LOCK_SECONDS }
    }
}

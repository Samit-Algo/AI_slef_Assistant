package com.heybuddy.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the stored settings: defaults, saving, reading back, unknown values and reset. */
class AppSettingsTest {

    private val store = MapStore()

    @Test
    fun defaultsOnAFirstStart() {
        val settings = AppSettings(store)

        assertEquals(ThemeChoice.FOLLOW_PHONE, settings.theme.value)
        assertEquals(AutoLockChoice.TWO_MINUTES, settings.autoLock.value)
        assertFalse(settings.onboardingDone.value)
    }

    @Test
    fun choicesAreSavedAndReadBackAfterARestart() {
        val settings = AppSettings(store)
        settings.setTheme(ThemeChoice.DARK)
        settings.setAutoLock(AutoLockChoice.ONE_MINUTE)
        settings.markOnboardingDone()

        val afterRestart = AppSettings(store)

        assertEquals(ThemeChoice.DARK, afterRestart.theme.value)
        assertEquals(AutoLockChoice.ONE_MINUTE, afterRestart.autoLock.value)
        assertTrue(afterRestart.onboardingDone.value)
    }

    @Test
    fun anUnknownStoredValueFallsBackToTheDefault() {
        store.putString("theme", "PURPLE")
        store.putString("auto_lock", "TEN_HOURS")

        val settings = AppSettings(store)

        assertEquals(ThemeChoice.FOLLOW_PHONE, settings.theme.value)
        assertEquals(AutoLockChoice.TWO_MINUTES, settings.autoLock.value)
    }

    @Test
    fun resetGoesBackToAFirstStart() {
        val settings = AppSettings(store)
        settings.setTheme(ThemeChoice.LIGHT)
        settings.setAutoLock(AutoLockChoice.FIVE_MINUTES)
        settings.markOnboardingDone()

        settings.resetAll()

        assertEquals(ThemeChoice.FOLLOW_PHONE, settings.theme.value)
        assertEquals(AutoLockChoice.TWO_MINUTES, settings.autoLock.value)
        assertFalse(settings.onboardingDone.value)
        assertFalse(AppSettings(store).onboardingDone.value)
    }

    @Test
    fun autoLockChoicesAreOneTwoAndFiveMinutes() {
        val seconds = AutoLockChoice.entries.map { choice -> choice.seconds }

        assertEquals(listOf(60, 120, 300), seconds)
    }

    /** A plain in-memory store, so the test needs no Android. */
    private class MapStore : SettingsStore {
        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String): String? = values[key] as? String

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun clear() {
            values.clear()
        }
    }
}

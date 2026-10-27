package com.heybuddy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app's Material 3 theme. Follows the phone's light/dark setting.
 *
 * @param content The screen content to show inside the theme.
 */
@Composable
fun HeyBuddyTheme(content: @Composable () -> Unit) {
    val useDarkTheme = isSystemInDarkTheme()
    val colorScheme = chooseColorScheme(useDarkTheme)
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Uses the wallpaper-based colours on Android 12+, so the app feels native; plain Material colours before that. */
@Composable
private fun chooseColorScheme(useDarkTheme: Boolean): ColorScheme {
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (supportsDynamicColor) {
        val context = LocalContext.current
        return if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (useDarkTheme) darkColorScheme() else lightColorScheme()
}

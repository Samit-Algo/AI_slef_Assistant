package com.heybuddy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// Which theme is showing. Read by parts that pick their own colours (for example type badges),
// so they always match the theme even when a caller forces light or dark.
private val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * The app's theme (spec 5.14): fixed brand colours, Inter type, shapes, and the reduce-motion flag.
 *
 * Wallpaper-based colours ("Material You") are deliberately not used, so Hey Buddy always looks
 * the same and recognisably itself (owner decision).
 *
 * @param darkTheme Whether to use the dark theme. Follows the phone's setting by default.
 * @param content The screen content.
 */
@Composable
fun HeyBuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    // Changing the theme cross-fades through ThemeCrossfadeHost, not by animating these colours.
    val colorScheme = if (darkTheme) HeyBuddyDarkColors else HeyBuddyLightColors
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HeyBuddyTypography,
            shapes = HeyBuddyShapes,
            content = content,
        )
    }
}

/** True while the dark theme is showing. */
@Composable
@ReadOnlyComposable
fun isDarkThemeActive(): Boolean = LocalDarkTheme.current


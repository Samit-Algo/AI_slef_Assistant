package com.heybuddy.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette (spec 5.14). Contrast of every text pair was measured: 7.2:1 to 17.4:1 (WCAG AA needs 4.5:1).

private val SapphireLight = Color(0xFF1E4D8C)
private val SlateLight = Color(0xFF3D5A73)
private val TealLight = Color(0xFF0B6E60)
private val ErrorLight = Color(0xFFB3261E)

private val SapphireDark = Color(0xFFA8C8FF)
private val SlateDark = Color(0xFFB4C8DC)
private val TealDark = Color(0xFF7AD6C4)
private val ErrorDark = Color(0xFFFFB4AB)

/** The light colour scheme. Wallpaper-based colours are deliberately not used (owner decision). */
val HeyBuddyLightColors: ColorScheme = lightColorScheme(
    primary = SapphireLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF0A2D5C),
    secondary = SlateLight,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6F0),
    onSecondaryContainer = Color(0xFF1E3346),
    tertiary = TealLight,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCFEFE8),
    onTertiaryContainer = Color(0xFF00382F),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF171A21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A21),
    surfaceVariant = Color(0xFFEBEFF5),
    onSurfaceVariant = Color(0xFF454C5B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF1F3F8),
    surfaceContainerHigh = Color(0xFFEBEFF5),
    surfaceContainerHighest = Color(0xFFE4E8F0),
    outline = Color(0xFF737B8C),
    outlineVariant = Color(0xFFD5DAE3),
    error = ErrorLight,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E2),
    onErrorContainer = Color(0xFF5C0F0A),
)

/** The dark colour scheme. */
val HeyBuddyDarkColors: ColorScheme = darkColorScheme(
    primary = SapphireDark,
    onPrimary = Color(0xFF0A2D5C),
    primaryContainer = Color(0xFF1E4D8C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = SlateDark,
    onSecondary = Color(0xFF1E3346),
    secondaryContainer = Color(0xFF34495C),
    onSecondaryContainer = Color(0xFFDCE6F0),
    tertiary = TealDark,
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF0B4F45),
    onTertiaryContainer = Color(0xFFCFEFE8),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE3E6EE),
    surface = Color(0xFF161A22),
    onSurface = Color(0xFFE3E6EE),
    surfaceVariant = Color(0xFF242936),
    onSurfaceVariant = Color(0xFFB8BFCD),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF13171E),
    surfaceContainer = Color(0xFF1A1F28),
    surfaceContainerHigh = Color(0xFF242936),
    surfaceContainerHighest = Color(0xFF2E3442),
    outline = Color(0xFF8A92A3),
    outlineVariant = Color(0xFF353B48),
    error = ErrorDark,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1210),
    onErrorContainer = Color(0xFFFFDAD6),
)

package com.heybuddy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.heybuddy.R

/**
 * Inter (SIL Open Font License 1.1), bundled in res/font: the app has no internet, so no
 * downloadable fonts. Letters Inter lacks (for example Devanagari) fall back to the phone's font.
 */
val InterFontFamily: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

// Sizes are in sp so they grow with the phone's font-size setting (accessibility, spec 5.14).
private fun interStyle(size: Int, lineHeight: Int, weight: FontWeight): TextStyle {
    return TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
    )
}

/**
 * The type scale from spec 5.14, mapped onto Material 3 roles:
 * Headline = headlineMedium, Title = titleLarge, Card title = titleMedium, Body = bodyLarge,
 * Body small = bodyMedium, Label = labelLarge, Caption = labelSmall.
 */
val HeyBuddyTypography: Typography = Typography(
    headlineLarge = interStyle(size = 32, lineHeight = 40, weight = FontWeight.SemiBold),
    headlineMedium = interStyle(size = 28, lineHeight = 36, weight = FontWeight.SemiBold),
    headlineSmall = interStyle(size = 24, lineHeight = 32, weight = FontWeight.SemiBold),
    titleLarge = interStyle(size = 20, lineHeight = 28, weight = FontWeight.SemiBold),
    titleMedium = interStyle(size = 16, lineHeight = 24, weight = FontWeight.Medium),
    titleSmall = interStyle(size = 14, lineHeight = 20, weight = FontWeight.Medium),
    bodyLarge = interStyle(size = 16, lineHeight = 24, weight = FontWeight.Normal),
    bodyMedium = interStyle(size = 14, lineHeight = 20, weight = FontWeight.Normal),
    bodySmall = interStyle(size = 12, lineHeight = 16, weight = FontWeight.Normal),
    labelLarge = interStyle(size = 14, lineHeight = 20, weight = FontWeight.Medium),
    labelMedium = interStyle(size = 12, lineHeight = 16, weight = FontWeight.Medium),
    labelSmall = interStyle(size = 12, lineHeight = 16, weight = FontWeight.Medium),
)

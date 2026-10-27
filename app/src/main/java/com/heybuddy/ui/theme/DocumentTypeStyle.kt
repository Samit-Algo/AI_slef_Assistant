package com.heybuddy.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.heybuddy.R
import com.heybuddy.data.DocumentType

/**
 * How one document type looks (spec 5.14): its icon and its colour in both themes.
 * The colour is for icons only, never for text.
 *
 * @property icon The Material Symbols icon.
 * @property lightColor Icon colour in the light theme.
 * @property darkColor Icon colour in the dark theme.
 */
data class DocumentTypeStyle(
    @DrawableRes val icon: Int,
    val lightColor: Color,
    val darkColor: Color,
)

/**
 * The style of a document type. Generic icons only: never government logos or emblems.
 *
 * @param type The document type.
 * @return Its icon and colours.
 */
fun styleFor(type: DocumentType): DocumentTypeStyle {
    return when (type) {
        DocumentType.AADHAAR -> DocumentTypeStyle(R.drawable.ic_badge, Color(0xFFB45309), Color(0xFFFBBF24))
        DocumentType.PAN -> DocumentTypeStyle(R.drawable.ic_credit_card, Color(0xFF4338CA), Color(0xFFA5B4FC))
        DocumentType.DRIVING_LICENCE -> DocumentTypeStyle(R.drawable.ic_directions_car, Color(0xFF0F766E), Color(0xFF5EEAD4))
        DocumentType.PASSPORT -> DocumentTypeStyle(R.drawable.ic_flight, Color(0xFF1D4ED8), Color(0xFF93C5FD))
        DocumentType.PHOTO -> DocumentTypeStyle(R.drawable.ic_photo_camera, Color(0xFF7E22CE), Color(0xFFD8B4FE))
        DocumentType.SIGNATURE -> DocumentTypeStyle(R.drawable.ic_signature, Color(0xFFBE185D), Color(0xFFF9A8D4))
        DocumentType.CERTIFICATE -> DocumentTypeStyle(R.drawable.ic_workspace_premium, Color(0xFF15803D), Color(0xFF86EFAC))
        DocumentType.OTHER -> DocumentTypeStyle(R.drawable.ic_description, Color(0xFF475569), Color(0xFFCBD5E1))
    }
}

/** Icon colour for the current theme. */
@Composable
fun DocumentTypeStyle.iconColor(): Color = if (isDarkThemeActive()) darkColor else lightColor

/**
 * The soft tinted circle behind the icon: 12% of the colour in light, 22% in dark (spec 5.14).
 * Measured icon contrast on these tints: 4.25:1 to 6.96:1 (icons need 3:1).
 */
@Composable
fun DocumentTypeStyle.containerColor(surface: Color): Color {
    val dark = isDarkThemeActive()
    val tintStrength = if (dark) DARK_TINT else LIGHT_TINT
    val tint = iconColor().copy(alpha = tintStrength)
    return tint.compositeOver(surface)
}

private const val LIGHT_TINT = 0.12f
private const val DARK_TINT = 0.22f

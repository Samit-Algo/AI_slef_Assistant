package com.heybuddy.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The spacing scale and fixed sizes from spec 5.14. Use these instead of writing dp values in screens. */
object Dimens {
    /** Spacing scale: 4, 8, 12, 16, 24, 32, 48. */
    val SpaceXs: Dp = 4.dp
    val SpaceS: Dp = 8.dp
    val SpaceM: Dp = 12.dp
    val SpaceL: Dp = 16.dp
    val SpaceXl: Dp = 24.dp
    val SpaceXxl: Dp = 32.dp
    val SpaceXxxl: Dp = 48.dp

    /** Side padding of every screen. */
    val ScreenPadding: Dp = 24.dp

    /** Height of primary and secondary buttons. */
    val ButtonHeight: Dp = 52.dp

    /** The smallest touch target allowed anywhere (accessibility). */
    val MinTouchTarget: Dp = 48.dp

    /** Border of cards and outlined containers. */
    val BorderWidth: Dp = 1.dp

    /** Size of a document-type badge and the icon inside it. */
    val BadgeSize: Dp = 44.dp
    val BadgeIconSize: Dp = 24.dp

    /** The large icon in the middle of the Unlock, No screen lock and empty screens. */
    val HeroIconCircle: Dp = 88.dp
    val HeroIconSize: Dp = 44.dp

    /** Widest the content gets on tablets and landscape, so lines stay easy to read. */
    val MaxContentWidth: Dp = 560.dp
}

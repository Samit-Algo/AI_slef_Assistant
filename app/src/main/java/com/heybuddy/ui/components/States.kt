package com.heybuddy.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.heybuddy.R
import com.heybuddy.ui.theme.Dimens

/**
 * A large icon on a soft brand-coloured circle, used at the top of calm full-screen messages.
 * Decorative for TalkBack (the title next to it says what it means).
 *
 * @param icon The icon to show.
 * @param modifier Layout changes from the caller.
 * @param tint The icon colour; brand primary by default.
 * @param container The circle colour; primary container by default.
 */
@Composable
fun HeroIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier = modifier
            .size(Dimens.HeroIconCircle)
            .background(color = container, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(Dimens.HeroIconSize))
    }
}

/**
 * A friendly "nothing here yet" message with an optional action (spec 5.14).
 *
 * @param icon The icon to show.
 * @param title One short line, for example "Your vault is empty".
 * @param body What to do next.
 * @param modifier Layout changes from the caller.
 * @param action Optional button below the text.
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        HeroIcon(icon = icon)
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/**
 * A spinner with one line of text, for work that takes a moment (reading, saving, opening).
 * TalkBack announces the text when it appears.
 *
 * @param text What is happening, for example "Saving to your vault…".
 * @param modifier Layout changes from the caller.
 */
@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceXxl)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The three kinds of message banner. */
enum class BannerKind {
    INFO,
    SUCCESS,
    ERROR,
}

/**
 * A rounded message box with an icon: information, success or error (spec 5.14).
 * TalkBack announces it as soon as it appears, so a new error is never missed.
 *
 * @param text The message (simple English that says what to do next).
 * @param kind Which look to use.
 * @param modifier Layout changes from the caller.
 */
@Composable
fun MessageBanner(text: String, kind: BannerKind, modifier: Modifier = Modifier) {
    val style = bannerStyleFor(kind)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = style.container, shape = MaterialTheme.shapes.medium)
            .padding(Dimens.SpaceL)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        verticalAlignment = Alignment.Top,
    ) {
        // Decorative: the text carries the meaning; the colour and icon only support it.
        Icon(painter = painterResource(style.icon), contentDescription = null, tint = style.content)
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = style.content, modifier = Modifier.weight(1f))
    }
}

private class BannerStyle(@DrawableRes val icon: Int, val container: Color, val content: Color)

@Composable
private fun bannerStyleFor(kind: BannerKind): BannerStyle {
    val colors = MaterialTheme.colorScheme
    return when (kind) {
        BannerKind.INFO -> BannerStyle(R.drawable.ic_info, colors.secondaryContainer, colors.onSecondaryContainer)
        BannerKind.SUCCESS -> BannerStyle(R.drawable.ic_check_circle, colors.tertiaryContainer, colors.onTertiaryContainer)
        BannerKind.ERROR -> BannerStyle(R.drawable.ic_error, colors.errorContainer, colors.onErrorContainer)
    }
}

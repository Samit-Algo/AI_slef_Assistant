package com.heybuddy.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heybuddy.ui.theme.Dimens

private val ButtonIconSize = 20.dp

/**
 * The one main action of a screen: filled brand colour, full width, 52 dp high (spec 5.14).
 *
 * @param text The button's label.
 * @param onClick Called on tap.
 * @param modifier Layout changes from the caller.
 * @param enabled False to grey it out.
 * @param icon Optional decorative icon before the label (TalkBack reads the label only).
 * @param sensitive True for actions that must not be tricked by another app's overlay
 *   (Unlock, Save, Delete): taps are ignored while an overlay covers Hey Buddy.
 * @param colors Normally the brand colour; the error colour for destructive actions.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    sensitive: Boolean = false,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    val reportBlockedTouch = LocalObscuredTouchReporter.current
    val touchGuard = if (sensitive) Modifier.rejectObscuredTouches(reportBlockedTouch) else Modifier
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        colors = colors,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.ButtonHeight)
            .pressScale(interactionSource)
            .then(touchGuard),
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * A second choice next to the primary action: outlined, same size as [PrimaryButton].
 *
 * @param text The button's label.
 * @param onClick Called on tap.
 * @param modifier Layout changes from the caller.
 * @param enabled False to grey it out.
 * @param icon Optional decorative icon before the label.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(Dimens.BorderWidth, MaterialTheme.colorScheme.outline),
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().heightIn(min = Dimens.ButtonHeight).pressScale(interactionSource),
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * A low-key action such as Cancel: no border, at least 48 dp tall for easy tapping.
 *
 * @param text The button's label.
 * @param onClick Called on tap.
 * @param modifier Layout changes from the caller.
 */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = Dimens.MinTouchTarget).pressScale(interactionSource),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        // Decorative: the label already says what the button does.
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonIconSize))
        Spacer(modifier = Modifier.width(Dimens.SpaceS))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

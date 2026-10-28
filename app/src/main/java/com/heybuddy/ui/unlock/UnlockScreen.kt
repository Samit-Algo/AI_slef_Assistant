package com.heybuddy.ui.unlock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.heybuddy.R
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.HeroIcon
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.components.shakeOn
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.HeyBuddyTheme
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.motionSpec
import com.heybuddy.ui.theme.performRejectHaptic
import com.heybuddy.ui.theme.softOpeningSpring

/**
 * The Unlock screen: brand icon, app name, one Unlock button, and any message.
 *
 * @param uiState What to show.
 * @param unlockSucceeded True for the short moment after a proven unlock: the lock opens.
 * @param onUnlockClick Called when the user taps Unlock.
 */
@Composable
fun UnlockScreen(uiState: UnlockUiState, unlockSucceeded: Boolean, onUnlockClick: () -> Unit) {
    ScreenScaffold(centerContent = true) {
        LockHeroIcon(open = unlockSucceeded, realFailureCount = uiState.realFailureCount)
        Spacer(modifier = Modifier.height(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.unlock_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceL))
        PrimaryButton(
            text = stringResource(R.string.unlock_button),
            onClick = onUnlockClick,
            enabled = !unlockSucceeded,
            icon = R.drawable.ic_fingerprint,
            sensitive = true,
        )
        if (uiState.showNoFingerprintNote) {
            MessageBanner(text = stringResource(R.string.unlock_note_no_fingerprint), kind = BannerKind.INFO)
        }
        val errorMessage = uiState.errorMessage
        if (errorMessage != null) {
            MessageBanner(text = stringResource(errorMessage), kind = BannerKind.ERROR)
        }
    }
}

/**
 * The lock icon (spec 5.14, item 2). After a proven unlock it swaps to an open lock that springs up
 * slightly. After a REAL failure it shakes gently, with a light reject haptic; a cancel does nothing.
 */
@Composable
private fun LockHeroIcon(open: Boolean, realFailureCount: Int) {
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    // Only failures that happen while this screen is showing count: coming back to Unlock later
    // must not shake for an old failure.
    val countWhenShown = remember { realFailureCount }
    val newFailures = realFailureCount - countWhenShown
    LaunchedEffect(newFailures) {
        if (newFailures > 0) performRejectHaptic(view)
    }
    val scale by animateFloatAsState(
        targetValue = if (open) OPEN_SCALE else 1f,
        animationSpec = softOpeningSpring(reduceMotion),
        label = "lockScale",
    )
    AnimatedContent(
        targetState = open,
        transitionSpec = {
            fadeIn(motionSpec(MotionDurations.UNLOCK_SUCCESS_MILLIS, reduceMotion)) togetherWith
                fadeOut(motionSpec(MotionDurations.UNLOCK_SUCCESS_MILLIS, reduceMotion))
        },
        modifier = Modifier.shakeOn(newFailures),
        label = "lockIcon",
    ) { isOpen ->
        val icon = if (isOpen) R.drawable.ic_lock_open else R.drawable.ic_lock
        HeroIcon(
            icon = icon,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

/** The open lock ends 8 % larger: a small "it worked" lift, not a jump. */
private const val OPEN_SCALE = 1.08f

@Preview(showBackground = true)
@Composable
private fun UnlockScreenPreview() {
    HeyBuddyTheme {
        UnlockScreen(
            uiState = UnlockUiState(errorMessage = R.string.unlock_error_cancelled, showNoFingerprintNote = true),
            unlockSucceeded = false,
            onUnlockClick = {},
        )
    }
}

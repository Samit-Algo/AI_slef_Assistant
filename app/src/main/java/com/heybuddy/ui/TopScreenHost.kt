package com.heybuddy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.motionSpec

/**
 * Shows one top-level screen (onboarding, unlock, vault…). Only OPENING the vault fades in; every
 * other change, above all locking, is instant (spec 5.14 motion rule 2).
 *
 * @param screen The screen to show.
 * @param content Draws one screen.
 */
@Composable
fun TopScreenHost(screen: TopScreen, content: @Composable (TopScreen) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = screen,
        transitionSpec = { topScreenTransition(targetState, reduceMotion) },
        label = "topScreen",
    ) { shown ->
        // Belt and braces for rule 2: the old vault screen may still be in the transition for the
        // frame in which the lock happens; it draws nothing from that moment on.
        val isLockedVault = shown == TopScreen.VAULT && screen != TopScreen.VAULT
        if (!isLockedVault) content(shown)
    }
}

/**
 * Opening the vault fades in gently. Locking is INSTANT: vault content must never stay on screen,
 * not even for a fade, once the vault is locked.
 *
 * @param target The screen being shown.
 * @param reduceMotion True when the phone's "Remove animations" setting is on.
 * @return The transition.
 */
internal fun topScreenTransition(target: TopScreen, reduceMotion: Boolean): ContentTransform {
    // No size animation (sizeTransform = null): every screen fills the window, and Compose's default
    // size spring would keep the OLD screen alive for about 400 ms after the change.
    if (target != TopScreen.VAULT) {
        return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    }
    return ContentTransform(
        targetContentEnter = fadeIn(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion)),
        initialContentExit = fadeOut(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion)),
        sizeTransform = null,
    )
}

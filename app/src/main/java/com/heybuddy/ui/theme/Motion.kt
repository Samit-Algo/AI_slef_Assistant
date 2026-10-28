package com.heybuddy.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** True when the phone's "Remove animations" setting is on. Provided by [HeyBuddyTheme]. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Motion timings from spec 5.14 (approved in Task 1.10m), in milliseconds. Every movement is 150–400 ms. */
object MotionDurations {
    /** Screen changes: a short fade with a slight slide. */
    const val SCREEN_MILLIS = 250

    /** A list item fading in and rising into place. */
    const val LIST_ITEM_MILLIS = 200

    /** The lock opening after a successful unlock, before the vault appears. */
    const val UNLOCK_SUCCESS_MILLIS = 300

    /** A document card growing into the viewer, and back. */
    const val SHARED_ELEMENT_MILLIS = 350

    /** The lock icon's gentle shake after a real unlock failure. */
    const val SHAKE_MILLIS = 300

    /** A button or card sinking while pressed. */
    const val PRESS_MILLIS = 150

    /** The "+" button and dialogs opening. */
    const val POP_IN_MILLIS = 250

    /** The success check mark drawing itself. */
    const val CHECK_MARK_MILLIS = 400

    /** One light sweep across a loading outline (owner decision A: one movement, then a rest). */
    const val SHIMMER_SWEEP_MILLIS = 400

    /** One gentle nudge of an onboarding icon (owner decision A). */
    const val ICON_NUDGE_MILLIS = 300

    /** Onboarding cards sliding to the next card after Next or Back. */
    const val ONBOARDING_SLIDE_MILLIS = 300

    /** Onboarding dots sliding and stretching to the new page. */
    const val PAGE_DOTS_MILLIS = 300

    /** A Settings section opening or closing. */
    const val EXPAND_MILLIS = 250

    /** The whole screen cross-fading to the new theme colours. */
    const val THEME_FADE_MILLIS = 300

    /** The rest between two movements of a loop (owner decision A). Not a movement itself. */
    const val LOOP_REST_MILLIS = 1200

    /** Every movement above, so a test can check the 150–400 ms rule. */
    val ALL_MOVEMENTS: List<Int> = listOf(
        SCREEN_MILLIS,
        LIST_ITEM_MILLIS,
        UNLOCK_SUCCESS_MILLIS,
        SHARED_ELEMENT_MILLIS,
        SHAKE_MILLIS,
        PRESS_MILLIS,
        POP_IN_MILLIS,
        CHECK_MARK_MILLIS,
        SHIMMER_SWEEP_MILLIS,
        ICON_NUDGE_MILLIS,
        ONBOARDING_SLIDE_MILLIS,
        PAGE_DOTS_MILLIS,
        EXPAND_MILLIS,
        THEME_FADE_MILLIS,
    )
}

/** Shortest allowed movement (spec 5.14 motion rule 4). */
const val MIN_MOVEMENT_MILLIS = 150

/** Longest allowed movement (spec 5.14 motion rule 4). */
const val MAX_MOVEMENT_MILLIS = 400

/**
 * Springs used for touch feedback and opening: no bounce (rule 5, "no cartoon bounces"), stiff
 * enough to settle in about 350 ms.
 *
 * @param reduceMotion True when the phone's "Remove animations" setting is on.
 * @return The spring, or an instant change when motion is reduced.
 */
fun <T> calmSpring(reduceMotion: Boolean): FiniteAnimationSpec<T> {
    if (reduceMotion) return snap()
    return spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/**
 * The same spring with the slightest give, for things that "open" (the unlocked lock, "+",
 * dialogs). A ratio this close to 1 overshoots by about 1 %, which reads as soft, not bouncy.
 *
 * @param reduceMotion True when the phone's "Remove animations" setting is on.
 * @return The spring, or an instant change when motion is reduced.
 */
fun <T> softOpeningSpring(reduceMotion: Boolean): FiniteAnimationSpec<T> {
    if (reduceMotion) return snap()
    return spring(dampingRatio = SOFT_OPENING_DAMPING, stiffness = Spring.StiffnessMediumLow)
}

/** Almost no bounce: see [softOpeningSpring]. */
private const val SOFT_OPENING_DAMPING = 0.82f

/**
 * An animation timing that respects "Remove animations": instant when motion is reduced.
 *
 * @param durationMillis The normal duration.
 * @return The animation spec to use.
 */
@Composable
@ReadOnlyComposable
fun <T> motionSpec(durationMillis: Int): FiniteAnimationSpec<T> {
    return motionSpec(durationMillis, LocalReduceMotion.current)
}

/**
 * The same as [motionSpec], for places that are not composable (for example AnimatedContent's
 * transitionSpec). Read [LocalReduceMotion] first and pass it in.
 *
 * @param durationMillis The normal duration.
 * @param reduceMotion True when the phone's "Remove animations" setting is on.
 * @return The animation spec to use.
 */
fun <T> motionSpec(durationMillis: Int, reduceMotion: Boolean): FiniteAnimationSpec<T> {
    if (reduceMotion) return snap()
    return tween(durationMillis)
}

/**
 * How long to wait for a purely decorative animation (0 when motion is reduced).
 *
 * @param durationMillis The normal duration.
 * @return The delay to use.
 */
@Composable
@ReadOnlyComposable
fun motionDelayMillis(durationMillis: Int): Long {
    return if (LocalReduceMotion.current) 0L else durationMillis.toLong()
}

@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { isAnimationOff(context) }
}

// Android's "Remove animations" sets the animator duration scale to 0.
private fun isAnimationOff(context: Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        NORMAL_ANIMATION_SCALE,
    )
    return scale == 0f
}

private const val NORMAL_ANIMATION_SCALE = 1f

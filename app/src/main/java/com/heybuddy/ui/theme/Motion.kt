package com.heybuddy.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** True when the phone's "Remove animations" setting is on. Provided by [HeyBuddyTheme]. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Motion timings from spec 5.14, in milliseconds. */
object MotionDurations {
    /** Screen changes: a short fade with a slight slide. */
    const val SCREEN_MILLIS = 250

    /** A list item fading in and rising into place. */
    const val LIST_ITEM_MILLIS = 200

    /** The lock opening after a successful unlock, before the vault appears. */
    const val UNLOCK_SUCCESS_MILLIS = 300
}

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

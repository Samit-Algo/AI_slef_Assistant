package com.heybuddy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.calmSpring
import com.heybuddy.ui.theme.softOpeningSpring

/** How far a pressed button or card sinks (spec 5.14, item 3). */
private const val PRESSED_SCALE = 0.97f

/** Where the "+" button and dialogs start when they open (spec 5.14, item 3). */
private const val POP_IN_START_SCALE = 0.92f

/** How far the lock moves each way when it shakes (spec 5.14, item 2). */
private val ShakeDistance = 4.dp

/** One shake = 3 times left and right: 6 turning points spread over the shake time. */
private const val SHAKE_TURNS = 6

/** How much an onboarding icon grows at the top of its nudge: small and gentle. */
private const val NUDGE_SCALE = 1.06f

/** The width of the light band in a loading outline, as a share of the outline's width. */
private const val SHIMMER_BAND_FRACTION = 0.6f

/** How strong the light band is: barely there, so it reads as "working", not as decoration. */
private const val SHIMMER_ALPHA = 0.35f

/** One full loop: one movement, then the rest (owner decision A). */
private fun loopCycleMillis(movementMillis: Int): Int = movementMillis + MotionDurations.LOOP_REST_MILLIS

/**
 * Sinks the element slightly while it is pressed, then springs back (spec 5.14, item 3).
 * Nothing moves when "Remove animations" is on.
 *
 * @param interactionSource The same source the clickable element uses, so presses are seen.
 * @return The modifier.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) PRESSED_SCALE else 1f,
        animationSpec = calmSpring(reduceMotion),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Opens the element with a soft spring from 92 % (spec 5.14, item 3), once, when it first appears.
 *
 * @return The modifier.
 */
@Composable
fun Modifier.popIn(): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(if (reduceMotion) 1f else POP_IN_START_SCALE) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, softOpeningSpring(reduceMotion))
    }
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Shakes the element gently 3 times whenever [trigger] goes up (spec 5.14, item 2: real unlock
 * failures only). [trigger] 0 never shakes, so nothing moves when the screen first appears.
 *
 * @param trigger A counter; each new value is one shake.
 * @return The modifier.
 */
@Composable
fun Modifier.shakeOn(trigger: Int): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val distancePixels = with(LocalDensity.current) { ShakeDistance.toPx() }
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduceMotion) return@LaunchedEffect
        offset.snapTo(0f)
        offset.animateTo(0f, shakeKeyframes(distancePixels))
    }
    return this.graphicsLayer { translationX = offset.value }
}

private fun shakeKeyframes(distancePixels: Float) = keyframes {
    durationMillis = MotionDurations.SHAKE_MILLIS
    val step = MotionDurations.SHAKE_MILLIS / (SHAKE_TURNS + 1)
    for (turn in 1..SHAKE_TURNS) {
        val side = if (turn % 2 == 1) -1f else 1f
        side * distancePixels at step * turn
    }
}

/**
 * A soft light band sweeping across the element, then resting (spec 5.14, item 6; owner decision A:
 * one 400 ms sweep, 1.2 s rest). No sweep at all when "Remove animations" is on.
 *
 * @param highlight The colour of the light band (used faintly).
 * @return The modifier.
 */
@Composable
fun Modifier.shimmer(highlight: Color): Modifier {
    if (LocalReduceMotion.current) return this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = moveThenRest(MotionDurations.SHIMMER_SWEEP_MILLIS),
        label = "shimmerProgress",
    )
    return this.drawWithContent {
        drawContent()
        val bandWidth = size.width * SHIMMER_BAND_FRACTION
        // From fully left of the outline to fully right of it, so it enters and leaves softly.
        val bandStart = -bandWidth + progress * (size.width + bandWidth)
        val brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, highlight.copy(alpha = SHIMMER_ALPHA), Color.Transparent),
            startX = bandStart,
            endX = bandStart + bandWidth,
        )
        drawRect(brush = brush, topLeft = Offset.Zero, size = size)
    }
}

/**
 * A small, slow "breath" for an onboarding icon: one 300 ms nudge, then a 1.2 s rest (spec 5.14,
 * item 7; owner decision A). Still when "Remove animations" is on.
 *
 * @return The modifier.
 */
@Composable
fun Modifier.gentleNudgeLoop(): Modifier {
    if (LocalReduceMotion.current) return this
    val transition = rememberInfiniteTransition(label = "nudge")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = nudgeThenRest(),
        label = "nudgeScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 0 → 1 during the movement, then holds at 1 for the rest, then starts again. */
private fun moveThenRest(movementMillis: Int): InfiniteRepeatableSpec<Float> {
    return infiniteRepeatable(
        animation = keyframes {
            durationMillis = loopCycleMillis(movementMillis)
            0f at 0 using LinearEasing
            1f at movementMillis
        },
        repeatMode = RepeatMode.Restart,
    )
}

/** Up to [NUDGE_SCALE] and back within the nudge, then holds still for the rest. */
private fun nudgeThenRest(): InfiniteRepeatableSpec<Float> {
    val nudge = MotionDurations.ICON_NUDGE_MILLIS
    return infiniteRepeatable(
        animation = keyframes {
            durationMillis = loopCycleMillis(nudge)
            1f at 0
            NUDGE_SCALE at nudge / 2
            1f at nudge
        },
        repeatMode = RepeatMode.Restart,
    )
}

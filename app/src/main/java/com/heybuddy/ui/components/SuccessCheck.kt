package com.heybuddy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.performConfirmHaptic

/** The check mark's line, as a share of the circle's size. */
private const val CHECK_STROKE_FRACTION = 0.08f

/** Size of the small check in a success notice. */
private val NoticeCheckSize = 28.dp

/**
 * A check mark that draws itself in a soft circle, with a light confirm haptic as it starts
 * (spec 5.14, item 8). Instant when "Remove animations" is on (the haptic still follows the
 * phone's touch vibration setting). Decorative for TalkBack: the text next to it says what happened.
 *
 * @param size The circle's size.
 * @param modifier Layout changes from the caller.
 * @param tint The check's colour.
 * @param container The circle's colour.
 */
@Composable
fun AnimatedCheckMark(
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.tertiary,
    container: Color = MaterialTheme.colorScheme.tertiaryContainer,
) {
    val reduceMotion = LocalReduceMotion.current
    val view = LocalView.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        performConfirmHaptic(view)
        progress.animateTo(1f, tween(MotionDurations.CHECK_MARK_MILLIS, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier.size(size).background(color = container, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val checkPath = checkMarkPath(this.size.width)
            val drawnPart = partOf(checkPath, progress.value)
            drawPath(
                path = drawnPart,
                color = tint,
                style = Stroke(width = this.size.width * CHECK_STROKE_FRACTION, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * A short success line with a self-drawing check, for example "Name changed" (spec 5.14, item 8).
 * TalkBack announces the text.
 *
 * @param text What just succeeded.
 * @param modifier Layout changes from the caller.
 */
@Composable
fun SuccessNotice(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium)
            .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedCheckMark(size = NoticeCheckSize, container = MaterialTheme.colorScheme.tertiaryContainer)
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** A check inside a square of [side] pixels: short stroke down, long stroke up. */
private fun checkMarkPath(side: Float): Path {
    return Path().apply {
        moveTo(side * CHECK_START_X, side * CHECK_START_Y)
        lineTo(side * CHECK_LOW_X, side * CHECK_LOW_Y)
        lineTo(side * CHECK_END_X, side * CHECK_END_Y)
    }
}

/** The first [fraction] of [path], so the check appears to draw itself. */
private fun partOf(path: Path, fraction: Float): Path {
    val measure = PathMeasure()
    measure.setPath(path, forceClosed = false)
    val part = Path()
    measure.getSegment(0f, measure.length * fraction, part, startWithMoveTo = true)
    return part
}

// The check's three points, as shares of the circle's size (centred, slightly left-heavy like
// the Material check icon).
private const val CHECK_START_X = 0.30f
private const val CHECK_START_Y = 0.52f
private const val CHECK_LOW_X = 0.44f
private const val CHECK_LOW_Y = 0.66f
private const val CHECK_END_X = 0.71f
private const val CHECK_END_Y = 0.38f

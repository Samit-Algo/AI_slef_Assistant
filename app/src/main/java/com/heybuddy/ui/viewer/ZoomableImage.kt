package com.heybuddy.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.heybuddy.util.VIEWER_MAX_ZOOM

/** Zoom used by a double tap. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * A picture you can pinch to zoom (1x to 4x), drag while zoomed, and double tap to zoom in or out.
 * Dragging stops at the picture's edges, so it can never be pushed off screen.
 *
 * @param picture The photo or PDF page.
 * @param contentDescription What TalkBack says for the picture.
 * @param modifier Layout changes from the caller.
 */
@Composable
fun ZoomableImage(picture: Bitmap, contentDescription: String, modifier: Modifier = Modifier) {
    // Zoom resets for each new picture (a new page starts at 1x).
    var scale by remember(picture) { mutableFloatStateOf(1f) }
    var offset by remember(picture) { mutableStateOf(Offset.Zero) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size -> areaSize = size }
            .pointerInput(picture) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, VIEWER_MAX_ZOOM)
                    scale = newScale
                    offset = clampOffset(offset + pan, newScale, areaSize)
                }
            }
            .pointerInput(picture) {
                detectTapGestures(onDoubleTap = {
                    val zoomedIn = scale > 1f
                    scale = if (zoomedIn) 1f else DOUBLE_TAP_ZOOM
                    offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = picture.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** Keeps a zoomed picture's edges on screen: it can move at most half of the extra size each way. */
private fun clampOffset(offset: Offset, scale: Float, area: IntSize): Offset {
    val maxX = area.width * (scale - 1f) / 2f
    val maxY = area.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

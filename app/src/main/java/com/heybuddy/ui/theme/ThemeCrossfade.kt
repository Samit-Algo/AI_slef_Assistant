package com.heybuddy.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize

/**
 * Cross-fades the screen when the theme changes (spec 5.14, item 9), cheaply: it takes ONE picture
 * of the screen as it is, switches the theme at once, and fades that picture out on top. Only the
 * picture's transparency changes per frame, so nothing is rebuilt 60 times a second.
 *
 * @param layer Holds the screen's latest drawing (see [ThemeCrossfadeHost]).
 * @param reduceMotion True when the phone's "Remove animations" setting is on.
 */
class ThemeCrossfade internal constructor(private val layer: GraphicsLayer, private val reduceMotion: Boolean) {

    /** The picture of the old theme, while it fades out; null otherwise. */
    internal var oldThemePicture by mutableStateOf<ImageBitmap?>(null)
        private set

    /** The picture's transparency: 1 = fully covering, 0 = gone. */
    internal val pictureAlpha = Animatable(1f)

    /**
     * Changes the theme with a cross-fade. If the screen goes away meanwhile (for example the vault
     * locks), the coroutine is cancelled and the picture is dropped at once.
     *
     * @param applyTheme Switches the theme (for example saves the new choice).
     */
    suspend fun change(applyTheme: () -> Unit) {
        if (reduceMotion || layer.size == IntSize.Zero) {
            applyTheme()
            return
        }
        val picture = layer.toImageBitmap()
        pictureAlpha.snapTo(1f)
        oldThemePicture = picture
        applyTheme()
        try {
            pictureAlpha.animateTo(0f, tween(MotionDurations.THEME_FADE_MILLIS))
        } finally {
            oldThemePicture = null
        }
    }
}

/** Set by [ThemeCrossfadeHost]; null elsewhere, where the theme simply changes at once. */
val LocalThemeCrossfade = staticCompositionLocalOf<ThemeCrossfade?> { null }

/**
 * Keeps the latest drawing of [content], so [ThemeCrossfade] can picture it, and shows the fading
 * picture on top during a theme change. Placed around the vault screens only: when the vault locks,
 * this disappears with them, so no picture can outlive a lock.
 *
 * @param content The screens.
 */
@Composable
fun ThemeCrossfadeHost(content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val reduceMotion = LocalReduceMotion.current
    val crossfade = remember(layer, reduceMotion) { ThemeCrossfade(layer, reduceMotion) }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) {
            CompositionLocalProvider(LocalThemeCrossfade provides crossfade) {
                content()
            }
        }
        val picture = crossfade.oldThemePicture
        if (picture != null) {
            // Decorative: a picture of what was just on screen, gone within 300 ms.
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = crossfade.pictureAlpha.value },
            )
        }
    }
}

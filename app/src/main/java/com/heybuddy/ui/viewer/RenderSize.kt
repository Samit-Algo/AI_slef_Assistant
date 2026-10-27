package com.heybuddy.ui.viewer

import com.heybuddy.util.VIEWER_MAX_PIXELS
import com.heybuddy.util.VIEWER_MAX_SIDE_PIXELS
import com.heybuddy.util.VIEWER_RENDER_SCALE
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A size in pixels.
 *
 * @property width Width in pixels.
 * @property height Height in pixels.
 */
data class PixelSize(val width: Int, val height: Int) {
    /** Number of pixels. */
    val pixels: Long
        get() = width.toLong() * height.toLong()
}

/**
 * How big to draw a PDF page: [VIEWER_RENDER_SCALE] times the screen width (sharp when zoomed),
 * shrunk if needed so it never passes [VIEWER_MAX_SIDE_PIXELS] or [VIEWER_MAX_PIXELS].
 *
 * @param pageWidth The page's width in PDF points.
 * @param pageHeight The page's height in PDF points.
 * @param screenWidthPixels The screen width in pixels.
 * @return The bitmap size to render into (at least 1 x 1).
 */
fun pdfRenderSize(pageWidth: Int, pageHeight: Int, screenWidthPixels: Int): PixelSize {
    val safePageWidth = max(pageWidth, 1)
    val safePageHeight = max(pageHeight, 1)
    val targetWidth = screenWidthPixels.toDouble() * VIEWER_RENDER_SCALE
    val scale = targetWidth / safePageWidth
    val wanted = PixelSize(
        width = (safePageWidth * scale).toInt(),
        height = (safePageHeight * scale).toInt(),
    )
    return capToLimits(wanted)
}

/**
 * The power-of-two sample size to decode a photo with: the largest picture that fits within
 * [VIEWER_RENDER_SCALE] times the screen and the viewer limits. 1 means full size.
 *
 * @param imageWidth The photo's width in pixels.
 * @param imageHeight The photo's height in pixels.
 * @param screenLongestPixels The screen's longest side in pixels.
 * @return 1, 2, 4, 8, … (ImageDecoder decodes at 1/sampleSize of the size).
 */
fun imageSampleSize(imageWidth: Int, imageHeight: Int, screenLongestPixels: Int): Int {
    val maxSide = minOf(screenLongestPixels * VIEWER_RENDER_SCALE, VIEWER_MAX_SIDE_PIXELS)
    var sampleSize = 1
    while (!fits(imageWidth / sampleSize, imageHeight / sampleSize, maxSide)) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun fits(width: Int, height: Int, maxSide: Int): Boolean {
    val longest = max(width, height)
    val pixels = width.toLong() * height.toLong()
    return longest <= maxSide && pixels <= VIEWER_MAX_PIXELS
}

/** Shrinks a size (keeping its shape) until it is within both viewer limits. */
private fun capToLimits(size: PixelSize): PixelSize {
    val longest = max(size.width, size.height).toDouble()
    val sideScale = if (longest > VIEWER_MAX_SIDE_PIXELS) VIEWER_MAX_SIDE_PIXELS / longest else 1.0
    val afterSide = scaled(size, sideScale)
    val pixelScale = if (afterSide.pixels > VIEWER_MAX_PIXELS) sqrt(VIEWER_MAX_PIXELS.toDouble() / afterSide.pixels) else 1.0
    return scaled(afterSide, pixelScale)
}

private fun scaled(size: PixelSize, factor: Double): PixelSize {
    val width = (size.width * factor).toInt().coerceAtLeast(1)
    val height = (size.height * factor).toInt().coerceAtLeast(1)
    return PixelSize(width, height)
}

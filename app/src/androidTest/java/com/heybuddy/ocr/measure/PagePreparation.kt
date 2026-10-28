package com.heybuddy.ocr.measure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.heybuddy.util.READING_LONG_SIDE_PIXELS

/**
 * Image preparations for the reading measurement ONLY (test code). Task 2.2 measured them and none
 * helped clearly (scaling and contrast even lost some names and dates), so the app reads pages as
 * decoded. Kept here so future measurements (camera in Task 2.4, Tamil in Task 2.3) can compare
 * again. Every step returns a NEW picture; the caller recycles both when done.
 */
object PagePreparation {

    /**
     * Scales the picture so its long side is [READING_LONG_SIDE_PIXELS]: big enough for small print,
     * small enough to read quickly.
     *
     * @param picture The page.
     * @return A scaled copy (a plain copy if it already has that size).
     */
    fun scaledForReading(picture: Bitmap): Bitmap {
        val longSide = maxOf(picture.width, picture.height)
        val factor = READING_LONG_SIDE_PIXELS.toFloat() / longSide
        val width = (picture.width * factor).toInt().coerceAtLeast(1)
        val height = (picture.height * factor).toInt().coerceAtLeast(1)
        return picture.scale(width, height, filter = true)
    }

    /**
     * Grey with stretched contrast: the darkest 1 % becomes black and the lightest 1 % white, which
     * helps dim or washed-out photos.
     *
     * @param picture The page.
     * @return A grey, contrast-stretched copy.
     */
    fun greyWithStretchedContrast(picture: Bitmap): Bitmap {
        val (darkest, lightest) = brightnessRange(picture)
        val spread = (lightest - darkest).coerceAtLeast(MIN_SPREAD)
        val gain = FULL_SCALE / spread
        val offset = -darkest * gain
        // Grey by the usual brightness weights, then stretched: one colour matrix does both.
        val red = GREY_RED * gain
        val green = GREY_GREEN * gain
        val blue = GREY_BLUE * gain
        val matrix = ColorMatrix(
            floatArrayOf(
                red, green, blue, 0f, offset,
                red, green, blue, 0f, offset,
                red, green, blue, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val result = createBitmap(picture.width, picture.height)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(result).drawBitmap(picture, 0f, 0f, paint)
        return result
    }

    /** The 1st and 99th percentile of brightness (0–255), from a sample of pixels. */
    private fun brightnessRange(picture: Bitmap): Pair<Float, Float> {
        val counts = IntArray(BRIGHTNESS_LEVELS)
        val stepX = (picture.width / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (picture.height / SAMPLE_GRID).coerceAtLeast(1)
        var total = 0
        for (y in 0 until picture.height step stepY) {
            for (x in 0 until picture.width step stepX) {
                counts[brightnessOf(picture.getPixel(x, y))]++
                total++
            }
        }
        return Pair(percentile(counts, total, LOW_PERCENT), percentile(counts, total, HIGH_PERCENT))
    }

    private fun brightnessOf(color: Int): Int {
        val red = (color shr RED_SHIFT) and BYTE_MASK
        val green = (color shr GREEN_SHIFT) and BYTE_MASK
        val blue = color and BYTE_MASK
        return (red * GREY_RED + green * GREY_GREEN + blue * GREY_BLUE).toInt().coerceIn(0, BRIGHTNESS_LEVELS - 1)
    }

    private fun percentile(counts: IntArray, total: Int, percent: Float): Float {
        val wanted = total * percent
        var seen = 0
        for (level in counts.indices) {
            seen += counts[level]
            if (seen >= wanted) return level.toFloat()
        }
        return (BRIGHTNESS_LEVELS - 1).toFloat()
    }

    // Standard brightness weights for red, green and blue (ITU-R BT.601).
    private const val GREY_RED = 0.299f
    private const val GREY_GREEN = 0.587f
    private const val GREY_BLUE = 0.114f

    private const val FULL_SCALE = 255f
    private const val BRIGHTNESS_LEVELS = 256
    private const val BYTE_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    /** Never stretch more than this much (a nearly blank page would otherwise turn into noise). */
    private const val MIN_SPREAD = 32f

    /** About 200 x 200 sample points: plenty for a brightness histogram, quick to collect. */
    private const val SAMPLE_GRID = 200
    private const val LOW_PERCENT = 0.01f
    private const val HIGH_PERCENT = 0.99f
}

package com.heybuddy.ui.camera

import com.heybuddy.util.CAMERA_BLURRY_BELOW
import com.heybuddy.util.CAMERA_DARK_BELOW
import com.heybuddy.util.CAMERA_HINT_WINDOW_FRAMES

/**
 * Gives the light value (luma, 0 to 255) of one pixel of a picture.
 */
fun interface LumaReader {

    /**
     * @param x Column, from 0.
     * @param y Row, from 0.
     * @return The light value at that pixel, 0 (black) to 255 (white).
     */
    fun luma(x: Int, y: Int): Int
}

/**
 * How good a picture is for reading.
 *
 * @property brightness Average light value, 0 (black) to 255 (white).
 * @property sharpness Variance of the Laplacian: high when edges (letters) are crisp, low when blurry.
 */
data class FrameQuality(val brightness: Double, val sharpness: Double)

/** The two measured checks behind the camera hints (Task 2.4). Pure maths, no Android. */
object CaptureQuality {

    /**
     * Measures a picture. The Laplacian is the standard blur measure: for each pixel, 4 times its
     * value minus its four neighbours; a sharp picture has many strong values, so a high variance.
     *
     * @param width Picture width in pixels.
     * @param height Picture height in pixels.
     * @param reader Gives each pixel's light value.
     * @return Brightness and sharpness.
     */
    fun measure(width: Int, height: Int, reader: LumaReader): FrameQuality {
        if (width < MIN_SIZE || height < MIN_SIZE) return FrameQuality(brightness = 0.0, sharpness = 0.0)
        var lightSum = 0.0
        var laplacianSum = 0.0
        var laplacianSquareSum = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val centre = reader.luma(x, y)
                val neighbours = reader.luma(x - 1, y) + reader.luma(x + 1, y) + reader.luma(x, y - 1) + reader.luma(x, y + 1)
                val laplacian = (NEIGHBOURS * centre - neighbours).toDouble()
                lightSum += centre
                laplacianSum += laplacian
                laplacianSquareSum += laplacian * laplacian
                count++
            }
        }
        val mean = laplacianSum / count
        val variance = laplacianSquareSum / count - mean * mean
        return FrameQuality(brightness = lightSum / count, sharpness = variance)
    }

    private const val MIN_SIZE = 3
    private const val NEIGHBOURS = 4
}

/** The hints the camera shows. They are advice only: the shutter always works. */
enum class CaptureHint {
    /** "More light needed". */
    MORE_LIGHT,

    /** "Hold steady" (while aiming). */
    HOLD_STEADY,

    /** "Too blurry, try again" (after taking the photo). */
    TOO_BLURRY,
}

/**
 * Turns measured preview frames into a steady live hint: it looks at the middle value of the last
 * few frames, so one dark or shaky frame does not make the hint flicker.
 */
class LiveHintTracker {

    private val recent = ArrayDeque<FrameQuality>()

    /**
     * @param quality The newest frame's measurement.
     * @return The hint to show now, or null for none.
     */
    fun add(quality: FrameQuality): CaptureHint? {
        recent.addLast(quality)
        if (recent.size > CAMERA_HINT_WINDOW_FRAMES) recent.removeFirst()
        val brightness = middleOf(recent.map { frame -> frame.brightness })
        val sharpness = middleOf(recent.map { frame -> frame.sharpness })
        return when {
            brightness < CAMERA_DARK_BELOW -> CaptureHint.MORE_LIGHT
            sharpness < CAMERA_BLURRY_BELOW -> CaptureHint.HOLD_STEADY
            else -> null
        }
    }

    /** Forgets all frames (for example after a retake). */
    fun reset() {
        recent.clear()
    }

    private fun middleOf(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}

/** The check of a photo that was just taken. */
object PhotoCheck {

    /**
     * @param quality The photo's measurement.
     * @return The hint for the "Retake or keep" screen, or null if the photo looks fine.
     */
    fun hintFor(quality: FrameQuality): CaptureHint? {
        return when {
            quality.brightness < CAMERA_DARK_BELOW -> CaptureHint.MORE_LIGHT
            quality.sharpness < CAMERA_BLURRY_BELOW -> CaptureHint.TOO_BLURRY
            else -> null
        }
    }
}

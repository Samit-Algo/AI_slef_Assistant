package com.heybuddy.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.heybuddy.util.CAMERA_ANALYSIS_WIDTH_PIXELS
import com.heybuddy.util.CAMERA_JPEG_QUALITY
import com.heybuddy.util.CAMERA_PAGE_LONG_SIDE_PIXELS
import com.heybuddy.util.CAMERA_REVIEW_LONG_SIDE_PIXELS
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A photo just taken, in memory only (never a file, never the gallery; spec 7.5). The caller must
 * either hand [jpeg] on (it is then the new owner) or call [wipe].
 *
 * @property jpeg The page as JPEG: upright, at most [CAMERA_PAGE_LONG_SIDE_PIXELS] on the long side,
 *   quality [CAMERA_JPEG_QUALITY], and WITHOUT any photo metadata (no time, phone or location).
 * @property review A smaller picture for the "Retake or keep" screen.
 * @property quality The photo's brightness and sharpness.
 */
class CapturedPhoto(val jpeg: ByteArray, val review: Bitmap, val quality: FrameQuality) {

    /** Overwrites the bytes and frees the picture. */
    fun wipe() {
        jpeg.fill(0)
        review.recycle()
    }
}

/** Turns the camera's own JPEG into a [CapturedPhoto] (Task 2.4). */
object PhotoProcessor {

    /**
     * Decodes, turns upright, shrinks if needed and re-saves the photo. Re-saving also drops the
     * camera's metadata (EXIF), because a fresh JPEG is written from the pixels only. The caller
     * still owns [cameraJpeg] and wipes it.
     *
     * @param cameraJpeg The JPEG from the camera.
     * @param rotationDegrees How far the camera says the picture must turn to be upright.
     * @return The processed photo.
     * @throws IllegalArgumentException If the bytes are not a picture.
     */
    fun process(cameraJpeg: ByteArray, rotationDegrees: Int): CapturedPhoto {
        val decoded = BitmapFactory.decodeByteArray(cameraJpeg, 0, cameraJpeg.size)
            ?: throw IllegalArgumentException("The camera did not give a picture")
        val page = uprightAndLimited(decoded, rotationDegrees)
        try {
            val jpeg = encode(page)
            val review = scaledToLongSide(page, CAMERA_REVIEW_LONG_SIDE_PIXELS)
            val quality = measure(page)
            return CapturedPhoto(jpeg, review, quality)
        } finally {
            page.recycle()
        }
    }

    /** Measures a picture the same way as the preview frames: on a copy as wide as they are. */
    fun measure(picture: Bitmap): FrameQuality {
        val factor = CAMERA_ANALYSIS_WIDTH_PIXELS.toFloat() / max(picture.width, picture.height)
        val small = if (factor < 1f) picture.scale((picture.width * factor).roundToInt(), (picture.height * factor).roundToInt()) else picture
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val width = small.width
        val quality = CaptureQuality.measure(width, small.height) { x, y -> lumaOf(pixels[y * width + x]) }
        if (small !== picture) small.recycle()
        return quality
    }

    private fun uprightAndLimited(decoded: Bitmap, rotationDegrees: Int): Bitmap {
        val longSide = max(decoded.width, decoded.height)
        val factor = if (longSide > CAMERA_PAGE_LONG_SIDE_PIXELS) CAMERA_PAGE_LONG_SIDE_PIXELS.toFloat() / longSide else 1f
        if (factor == 1f && rotationDegrees == 0) return decoded
        val matrix = Matrix()
        matrix.postScale(factor, factor)
        matrix.postRotate(rotationDegrees.toFloat())
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    private fun encode(page: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        page.compress(Bitmap.CompressFormat.JPEG, CAMERA_JPEG_QUALITY, output)
        return output.toByteArray()
    }

    private fun scaledToLongSide(picture: Bitmap, longSide: Int): Bitmap {
        val factor = longSide.toFloat() / max(picture.width, picture.height)
        if (factor >= 1f) return picture.copy(Bitmap.Config.ARGB_8888, false)
        return picture.scale((picture.width * factor).roundToInt(), (picture.height * factor).roundToInt())
    }

    /** The usual light formula (ITU-R BT.601), the same "Y" that camera preview frames carry. */
    private fun lumaOf(pixel: Int): Int {
        val red = (pixel shr RED_SHIFT) and CHANNEL
        val green = (pixel shr GREEN_SHIFT) and CHANNEL
        val blue = pixel and CHANNEL
        return (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / WEIGHT_TOTAL
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL = 0xFF
    private const val RED_WEIGHT = 299
    private const val GREEN_WEIGHT = 587
    private const val BLUE_WEIGHT = 114
    private const val WEIGHT_TOTAL = 1000
}

package com.heybuddy.ocr.fakedocs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.random.Random

/**
 * Makes a clean fake card look like a quick phone photo (Task 2.2): on a table, slightly tilted,
 * with a shadow, blur or low light. Fixed random seeds, so every run gives the same images.
 */
object PhotoEffects {

    /**
     * @param card The clean card.
     * @param look Which effect.
     * @return The card itself for CLEAN, otherwise a new picture.
     */
    fun apply(card: Bitmap, look: PhotoLook): Bitmap {
        return when (look) {
            PhotoLook.CLEAN -> card
            PhotoLook.TILTED -> onTable(card, tilted = true)
            PhotoLook.SHADOW -> withShadow(onTable(card, tilted = false))
            PhotoLook.BLURRED -> blurred(onTable(card, tilted = false))
            PhotoLook.LOW_LIGHT -> inLowLight(onTable(card, tilted = false))
        }
    }

    /** The card lying on a table; tilted means about 4 degrees with a little perspective. */
    private fun onTable(card: Bitmap, tilted: Boolean): Bitmap {
        val photo = createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT)
        photo.eraseColor(TABLE_COLOUR)
        val left = (PHOTO_WIDTH - card.width) / 2f
        val top = (PHOTO_HEIGHT - card.height) / 2f
        val right = left + card.width
        val bottom = top + card.height
        val source = floatArrayOf(0f, 0f, card.width.toFloat(), 0f, card.width.toFloat(), card.height.toFloat(), 0f, card.height.toFloat())
        val target = if (tilted) {
            // About 4°: the right side is 110 px higher than the left over 1600 px, and the far edge
            // is slightly shorter than the near one, as when a phone is not held quite flat.
            floatArrayOf(left + 20f, top + 70f, right - 10f, top - 40f, right + 15f, bottom - 60f, left, bottom + 40f)
        } else {
            floatArrayOf(left, top, right, top, right, bottom, left, bottom)
        }
        val matrix = Matrix()
        matrix.setPolyToPoly(source, 0, target, 0, CORNERS)
        Canvas(photo).drawBitmap(card, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return photo
    }

    /** A soft shadow from the top-left, as from a hand or the phone above the card. */
    private fun withShadow(photo: Bitmap): Bitmap {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, photo.width * SHADOW_REACH, photo.height * SHADOW_REACH,
                Color.argb(SHADOW_ALPHA, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
        Canvas(photo).drawRect(0f, 0f, photo.width.toFloat(), photo.height.toFloat(), paint)
        return photo
    }

    /** Slightly out of focus: shrunk and enlarged again with smoothing. */
    private fun blurred(photo: Bitmap): Bitmap {
        val small = photo.scale(photo.width / BLUR_FACTOR, photo.height / BLUR_FACTOR, filter = true)
        val result = small.scale(photo.width, photo.height, filter = true)
        small.recycle()
        photo.recycle()
        return result
    }

    /** Dim, slightly warm light with sensor noise. */
    private fun inLowLight(photo: Bitmap): Bitmap {
        val dim = createBitmap(photo.width, photo.height)
        val matrix = ColorMatrix(
            floatArrayOf(
                LOW_LIGHT_RED, 0f, 0f, 0f, 0f,
                0f, LOW_LIGHT_GREEN, 0f, 0f, 0f,
                0f, 0f, LOW_LIGHT_BLUE, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        Canvas(dim).drawBitmap(photo, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        photo.recycle()
        addNoise(dim)
        return dim
    }

    private fun addNoise(photo: Bitmap) {
        val random = Random(NOISE_SEED)
        val row = IntArray(photo.width)
        for (y in 0 until photo.height) {
            photo.getPixels(row, 0, photo.width, 0, y, photo.width, 1)
            for (x in row.indices) {
                val change = random.nextInt(-NOISE_LEVEL, NOISE_LEVEL + 1)
                row[x] = Color.rgb(
                    (Color.red(row[x]) + change).coerceIn(0, MAX_CHANNEL),
                    (Color.green(row[x]) + change).coerceIn(0, MAX_CHANNEL),
                    (Color.blue(row[x]) + change).coerceIn(0, MAX_CHANNEL),
                )
            }
            photo.setPixels(row, 0, photo.width, 0, y, photo.width, 1)
        }
    }

    private const val PHOTO_WIDTH = 1800
    private const val PHOTO_HEIGHT = 1300
    private val TABLE_COLOUR = Color.rgb(132, 118, 100)
    private const val CORNERS = 4
    private const val SHADOW_ALPHA = 150
    private const val SHADOW_REACH = 0.6f
    private const val BLUR_FACTOR = 3
    private const val LOW_LIGHT_RED = 0.48f
    private const val LOW_LIGHT_GREEN = 0.42f
    private const val LOW_LIGHT_BLUE = 0.36f
    private const val NOISE_SEED = 2026
    private const val NOISE_LEVEL = 18
    private const val MAX_CHANNEL = 255
}

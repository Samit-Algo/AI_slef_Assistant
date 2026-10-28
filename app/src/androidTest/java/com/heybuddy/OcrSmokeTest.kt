package com.heybuddy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Task 2.1 smoke test: the bundled ML Kit recognizer works inside Hey Buddy even with its
 * network/logging parts removed, and with no network permission at all (so OCR provably needs no
 * internet). Uses made-up sample text drawn in memory; real OCR testing comes in Task 2.2.
 */
@RunWith(AndroidJUnit4::class)
class OcrSmokeTest {

    @Test
    fun readsEnglishAndHindiSampleTextOnThePhone() {
        val picture = drawSampleText()
        val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(picture, NO_ROTATION)), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val text = result.text

            // "It runs and reads", not accuracy: accuracy is measured in Task 2.2 (the first run read
            // "HEY BUODY2026" for "HEY BUDDY 2026", so exact English is not asserted here).
            assertTrue("English not read: \"$text\"", text.contains(ENGLISH_WORD) && text.contains(ENGLISH_NUMBER))
            assertTrue("Hindi not read: \"$text\"", text.contains(HINDI_SAMPLE))
        } finally {
            recognizer.close()
            picture.recycle()
        }
    }

    /** Black sample text on white, large and straight: this test is about "it runs", not accuracy. */
    private fun drawSampleText(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        canvas.drawText(ENGLISH_SAMPLE, MARGIN, FIRST_LINE_Y, paint)
        canvas.drawText(HINDI_SAMPLE, MARGIN, SECOND_LINE_Y, paint)
        return bitmap
    }

    private companion object {
        const val ENGLISH_SAMPLE = "HEY BUDDY 2026"
        const val ENGLISH_WORD = "HEY"
        const val ENGLISH_NUMBER = "2026"
        const val HINDI_SAMPLE = "नमस्ते"
        const val NO_ROTATION = 0
        const val TIMEOUT_SECONDS = 30L
        const val WIDTH = 1200
        const val HEIGHT = 400
        const val TEXT_SIZE = 80f
        const val MARGIN = 60f
        const val FIRST_LINE_Y = 150f
        const val SECOND_LINE_Y = 300f
    }
}

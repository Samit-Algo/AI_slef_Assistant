package com.heybuddy.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads text from one picture that is already in memory (spec 7.2). Several engines can sit behind
 * this: ML Kit now, the Tamil engine in Task 2.3.
 */
interface TextReader {

    /**
     * Reads one picture. The picture is not changed or recycled here (the caller owns it).
     *
     * @param picture A decrypted page, in memory only.
     * @return The lines found, in reading order.
     */
    suspend fun read(picture: Bitmap): RecognizedPage

    /** Frees the engine. The reader must not be used afterwards. */
    fun close()
}

/**
 * ML Kit text recognition, fully on the phone with its bundled models (spec 7.4). The Devanagari
 * recognizer reads Hindi and English together; Task 2.2 measured that the separate English-only
 * model found nothing more and turned Hindi into look-alike garbage, so it is not used.
 */
class MlKitTextReader : TextReader {

    // Made on first use, so creating the reader (for example in AppContainer) costs nothing.
    private val recognizer: TextRecognizer by lazy { createRecognizer() }

    override suspend fun read(picture: Bitmap): RecognizedPage {
        val image = InputImage.fromBitmap(picture, NO_ROTATION)
        val result = recognizer.process(image).awaitResult()
        return RecognizedPage(inReadingOrder(linesOf(result)))
    }

    override fun close() {
        recognizer.close()
    }

    private fun linesOf(result: Text): List<RecognizedLine> {
        val lines = mutableListOf<RecognizedLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val bounds = line.boundingBox
                val box = bounds?.let { rect -> LineBox(rect.left, rect.top, rect.right, rect.bottom) }
                lines.add(RecognizedLine(line.text, box, line.confidence))
            }
        }
        return lines
    }

    private companion object {
        /** Pictures are always passed upright (decoding already applied the photo's rotation). */
        const val NO_ROTATION = 0

        fun createRecognizer(): TextRecognizer {
            val options = DevanagariTextRecognizerOptions.Builder().build()
            return TextRecognition.getClient(options)
        }
    }
}

/** Waits for an ML Kit task without blocking a thread. */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> continuation.resume(value) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}

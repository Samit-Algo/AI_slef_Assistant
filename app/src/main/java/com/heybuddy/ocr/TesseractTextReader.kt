package com.heybuddy.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.heybuddy.util.OCR_MODELS_FOLDER_NAME
import com.heybuddy.util.TAMIL_MODEL_ASSET
import com.heybuddy.util.TAMIL_MODEL_LANGUAGE
import com.heybuddy.util.TamilText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads Tamil with Tesseract and its official "fast" Tamil model, fully on the phone (spec 7.4a,
 * owner's choice after the Task 2.3 measurement). Every line goes through [TamilText.clean].
 *
 * Tesseract can only load its model from a real file, so on first use the model is copied out of
 * the app's assets into noBackupFilesDir. That file is the public model, never anyone's data.
 *
 * @param appContext The application context (for the assets and the model folder).
 */
class TesseractTextReader(private val appContext: Context) : TextReader {

    // Tesseract is not safe to use from two threads at once.
    private val lock = Mutex()
    private var engine: TessBaseAPI? = null

    override suspend fun read(picture: Bitmap): RecognizedPage = withContext(Dispatchers.Default) {
        lock.withLock {
            val api = engine ?: openEngine().also { opened -> engine = opened }
            readWith(api, picture)
        }
    }

    override fun close() {
        engine?.recycle()
        engine = null
    }

    private fun readWith(api: TessBaseAPI, picture: Bitmap): RecognizedPage {
        api.setImage(picture)
        try {
            // Runs the recognition; the lines are then taken one by one with their boxes.
            api.getUTF8Text()
            return RecognizedPage(inReadingOrder(linesOf(api)))
        } finally {
            // Drops Tesseract's own copy of the picture and its results.
            api.clear()
        }
    }

    private fun linesOf(api: TessBaseAPI): List<RecognizedLine> {
        val lines = mutableListOf<RecognizedLine>()
        val iterator = api.resultIterator ?: return lines
        try {
            iterator.begin()
            do {
                val line = lineAt(iterator)
                if (line != null) lines.add(line)
            } while (iterator.next(LINE_LEVEL))
        } finally {
            iterator.delete()
        }
        return lines
    }

    private fun lineAt(iterator: com.googlecode.tesseract.android.ResultIterator): RecognizedLine? {
        val raw = iterator.getUTF8Text(LINE_LEVEL) ?: return null
        val text = TamilText.clean(raw).trim()
        if (text.isEmpty()) return null
        val rect = iterator.getBoundingRect(LINE_LEVEL)
        val confidence = iterator.confidence(LINE_LEVEL) / PERCENT
        return RecognizedLine(text, LineBox(rect.left, rect.top, rect.right, rect.bottom), confidence)
    }

    private fun openEngine(): TessBaseAPI {
        val modelsFolder = File(appContext.noBackupFilesDir, OCR_MODELS_FOLDER_NAME)
        copyModelIfNeeded(File(modelsFolder, TESSDATA_FOLDER))
        val api = TessBaseAPI()
        val loaded = api.init(modelsFolder.path, TAMIL_MODEL_LANGUAGE)
        check(loaded) { "The Tamil reading model could not be loaded" }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        return api
    }

    /** Copies the model out of the assets once (again if a copy was cut short, so the size differs). */
    private fun copyModelIfNeeded(tessdataFolder: File) {
        val modelFile = File(tessdataFolder, "$TAMIL_MODEL_LANGUAGE.traineddata")
        val expectedSize = appContext.assets.openFd(TAMIL_MODEL_ASSET).use { descriptor -> descriptor.length }
        if (modelFile.length() == expectedSize) return
        tessdataFolder.mkdirs()
        val partFile = File(tessdataFolder, "${modelFile.name}.part")
        appContext.assets.open(TAMIL_MODEL_ASSET).use { input ->
            partFile.outputStream().use { output -> input.copyTo(output) }
        }
        check(partFile.renameTo(modelFile)) { "The Tamil reading model could not be prepared" }
    }

    private companion object {
        /** Tesseract always looks for its models in a folder with exactly this name. */
        const val TESSDATA_FOLDER = "tessdata"
        const val LINE_LEVEL = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE

        /** Tesseract gives confidence from 0 to 100; lines use 0 to 1. */
        const val PERCENT = 100f
    }
}

package com.heybuddy.ocr

import android.graphics.Bitmap

/**
 * The app's reader (spec 7.4a): ML Kit for English and Hindi, Tesseract for Tamil, merged per page.
 * The two engines run one after the other, not together, so a budget phone holds only one engine's
 * working memory at a time.
 *
 * @param mainReader ML Kit (English and Hindi).
 * @param tamilReader Tesseract (Tamil).
 */
class MergedTextReader(private val mainReader: TextReader, private val tamilReader: TextReader) : TextReader {

    override suspend fun read(picture: Bitmap): RecognizedPage {
        val mainLines = mainReader.read(picture).lines
        val tamilLines = tamilReader.read(picture).lines
        val merged = LineMerge.merge(mainLines, tamilLines)
        return RecognizedPage(inReadingOrder(merged))
    }

    override fun close() {
        mainReader.close()
        tamilReader.close()
    }
}

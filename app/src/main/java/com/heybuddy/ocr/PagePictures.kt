package com.heybuddy.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.heybuddy.data.OpenedPage
import com.heybuddy.util.MAX_PDF_PAGES_TO_READ
import com.heybuddy.util.READING_LONG_SIDE_PIXELS
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Turns one opened (decrypted, verified) vault page into pictures for reading, in memory only
 * (spec 7.3-2). The decrypted copy is released here, straight after use: photo bytes are wiped, a
 * PDF's verified copy in view_tmp (rule 6) is deleted.
 */
object PagePictures {

    /**
     * The pictures of one page: one for a photo, one per PDF page (at most [MAX_PDF_PAGES_TO_READ]).
     *
     * @param opened The decrypted page. Always released by this call, even on failure.
     * @return The pictures. The caller recycles them when done.
     * @throws IOException If the page cannot be decoded.
     */
    fun of(opened: OpenedPage): List<Bitmap> {
        return when (opened) {
            is OpenedPage.Image -> try {
                listOf(decodePhoto(opened.bytes.bytes))
            } finally {
                opened.bytes.wipe()
            }
            is OpenedPage.Pdf -> try {
                drawPdfPages(opened)
            } finally {
                opened.file.delete()
            }
        }
    }

    private fun decodePhoto(photoBytes: ByteArray): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(photoBytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // A plain (software) picture: the OCR engine reads its pixels directly.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSizeFor(maxOf(info.size.width, info.size.height)))
            decoder.setOnPartialImageListener { false }
        }
    }

    private fun drawPdfPages(opened: OpenedPage.Pdf): List<Bitmap> {
        val descriptor = ParcelFileDescriptor.open(opened.file.file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pictures = mutableListOf<Bitmap>()
        descriptor.use {
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES_TO_READ)
                for (index in 0 until pageCount) {
                    renderer.openPage(index).use { page -> pictures.add(drawPdfPage(page)) }
                }
            }
        }
        return pictures
    }

    private fun drawPdfPage(page: PdfRenderer.Page): Bitmap {
        val factor = READING_LONG_SIDE_PIXELS.toFloat() / maxOf(page.width, page.height)
        val bitmap = createBitmap((page.width * factor).toInt().coerceAtLeast(1), (page.height * factor).toInt().coerceAtLeast(1))
        // PDF pages are transparent by default; white paper reads best.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        return bitmap
    }

    /** The largest power-of-two step that keeps the long side at or above the reading size. */
    private fun sampleSizeFor(longSide: Int): Int {
        var sampleSize = 1
        while (longSide / (sampleSize * 2) >= READING_LONG_SIDE_PIXELS) {
            sampleSize *= 2
        }
        return sampleSize
    }
}

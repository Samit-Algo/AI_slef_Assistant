package com.heybuddy.ui.adddocument

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.heybuddy.util.THUMBNAIL_MAX_PIXELS
import java.io.IOException
import java.nio.ByteBuffer

/** The image could not be decoded: its first bytes look right, but the rest is damaged. */
class DamagedImageException(cause: Throwable) : IOException("Image could not be decoded", cause)

/** Builds the small page thumbnails shown before saving. Nothing is ever written to our storage. */
object FilePreview {

    /**
     * Decodes a picked image, scaled down so its longest side is at most [THUMBNAIL_MAX_PIXELS].
     *
     * Decoding fully also checks the image: a file that only fakes the first bytes fails here.
     * ImageDecoder applies the photo's rotation, so the preview is the right way up.
     *
     * @param imageBytes The picked file's bytes (in memory).
     * @return The preview.
     * @throws DamagedImageException If the image cannot be fully decoded.
     */
    fun decodeImage(imageBytes: ByteArray): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
        try {
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longestSide = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize(sampleSizeFor(longestSide))
                // A partly decoded image means the file is damaged; never accept it.
                decoder.setOnPartialImageListener { false }
            }
        } catch (decodeFailed: IOException) {
            throw DamagedImageException(decodeFailed)
        } catch (decodeFailed: IllegalArgumentException) {
            throw DamagedImageException(decodeFailed)
        }
    }

    /**
     * Draws the first page of a picked PDF straight from the picker's copy (read-only).
     *
     * @param contentResolver Used to open the picker's file.
     * @param pdfUri The picked file.
     * @return The first page, or null if this PDF cannot be previewed (for example it has a
     *   password, or the picker does not allow random access). Saving is still allowed then.
     */
    fun renderFirstPdfPage(contentResolver: ContentResolver, pdfUri: Uri): Bitmap? {
        return try {
            val descriptor = contentResolver.openFileDescriptor(pdfUri, READ_ONLY) ?: return null
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page -> renderPage(page) }
            }
        } catch (notPreviewable: IOException) {
            null
        } catch (notPreviewable: SecurityException) {
            null
        } catch (notPreviewable: IllegalArgumentException) {
            null
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = THUMBNAIL_MAX_PIXELS.toFloat() / maxOf(page.width, page.height)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        // PDF pages are transparent by default; paint white so the page looks like paper.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /** The smallest power of two that brings [longestSide] down to the thumbnail size. */
    private fun sampleSizeFor(longestSide: Int): Int {
        var sampleSize = 1
        while (longestSide / (sampleSize * 2) >= THUMBNAIL_MAX_PIXELS) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private const val READ_ONLY = "r"
}

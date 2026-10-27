package com.heybuddy.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Draws the pages of one verified PDF (from view_tmp), one page at a time.
 *
 * Android's PdfRenderer can draw only one page at a time and must not be closed while it draws,
 * so every use goes through one lock and one background thread.
 *
 * @param file The verified PDF in view_tmp.
 * @throws IOException If the PDF cannot be opened (for example it has a password).
 */
class PdfPageRenderer(file: File) {

    private val descriptor: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = openRenderer(descriptor)
    private val rendererLock = Any()
    private var closed = false

    // One thread at a time: PdfRenderer is not safe to use from two threads at once.
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Number of pages in the PDF. */
    val pageCount: Int = renderer.pageCount

    /**
     * Draws one page on white "paper", sized by [pdfRenderSize] (sharp when zoomed, but capped).
     *
     * @param pageIndex Which page, starting at 0.
     * @param screenWidthPixels The screen width, used to pick a sharp size.
     * @return The page picture, or null if the renderer was closed meanwhile (viewer closed/locked).
     */
    suspend fun renderPage(pageIndex: Int, screenWidthPixels: Int): Bitmap? {
        return withContext(renderDispatcher) {
            synchronized(rendererLock) {
                if (closed) return@withContext null
                renderer.openPage(pageIndex).use { page -> drawPage(page, screenWidthPixels) }
            }
        }
    }

    /**
     * Closes the PDF. Waits for a page that is being drawn right now, then frees everything.
     * Safe to call more than once.
     */
    fun close() {
        synchronized(rendererLock) {
            if (closed) return
            closed = true
            renderer.close()
            descriptor.close()
        }
    }

    private fun drawPage(page: PdfRenderer.Page, screenWidthPixels: Int): Bitmap {
        val size = pdfRenderSize(page.width, page.height, screenWidthPixels)
        val bitmap = createBitmap(size.width, size.height)
        // PDF pages are transparent by default; paint white so the page looks like paper.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun openRenderer(fileDescriptor: ParcelFileDescriptor): PdfRenderer {
        try {
            return PdfRenderer(fileDescriptor)
        } catch (cannotOpen: IOException) {
            fileDescriptor.close()
            throw cannotOpen
        } catch (cannotOpen: SecurityException) {
            // A password-protected PDF.
            fileDescriptor.close()
            throw IOException("PDF cannot be opened", cannotOpen)
        }
    }
}

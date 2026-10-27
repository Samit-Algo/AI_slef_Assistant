package com.heybuddy.ui.viewer

import com.heybuddy.util.VIEWER_MAX_PIXELS
import com.heybuddy.util.VIEWER_MAX_SIDE_PIXELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the viewer's size rules: sharp (2x the screen) but never over 4096 px or 8 million pixels. */
class RenderSizeTest {

    @Test
    fun aNormalA4PageIsDrawnAtTwiceTheScreenWidth() {
        // A4 is 595 x 842 points; a 1080-pixel-wide phone gives a 2160-pixel-wide page.
        val size = pdfRenderSize(pageWidth = A4_WIDTH, pageHeight = A4_HEIGHT, screenWidthPixels = PHONE_WIDTH)

        assertEquals(2 * PHONE_WIDTH, size.width)
        assertTrue(size.height in 3050..3060)
    }

    @Test
    fun aVeryTallPageIsKeptUnderTheLongestSideLimit() {
        val size = pdfRenderSize(pageWidth = A4_WIDTH, pageHeight = A4_HEIGHT * 10, screenWidthPixels = PHONE_WIDTH)

        assertTrue("longest side ${maxOf(size.width, size.height)}", maxOf(size.width, size.height) <= VIEWER_MAX_SIDE_PIXELS)
        assertTrue(size.pixels <= VIEWER_MAX_PIXELS)
    }

    @Test
    fun aHugeSquarePageIsKeptUnderThePixelLimit() {
        // 4096 x 4096 would be 16.8 million pixels: over the 8 million limit.
        val size = pdfRenderSize(pageWidth = HUGE, pageHeight = HUGE, screenWidthPixels = 4000)

        assertTrue("pixels ${size.pixels}", size.pixels <= VIEWER_MAX_PIXELS)
        assertTrue(maxOf(size.width, size.height) <= VIEWER_MAX_SIDE_PIXELS)
    }

    @Test
    fun aBrokenPageWithNoSizeStillGivesAUsablePicture() {
        val size = pdfRenderSize(pageWidth = 0, pageHeight = 0, screenWidthPixels = PHONE_WIDTH)

        assertTrue(size.width >= 1 && size.height >= 1)
    }

    @Test
    fun aSmallPhotoIsDecodedAtFullSize() {
        assertEquals(1, imageSampleSize(imageWidth = 1200, imageHeight = 800, screenLongestPixels = PHONE_HEIGHT))
    }

    @Test
    fun aBigCameraPhotoIsHalvedToStayWithinTheLimits() {
        // 4000 x 3000 = 12 million pixels: too many. Half size (2000 x 1500) fits.
        assertEquals(2, imageSampleSize(imageWidth = 4000, imageHeight = 3000, screenLongestPixels = PHONE_HEIGHT))
    }

    @Test
    fun aGiantPhotoIsShrunkUntilItFits() {
        val sampleSize = imageSampleSize(imageWidth = 20_000, imageHeight = 15_000, screenLongestPixels = PHONE_HEIGHT)
        val width = 20_000 / sampleSize
        val height = 15_000 / sampleSize

        assertTrue(maxOf(width, height) <= VIEWER_MAX_SIDE_PIXELS)
        assertTrue(width.toLong() * height <= VIEWER_MAX_PIXELS)
    }

    private companion object {
        const val A4_WIDTH = 595
        const val A4_HEIGHT = 842
        const val PHONE_WIDTH = 1080
        const val PHONE_HEIGHT = 2400
        const val HUGE = 14_400
    }
}

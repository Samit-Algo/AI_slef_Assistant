package com.heybuddy.ui.camera

import com.heybuddy.util.CAMERA_BLURRY_BELOW
import com.heybuddy.util.CAMERA_DARK_BELOW
import com.heybuddy.util.CAMERA_HINT_WINDOW_FRAMES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brightness and sharpness maths behind the camera hints, on made-up pictures (Task 2.4). */
class CaptureQualityTest {

    private val size = 64

    @Test
    fun brightnessIsTheAverageLightValue() {
        val grey = CaptureQuality.measure(size, size) { _, _ -> 100 }
        assertEquals(100.0, grey.brightness, 0.001)
    }

    @Test
    fun aFlatPictureHasNoSharpness() {
        val flat = CaptureQuality.measure(size, size) { _, _ -> 200 }
        assertEquals(0.0, flat.sharpness, 0.001)
    }

    @Test
    fun crispEdgesAreSharperThanSoftOnes() {
        // Black and white stripes, like printed text, against the same stripes blurred.
        val crisp = CaptureQuality.measure(size, size) { x, _ -> if ((x / 4) % 2 == 0) 0 else 255 }
        val soft = CaptureQuality.measure(size, size) { x, _ -> softStripe(x) }
        assertTrue("crisp ${crisp.sharpness} soft ${soft.sharpness}", crisp.sharpness > soft.sharpness * 10)
    }

    @Test
    fun tooSmallPicturesMeasureAsNothing() {
        val tiny = CaptureQuality.measure(2, 2) { _, _ -> 255 }
        assertEquals(FrameQuality(0.0, 0.0), tiny)
    }

    @Test
    fun darkFramesAskForMoreLight() {
        val tracker = LiveHintTracker()
        var hint: CaptureHint? = null
        repeat(CAMERA_HINT_WINDOW_FRAMES) { hint = tracker.add(FrameQuality(CAMERA_DARK_BELOW - 10, CAMERA_BLURRY_BELOW * 10)) }
        assertEquals(CaptureHint.MORE_LIGHT, hint)
    }

    @Test
    fun blurryFramesAskToHoldSteady() {
        val tracker = LiveHintTracker()
        var hint: CaptureHint? = null
        repeat(CAMERA_HINT_WINDOW_FRAMES) { hint = tracker.add(FrameQuality(CAMERA_DARK_BELOW + 80, CAMERA_BLURRY_BELOW / 2)) }
        assertEquals(CaptureHint.HOLD_STEADY, hint)
    }

    @Test
    fun oneBadFrameDoesNotMakeTheHintFlicker() {
        val tracker = LiveHintTracker()
        val good = FrameQuality(CAMERA_DARK_BELOW + 80, CAMERA_BLURRY_BELOW * 10)
        repeat(CAMERA_HINT_WINDOW_FRAMES) { tracker.add(good) }
        val afterOneDarkFrame = tracker.add(FrameQuality(0.0, 0.0))
        assertNull(afterOneDarkFrame)
    }

    @Test
    fun aGoodPhotoNeedsNoHintAndABadOneDoes() {
        assertNull(PhotoCheck.hintFor(FrameQuality(CAMERA_DARK_BELOW + 80, CAMERA_BLURRY_BELOW * 10)))
        assertEquals(CaptureHint.TOO_BLURRY, PhotoCheck.hintFor(FrameQuality(CAMERA_DARK_BELOW + 80, CAMERA_BLURRY_BELOW / 2)))
        assertEquals(CaptureHint.MORE_LIGHT, PhotoCheck.hintFor(FrameQuality(CAMERA_DARK_BELOW / 2, CAMERA_BLURRY_BELOW * 10)))
    }

    /** A stripe pattern with a slow ramp between black and white. */
    private fun softStripe(x: Int): Int {
        val phase = x % 16
        val rising = if (phase < 8) phase else 16 - phase
        return rising * 255 / 8
    }
}

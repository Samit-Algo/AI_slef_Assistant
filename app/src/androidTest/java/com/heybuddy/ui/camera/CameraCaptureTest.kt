package com.heybuddy.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.view.Surface
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.MainActivity
import com.heybuddy.data.TestVault
import com.heybuddy.security.requirePhoneUnlocked
import com.heybuddy.ui.adddocument.PrepareResult
import com.heybuddy.util.CAMERA_PAGE_LONG_SIDE_PIXELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * The in-app camera on a real phone (Task 2.4): photos go to memory only and then into an
 * encrypted pending page; no metadata survives; preview frames are always closed; locking or
 * leaving closes the camera and drops any photo; FLAG_SECURE covers the preview. Points the camera
 * at whatever is in front of the phone; nothing it sees is kept.
 */
@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val vault = TestVault(context, name = "camera")
    private val owner = TestLifecycleOwner()

    @Before
    fun setUp() {
        requirePhoneUnlocked()
        // The owner's Realme phone does not let the PC grant permissions (pm grant is refused), so
        // the camera must have been allowed once in the app itself.
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        assertTrue("Allow the camera for Hey Buddy first: Add document, Take photo, Continue, Allow.", granted)
        vault.deleteAll()
        vault.unlock()
        instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            if (owner.registry.currentState.isAtLeast(Lifecycle.State.CREATED)) owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        vault.deleteAll()
    }

    @Test
    fun theAnalyzerClosesEveryFrameRightAfterMeasuring() {
        val measured = mutableListOf<FrameQuality>()
        val analyzer = QualityAnalyzer { quality -> measured.add(quality) }
        val good = FakeFrame(frameWidth = 32, frameHeight = 24)
        analyzer.analyze(good)
        assertTrue("A measured frame must be closed", good.closed)
        assertEquals(1, measured.size)

        // Even when measuring fails, the frame is closed.
        val broken = FakeFrame(frameWidth = 32, frameHeight = 24, bufferBytes = 4)
        runCatching { analyzer.analyze(broken) }
        assertTrue("A frame that failed to measure must still be closed", broken.closed)
    }

    @Test
    fun theRealCameraKeepsDeliveringFramesSoNoneIsHeldBack() = runBlocking {
        // CameraX stops sending frames when earlier ones are not closed; steady frames prove they are.
        val frames = AtomicInteger()
        val session = CameraSession(context)
        try {
            openWithPreview(session, QualityAnalyzer { frames.incrementAndGet() })
            Thread.sleep(FRAME_WATCH_MILLIS)
            assertTrue("Only ${frames.get()} frames measured", frames.get() >= MIN_FRAMES)
        } finally {
            withContext(Dispatchers.Main) { session.release() }
        }
    }

    @Test
    fun aRealPhotoGoesStraightIntoAnEncryptedPageAndNowhereElse() = runBlocking {
        val mediaBefore = ownMediaCount()
        val filesBefore = appFiles()
        val session = CameraSession(context)
        val photo = try {
            openWithPreview(session, QualityAnalyzer {})
            val shot = session.takePhoto()
            try {
                assertTrue("The camera's own JPEG has metadata", hasCameraMetadata(shot.jpeg))
                PhotoProcessor.process(shot.jpeg, shot.rotationDegrees)
            } finally {
                shot.wipe()
            }
        } finally {
            withContext(Dispatchers.Main) { session.release() }
        }
        val pageBytes = photo.jpeg.size
        assertFalse("The page must have no metadata", hasCameraMetadata(photo.jpeg))
        assertTrue("Long side at most $CAMERA_PAGE_LONG_SIDE_PIXELS", longSideOf(photo.jpeg) <= CAMERA_PAGE_LONG_SIDE_PIXELS)
        photo.review.recycle()

        val result = vault.pagePreparer.prepareCaptured(vault.repository.newDocumentId(), photo.jpeg)

        assertTrue("Got $result", result is PrepareResult.Prepared)
        assertTrue("The photo bytes must be wiped", photo.jpeg.all { byte -> byte == 0.toByte() })
        assertEquals("Nothing may reach MediaStore or the gallery", mediaBefore, ownMediaCount())
        val newFiles = appFiles() - filesBefore
        for (file in newFiles) {
            assertTrue("Unexpected new file ${file.name}", file.name.endsWith(".bin.pending") || isDatabaseFile(file))
            assertFalse("${file.name} is a plain JPEG", startsLikeJpeg(file))
        }
        reportPageSize(pageBytes)
    }

    @Test
    fun aPhotoIsUprightAndShrunkAndLosesItsMetadata() {
        val cameraJpeg = jpegWithMetadata(width = 4000, height = 3000)

        val photo = PhotoProcessor.process(cameraJpeg, rotationDegrees = 90)

        val bounds = boundsOf(photo.jpeg)
        assertEquals("Turned upright: tall", true, bounds.second > bounds.first)
        assertEquals(CAMERA_PAGE_LONG_SIDE_PIXELS, bounds.second)
        assertFalse(hasCameraMetadata(photo.jpeg))
        photo.wipe()
    }

    @Test
    fun lockingClosesTheCameraAndDropsThePhotoAtOnce() = runBlocking {
        val viewModel = withContext(Dispatchers.Main) { CameraViewModel(vault.lockState, CameraSession(context)) }
        withContext(Dispatchers.Main) {
            viewModel.onAccessChecked(CameraAccess.GRANTED)
            viewModel.openCamera(owner)
        }
        provideSurface(viewModel.surfaceRequest)
        withContext(Dispatchers.Main) { viewModel.onShutterClick() }
        waitFor("review") { viewModel.uiState.value.stage == CameraStage.REVIEW }
        assertNotNull(viewModel.uiState.value.review)

        withContext(Dispatchers.Main) { vault.lock() }

        waitFor("reset") { viewModel.uiState.value.stage == CameraStage.CHECKING }
        assertNull("The photo must be gone", viewModel.uiState.value.review)
        assertNull(withContext(Dispatchers.Main) { viewModel.takeKeptPhoto() })
        waitFor("camera closed") { allCamerasClosed() }
    }

    @Test
    fun aPhotoThatFinishesAfterTheLockIsThrownAway() = runBlocking {
        val viewModel = withContext(Dispatchers.Main) { CameraViewModel(vault.lockState, CameraSession(context)) }
        withContext(Dispatchers.Main) {
            viewModel.onAccessChecked(CameraAccess.GRANTED)
            viewModel.openCamera(owner)
        }
        provideSurface(viewModel.surfaceRequest)
        Thread.sleep(CAMERA_WARM_UP_MILLIS)

        withContext(Dispatchers.Main) {
            viewModel.onShutterClick()
            vault.lock()
        }
        Thread.sleep(LATE_PHOTO_WAIT_MILLIS)

        assertTrue(viewModel.uiState.value.stage != CameraStage.REVIEW)
        assertNull(withContext(Dispatchers.Main) { viewModel.takeKeptPhoto() })
        waitFor("camera closed") { allCamerasClosed() }
    }

    @Test
    fun leavingTheScreenReleasesTheCamera() = runBlocking {
        val viewModel = withContext(Dispatchers.Main) { CameraViewModel(vault.lockState, CameraSession(context)) }
        withContext(Dispatchers.Main) {
            viewModel.onAccessChecked(CameraAccess.GRANTED)
            viewModel.openCamera(owner)
        }
        provideSurface(viewModel.surfaceRequest)
        waitFor("camera open") { !allCamerasClosed() }

        withContext(Dispatchers.Main) { viewModel.onLeave() }

        waitFor("camera closed") { allCamerasClosed() }
        assertNull(viewModel.surfaceRequest.value)
    }

    /** With the app's FLAG_SECURE window, a screenshot of the live camera preview shows nothing. */
    @Test
    fun aScreenshotOfTheLivePreviewShowsNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val session = CameraSession(context)
            scenario.onActivity { activity ->
                activity.setContent {
                    val request by session.surfaceRequest.collectAsState()
                    CameraScreen(CameraUiState(stage = CameraStage.LIVE), request, noActions())
                }
            }
            scenario.onActivity { activity -> runBlocking { session.open(activity, QualityAnalyzer {}) } }
            Thread.sleep(PREVIEW_SETTLE_MILLIS)

            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            instrumentation.runOnMainSync { session.release() }
            // Some phones refuse the screenshot completely, which is just as good.
            if (screenshot != null) {
                val middle = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                assertEquals("Expected black, got #%06X".format(middle and 0xFFFFFF), Color.BLACK, middle)
            }
        }
    }

    // ---- Helpers ----

    private suspend fun openWithPreview(session: CameraSession, analyzer: QualityAnalyzer) {
        withContext(Dispatchers.Main) { session.open(owner, analyzer) }
        provideSurface(session.surfaceRequest)
        Thread.sleep(CAMERA_WARM_UP_MILLIS)
    }

    /** In the app the preview widget gives CameraX a surface; here an off-screen one does. */
    private fun provideSurface(requests: StateFlow<SurfaceRequest?>) {
        var waited = 0L
        var current = requests.value
        while (current == null && waited < WAIT_LIMIT_MILLIS) {
            Thread.sleep(POLL_MILLIS)
            waited += POLL_MILLIS
            current = requests.value
        }
        val surfaceRequest = checkNotNull(current) { "The camera never asked for a preview surface" }
        val texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(surfaceRequest.resolution.width, surfaceRequest.resolution.height)
        val surface = Surface(texture)
        surfaceRequest.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
            surface.release()
            texture.release()
        }
    }

    private fun allCamerasClosed(): Boolean {
        var closed = false
        instrumentation.runOnMainSync {
            val provider = ProcessCameraProvider.getInstance(context).get()
            closed = provider.availableCameraInfos.all { info ->
                val type = info.cameraState.value?.type
                type == null || type == CameraState.Type.CLOSED
            }
        }
        return closed
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        var waited = 0L
        while (!condition()) {
            assertTrue("Timed out waiting for $what", waited < WAIT_LIMIT_MILLIS)
            Thread.sleep(POLL_MILLIS)
            waited += POLL_MILLIS
        }
    }

    /** Images in MediaStore that Hey Buddy itself added (it must never add any). */
    private fun ownMediaCount(): Int {
        val columns = arrayOf(MediaStore.Images.Media._ID)
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, null, null, null).use { cursor ->
            return cursor?.count ?: 0
        }
    }

    private fun appFiles(): Set<File> {
        val roots = listOf(context.dataDir, context.externalCacheDir, context.getExternalFilesDir(null)).filterNotNull()
        return roots.flatMap { root -> root.walkTopDown().filter { file -> file.isFile }.toList() }.toSet()
    }

    private fun isDatabaseFile(file: File): Boolean = file.path.contains("/databases/") || file.path.contains("/shared_prefs/")

    private fun startsLikeJpeg(file: File): Boolean {
        if (file.length() < JPEG_MAGIC.size) return false
        val start = file.inputStream().use { input -> input.readNBytes(JPEG_MAGIC.size) }
        return start.contentEquals(JPEG_MAGIC)
    }

    private fun hasCameraMetadata(jpeg: ByteArray): Boolean {
        val exif = ExifInterface(ByteArrayInputStream(jpeg))
        val tags = listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_GPS_LATITUDE)
        return tags.any { tag -> exif.getAttribute(tag) != null }
    }

    /** A made-up "camera" JPEG carrying a phone name and a location, like a real camera's. */
    private fun jpegWithMetadata(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        bitmap.recycle()
        // EXIF can only be written to a file; this test file is deleted straight away.
        val file = File(context.cacheDir, "exif_test.jpg")
        try {
            file.writeBytes(output.toByteArray())
            val exif = ExifInterface(file.path)
            exif.setAttribute(ExifInterface.TAG_MAKE, "SampleMaker")
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "13/1,4/1,0/1")
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun boundsOf(jpeg: ByteArray): Pair<Int, Int> {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        return Pair(options.outWidth, options.outHeight)
    }

    private fun longSideOf(jpeg: ByteArray): Int {
        val (width, height) = boundsOf(jpeg)
        return max(width, height)
    }

    /** The owner asked for the typical page size after capture; reported in the test output. */
    private fun reportPageSize(bytes: Int) {
        instrumentation.sendStatus(0, Bundle().apply { putString("cameraPageBytes", bytes.toString()) })
    }

    private fun noActions(): CameraActions = CameraActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    /** A lifecycle that the test controls, standing in for the screen's. */
    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    /** A made-up preview frame (grey), which records whether it was closed. */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private class FakeFrame(private val frameWidth: Int, private val frameHeight: Int, bufferBytes: Int = frameWidth * frameHeight) : ImageProxy {
        var closed = false
        private val pixels = ByteBuffer.allocate(bufferBytes).apply {
            while (hasRemaining()) put(GREY)
            rewind()
        }
        private val plane = object : ImageProxy.PlaneProxy {
            override fun getRowStride(): Int = frameWidth
            override fun getPixelStride(): Int = 1
            override fun getBuffer(): ByteBuffer = pixels
        }

        override fun close() {
            closed = true
        }

        override fun getCropRect(): Rect = Rect(0, 0, frameWidth, frameHeight)
        override fun setCropRect(rect: Rect?) = Unit
        override fun getFormat(): Int = android.graphics.ImageFormat.YUV_420_888
        override fun getHeight(): Int = frameHeight
        override fun getWidth(): Int = frameWidth
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(plane)
        override fun getImageInfo(): ImageInfo = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp(): Long = 0L
            override fun getRotationDegrees(): Int = 0
            override fun getSensorToBufferTransformMatrix(): android.graphics.Matrix = android.graphics.Matrix()
            override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
        }
        override fun getImage(): android.media.Image? = null

        private companion object {
            const val GREY: Byte = 120
        }
    }

    private companion object {
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        const val JPEG_QUALITY = 90
        const val FRAME_WATCH_MILLIS = 4_000L
        const val MIN_FRAMES = 20
        const val CAMERA_WARM_UP_MILLIS = 1_500L
        const val LATE_PHOTO_WAIT_MILLIS = 4_000L
        const val PREVIEW_SETTLE_MILLIS = 2_000L
        const val WAIT_LIMIT_MILLIS = 10_000L
        const val POLL_MILLIS = 100L
    }
}

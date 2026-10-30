package com.heybuddy.ui.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.heybuddy.util.CAMERA_ANALYSIS_HEIGHT_PIXELS
import com.heybuddy.util.CAMERA_ANALYSIS_WIDTH_PIXELS
import com.heybuddy.util.CAMERA_CAPTURE_HEIGHT_PIXELS
import com.heybuddy.util.CAMERA_CAPTURE_WIDTH_PIXELS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Measures preview frames for the live hints. Every frame is CLOSED as soon as it is measured
 * (owner rule, Task 2.4): no frame is kept, copied or queued here. Only two numbers leave.
 *
 * @param onMeasured Gets each frame's brightness and sharpness.
 */
class QualityAnalyzer(private val onMeasured: (FrameQuality) -> Unit) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val quality = try {
            measure(image)
        } finally {
            image.close()
        }
        onMeasured(quality)
    }

    private fun measure(image: ImageProxy): FrameQuality {
        // Plane 0 of a camera frame (YUV) is the light value of every pixel.
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        return CaptureQuality.measure(image.width, image.height) { x, y ->
            buffer.get(y * rowStride + x * pixelStride).toInt() and LUMA_MASK
        }
    }

    private companion object {
        const val LUMA_MASK = 0xFF
    }
}

/**
 * The camera's own JPEG, in memory, and how far it must turn to be upright. The holder wipes it.
 *
 * @property jpeg The photo as the camera made it (with its metadata; never saved like this).
 * @property rotationDegrees How far to turn it.
 */
class CameraShot(val jpeg: ByteArray, val rotationDegrees: Int) {

    /** Overwrites the bytes. */
    fun wipe() {
        jpeg.fill(0)
    }
}

/**
 * What the opened camera can do.
 *
 * @property hasTorch True if the phone has a flash that can stay on as a torch.
 */
data class CameraFeatures(val hasTorch: Boolean)

/**
 * CameraX for the capture screen (spec 7.5): preview, preview-frame measuring, and taking a photo
 * straight into memory with ImageCapture's in-memory callback. Never a file, never the gallery.
 *
 * @param appContext The application context.
 */
class CameraSession(private val appContext: Context) {

    private val mutableSurfaceRequest = MutableStateFlow<SurfaceRequest?>(null)

    /** Where the preview must be drawn, or null while the camera is closed. */
    val surfaceRequest: StateFlow<SurfaceRequest?> = mutableSurfaceRequest.asStateFlow()

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    /**
     * Opens the camera (the back one, or any camera on phones without one). The camera follows
     * [owner]: it pauses when the app is not visible.
     *
     * @param owner The screen's lifecycle.
     * @param analyzer Measures preview frames.
     * @return What the camera can do.
     */
    suspend fun open(owner: LifecycleOwner, analyzer: ImageAnalysis.Analyzer): CameraFeatures {
        val cameraProvider = ProcessCameraProvider.awaitInstance(appContext)
        provider = cameraProvider
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request -> mutableSurfaceRequest.value = request }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selectorFor(Size(CAMERA_ANALYSIS_WIDTH_PIXELS, CAMERA_ANALYSIS_HEIGHT_PIXELS)))
            // Only the newest frame is ever looked at; older ones are dropped by CameraX unread.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor, analyzer)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(selectorFor(Size(CAMERA_CAPTURE_WIDTH_PIXELS, CAMERA_CAPTURE_HEIGHT_PIXELS)))
            .build()
        imageCapture = capture
        cameraProvider.unbindAll()
        val selector = chooseCamera(cameraProvider)
        val opened = cameraProvider.bindToLifecycle(owner, selector, preview, analysis, capture)
        camera = opened
        return CameraFeatures(hasTorch = opened.cameraInfo.hasFlashUnit())
    }

    /**
     * Turns the torch on or off (ignored if there is none or the camera is closed).
     *
     * @param on True for on.
     */
    fun setTorch(on: Boolean) {
        val opened = camera ?: return
        if (!opened.cameraInfo.hasFlashUnit()) return
        opened.cameraControl.enableTorch(on)
    }

    /**
     * Takes a photo straight into memory.
     *
     * @return The camera's JPEG. The caller wipes it.
     * @throws ImageCaptureException If the camera could not take it.
     * @throws IllegalStateException If the camera is not open.
     */
    suspend fun takePhoto(): CameraShot {
        val capture = imageCapture ?: throw IllegalStateException("The camera is not open")
        return suspendCancellableCoroutine { continuation ->
            val callback = object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val shot = try {
                        copyOut(image)
                    } finally {
                        image.close()
                    }
                    // Cancelled meanwhile (the vault locked): nobody will take it, so wipe it here.
                    if (continuation.isActive) continuation.resume(shot) else shot.wipe()
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
            capture.takePicture(ContextCompat.getMainExecutor(appContext), callback)
        }
    }

    /**
     * Closes the camera at once: preview, measuring and capture stop, the torch goes off. Safe to
     * call more than once.
     */
    fun close() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
        mutableSurfaceRequest.value = null
    }

    /** Closes the camera and stops the measuring thread for good. */
    fun release() {
        close()
        analysisExecutor.shutdown()
    }

    private fun copyOut(image: ImageProxy): CameraShot {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return CameraShot(bytes, image.imageInfo.rotationDegrees)
    }

    private fun chooseCamera(cameraProvider: ProcessCameraProvider): CameraSelector {
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        return if (cameraProvider.hasCamera(back)) back else CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun selectorFor(size: Size): ResolutionSelector {
        val strategy = ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        return ResolutionSelector.Builder().setResolutionStrategy(strategy).build()
    }
}

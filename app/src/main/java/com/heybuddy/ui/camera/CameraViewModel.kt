package com.heybuddy.ui.camera

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.camera.core.SurfaceRequest
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heybuddy.R
import com.heybuddy.security.LockStatus
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which step the camera screen is on. */
enum class CameraStage {
    /** Checking the permission (one frame, plain black, so no wrong screen flashes up). */
    CHECKING,

    /** The permission is not given (see [CameraUiState.access]). */
    NEEDS_ACCESS,

    /** Showing the preview and the shutter. */
    LIVE,

    /** Taking and preparing the photo. */
    TAKING,

    /** "Retake or keep". */
    REVIEW,
}

/** Shape of the guide frame. */
enum class FrameShape {
    /** An ID card (Aadhaar, PAN, licence). */
    CARD,

    /** An A4 page (certificates). */
    PAGE,
}

/**
 * What the camera screen shows.
 *
 * @property stage The current step.
 * @property access The permission state (meaningful in [CameraStage.NEEDS_ACCESS]).
 * @property liveHint The hint while aiming, or null.
 * @property hasTorch True if the torch button is shown.
 * @property torchOn True while the torch is on.
 * @property frameShape The guide frame's shape.
 * @property review The photo on the "Retake or keep" screen, or null.
 * @property reviewHint The hint about that photo, or null if it looks fine.
 * @property errorMessage A problem to show, or null.
 */
data class CameraUiState(
    val stage: CameraStage = CameraStage.CHECKING,
    val access: CameraAccess = CameraAccess.ASK_WITH_EXPLANATION,
    val liveHint: CaptureHint? = null,
    val hasTorch: Boolean = false,
    val torchOn: Boolean = false,
    val frameShape: FrameShape = FrameShape.CARD,
    val review: Bitmap? = null,
    val reviewHint: CaptureHint? = null,
    @StringRes val errorMessage: Int? = null,
)

/**
 * Runs the in-app camera (Task 2.4). The photo lives only in memory: it is handed on with
 * [takeKeptPhoto] or wiped (retake, close, and AT ONCE when the vault locks).
 *
 * @param lockState The app's lock: locking closes the camera and drops any photo immediately.
 * @param session CameraX.
 */
class CameraViewModel(
    private val lockState: VaultLockState,
    private val session: CameraSession,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(CameraUiState())

    /** The current screen state. */
    val uiState: StateFlow<CameraUiState> = mutableUiState.asStateFlow()

    /** Where the preview must be drawn, or null while the camera is closed. */
    val surfaceRequest: StateFlow<SurfaceRequest?> = session.surfaceRequest

    private val hintTracker = LiveHintTracker()

    // The photo on the "Retake or keep" screen. Only touched on the main thread.
    private var photo: CapturedPhoto? = null
    private var takingJob: Job? = null

    init {
        viewModelScope.launch {
            lockState.status.filter { status -> status == LockStatus.LOCKED }.collect { dropEverything() }
        }
    }

    /**
     * Records the permission state found by the screen.
     *
     * @param access The state.
     */
    fun onAccessChecked(access: CameraAccess) {
        val current = mutableUiState.value
        if (access == CameraAccess.GRANTED) {
            val waiting = current.stage == CameraStage.CHECKING || current.stage == CameraStage.NEEDS_ACCESS
            if (waiting) mutableUiState.value = current.copy(stage = CameraStage.LIVE, access = access)
            return
        }
        // A re-check on returning to the app only knows "not granted"; it must not replace Android's
        // real answer ("denied" or "don't ask again") with the first explanation again.
        val alreadyAnswered = current.access == CameraAccess.DENIED || current.access == CameraAccess.DENIED_FOR_GOOD
        if (access == CameraAccess.ASK_WITH_EXPLANATION && alreadyAnswered) return
        mutableUiState.value = current.copy(stage = CameraStage.NEEDS_ACCESS, access = access)
    }

    /**
     * Opens the camera for the live preview.
     *
     * @param owner The screen's lifecycle.
     */
    suspend fun openCamera(owner: LifecycleOwner) {
        if (lockState.status.value == LockStatus.LOCKED) return
        val features = try {
            session.open(owner, QualityAnalyzer(::onFrameMeasured))
        } catch (failed: Exception) {
            SafeLog.error(LogEvent.CAMERA_FAILED, failed)
            mutableUiState.value = mutableUiState.value.copy(errorMessage = R.string.camera_error_open)
            return
        }
        SafeLog.info(LogEvent.CAMERA_OPENED)
        mutableUiState.value = mutableUiState.value.copy(hasTorch = features.hasTorch, torchOn = false)
    }

    /** Closes the camera (leaving the screen, or the review screen showing). */
    fun closeCamera() {
        session.close()
        hintTracker.reset()
        mutableUiState.value = mutableUiState.value.copy(torchOn = false, liveHint = null)
        SafeLog.info(LogEvent.CAMERA_CLOSED)
    }

    /** Turns the torch on or off. */
    fun onTorchClick() {
        val current = mutableUiState.value
        if (!current.hasTorch) return
        val on = !current.torchOn
        session.setTorch(on)
        mutableUiState.value = current.copy(torchOn = on)
    }

    /** Switches the guide frame between card and page. */
    fun onFrameShapeClick() {
        val current = mutableUiState.value
        val next = if (current.frameShape == FrameShape.CARD) FrameShape.PAGE else FrameShape.CARD
        mutableUiState.value = current.copy(frameShape = next)
    }

    /** The shutter: takes a photo into memory and shows "Retake or keep". */
    fun onShutterClick() {
        val current = mutableUiState.value
        if (current.stage != CameraStage.LIVE || lockState.status.value == LockStatus.LOCKED) return
        mutableUiState.value = current.copy(stage = CameraStage.TAKING, errorMessage = null)
        takingJob = viewModelScope.launch { takeAndReview() }
    }

    /** "Retake": the photo is wiped and the preview comes back. */
    fun onRetakeClick() {
        wipePhoto()
        mutableUiState.value = mutableUiState.value.copy(stage = CameraStage.LIVE, review = null, reviewHint = null)
    }

    /**
     * "Use this photo": hands the photo on and forgets it. The caller becomes its owner.
     *
     * @return The photo's JPEG, or null if there is none (or the vault locked).
     */
    fun takeKeptPhoto(): ByteArray? {
        val kept = photo ?: return null
        if (lockState.status.value == LockStatus.LOCKED) {
            wipePhoto()
            return null
        }
        photo = null
        kept.review.recycle()
        mutableUiState.value = mutableUiState.value.copy(review = null, reviewHint = null)
        return kept.jpeg
    }

    /** Leaving the camera screen: the camera closes and any photo is wiped. */
    fun onLeave() {
        takingJob?.cancel()
        closeCamera()
        wipePhoto()
        mutableUiState.value = CameraUiState(frameShape = mutableUiState.value.frameShape)
    }

    override fun onCleared() {
        takingJob?.cancel()
        wipePhoto()
        session.release()
    }

    private fun onFrameMeasured(quality: FrameQuality) {
        // Called on the measuring thread; the state update itself is thread-safe.
        val hint = synchronized(hintTracker) { hintTracker.add(quality) }
        val current = mutableUiState.value
        if (current.stage == CameraStage.LIVE && current.liveHint != hint) mutableUiState.value = current.copy(liveHint = hint)
    }

    private suspend fun takeAndReview() {
        val shot = try {
            session.takePhoto()
        } catch (failed: Exception) {
            SafeLog.error(LogEvent.CAMERA_FAILED, failed)
            showTakeFailure()
            return
        }
        val processed = try {
            withContext(Dispatchers.Default) { PhotoProcessor.process(shot.jpeg, shot.rotationDegrees) }
        } catch (failed: Exception) {
            SafeLog.error(LogEvent.CAMERA_FAILED, failed)
            showTakeFailure()
            return
        } finally {
            // The camera's own JPEG still holds its metadata; it is never needed again.
            shot.wipe()
        }
        if (lockState.status.value == LockStatus.LOCKED || mutableUiState.value.stage != CameraStage.TAKING) {
            processed.wipe()
            return
        }
        SafeLog.info(LogEvent.PHOTO_TAKEN)
        photo = processed
        closeCamera()
        mutableUiState.value = mutableUiState.value.copy(
            stage = CameraStage.REVIEW,
            review = processed.review,
            reviewHint = PhotoCheck.hintFor(processed.quality),
        )
    }

    private fun showTakeFailure() {
        if (mutableUiState.value.stage != CameraStage.TAKING) return
        mutableUiState.value = mutableUiState.value.copy(stage = CameraStage.LIVE, errorMessage = R.string.camera_error_take)
    }

    /** The vault locked: close the camera and drop any photo at once (owner rule, Task 2.4). */
    private fun dropEverything() {
        takingJob?.cancel()
        session.close()
        hintTracker.reset()
        wipePhoto()
        mutableUiState.value = CameraUiState(frameShape = mutableUiState.value.frameShape)
    }

    private fun wipePhoto() {
        photo?.wipe()
        photo = null
    }
}

package com.heybuddy.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.heybuddy.security.VaultLockState

/** One camera view model for the whole app, so its CameraX helper is made only once. */
private const val CAMERA_VIEW_MODEL_KEY = "in_app_camera"

/**
 * Whether "Take photo" can be offered: phones without any camera never see it (Task 2.4).
 *
 * @param context Any context.
 * @return True if the phone has a camera.
 */
fun phoneHasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * The in-app camera as a full screen on top of the Add screen or the viewer (Task 2.4). The camera
 * opens with this screen and closes when it goes (or at once when the vault locks).
 *
 * @param lockState The app's lock.
 * @param onBeforeExternalScreen Called right before the phone's Settings open (auto-lock pass).
 * @param onPhotoKept Gets the kept photo's JPEG; the receiver becomes its owner.
 * @param onChooseInstead Close the camera and open the photo picker instead.
 * @param onClose Close the camera.
 */
@Composable
fun CameraRoute(
    lockState: VaultLockState,
    onBeforeExternalScreen: () -> Unit,
    onPhotoKept: (ByteArray) -> Unit,
    onChooseInstead: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val cameraViewModel: CameraViewModel = viewModel(
        key = CAMERA_VIEW_MODEL_KEY,
        factory = viewModelFactory {
            initializer { CameraViewModel(lockState, CameraSession(context.applicationContext)) }
        },
    )
    val uiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
    val surfaceRequest by cameraViewModel.surfaceRequest.collectAsStateWithLifecycle()
    val checkAccess = { cameraViewModel.onAccessChecked(currentAccess(activity)) }
    // On opening (the screen shows plain black until then) and on every return to the app: the
    // user may have changed the permission in the phone's Settings.
    LifecycleResumeEffect(Unit) {
        checkAccess()
        onPauseOrDispose {}
    }
    val askAndroid = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val showRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        cameraViewModel.onAccessChecked(CameraAccessRules.afterAnswer(granted, showRationale))
    }
    OpenCameraWhileAiming(uiState.stage, cameraViewModel)
    LightBarIconsOnDarkScreens(darkScreen = uiState.stage != CameraStage.NEEDS_ACCESS, activity = activity)
    DisposableEffect(Unit) {
        onDispose { cameraViewModel.onLeave() }
    }
    BackHandler(onBack = onClose)
    CameraScreen(
        uiState = uiState,
        surfaceRequest = surfaceRequest,
        actions = CameraActions(
            onClose = onClose,
            onContinueToAsk = { askAndroid.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                onBeforeExternalScreen()
                activity.startActivity(appSettingsIntent(activity))
            },
            onChooseInstead = onChooseInstead,
            onTorchClick = cameraViewModel::onTorchClick,
            onFrameShapeClick = cameraViewModel::onFrameShapeClick,
            onShutterClick = cameraViewModel::onShutterClick,
            onRetakeClick = cameraViewModel::onRetakeClick,
            onUsePhotoClick = {
                val jpeg = cameraViewModel.takeKeptPhoto()
                if (jpeg != null) onPhotoKept(jpeg)
            },
        ),
    )
}

/** The camera is open only while aiming or taking; the review screen does not need it. */
@Composable
private fun OpenCameraWhileAiming(stage: CameraStage, cameraViewModel: CameraViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val aiming = stage == CameraStage.LIVE || stage == CameraStage.TAKING
    LaunchedEffect(aiming) {
        if (aiming) cameraViewModel.openCamera(lifecycleOwner)
    }
}

/**
 * The capture and review screens are always black, so the status and navigation bar icons must be
 * light there even in light mode (otherwise they are dark on black and cannot be seen).
 */
@Composable
private fun LightBarIconsOnDarkScreens(darkScreen: Boolean, activity: Activity) {
    DisposableEffect(darkScreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val statusWasLight = controller.isAppearanceLightStatusBars
        val navigationWasLight = controller.isAppearanceLightNavigationBars
        if (darkScreen) {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
        onDispose {
            controller.isAppearanceLightStatusBars = statusWasLight
            controller.isAppearanceLightNavigationBars = navigationWasLight
        }
    }
}

private fun currentAccess(activity: Activity): CameraAccess {
    val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    return CameraAccessRules.beforeOpening(granted)
}

/** The phone's own settings page for Hey Buddy, where the camera permission can be turned on. */
private fun appSettingsIntent(activity: Activity): Intent {
    val packageUri = Uri.fromParts("package", activity.packageName, null)
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
}

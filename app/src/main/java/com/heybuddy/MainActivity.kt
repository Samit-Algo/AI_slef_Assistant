package com.heybuddy

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.heybuddy.ui.TopScreen
import com.heybuddy.ui.VaultScreen
import com.heybuddy.ui.adddocument.AddDocumentScreen
import com.heybuddy.ui.adddocument.AddDocumentViewModel
import com.heybuddy.ui.chooseTopScreen
import com.heybuddy.ui.theme.HeyBuddyTheme
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.motionDelayMillis
import com.heybuddy.ui.theme.motionSpec
import com.heybuddy.ui.theme.performConfirmHaptic
import com.heybuddy.ui.vault.VaultListScreen
import com.heybuddy.ui.viewer.ViewerActions
import com.heybuddy.ui.viewer.ViewerScreen
import com.heybuddy.ui.viewer.ViewerViewModel
import com.heybuddy.ui.unlock.NoScreenLockScreen
import com.heybuddy.ui.unlock.UnlockScreen
import com.heybuddy.ui.unlock.UnlockViewModel
import kotlinx.coroutines.delay

/**
 * The app's single activity. A FragmentActivity because BiometricPrompt needs one.
 * Later tasks add FLAG_SECURE (Task 1.9) and navigation.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars; ScreenScaffold keeps content clear of them (spec 5.14).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HeyBuddyApp).container
        setContent {
            HeyBuddyTheme {
                AppRoot(container = container, activity = this)
            }
        }
    }
}

/** Picks the top-level screen from the screen-lock check and the vault lock state. */
@Composable
private fun AppRoot(container: AppContainer, activity: FragmentActivity) {
    val lockStatus by container.vaultLockState.status.collectAsStateWithLifecycle()
    var isDeviceSecure by remember { mutableStateOf(isDeviceSecure(activity)) }

    // Checked again on every resume: the user may have just set (or removed) a screen lock in Settings.
    LifecycleResumeEffect(Unit) {
        isDeviceSecure = isDeviceSecure(activity)
        onPauseOrDispose {}
    }

    val targetScreen = chooseTopScreen(isDeviceSecure, lockStatus)
    var shownScreen by remember { mutableStateOf(targetScreen) }
    var unlockSucceeded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val unlockCelebrationMillis = motionDelayMillis(MotionDurations.UNLOCK_SUCCESS_MILLIS)

    LaunchedEffect(targetScreen) {
        val isUnlocking = shownScreen == TopScreen.UNLOCK && targetScreen == TopScreen.VAULT
        if (isUnlocking) {
            // Let the lock visibly open (with a light tap) before the vault appears.
            unlockSucceeded = true
            performConfirmHaptic(view)
            delay(unlockCelebrationMillis)
        }
        unlockSucceeded = false
        shownScreen = targetScreen
    }

    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = shownScreen,
        transitionSpec = { topScreenTransition(targetState, reduceMotion) },
        label = "topScreen",
    ) { screen ->
        when (screen) {
            TopScreen.NO_SCREEN_LOCK -> NoScreenLockScreen()
            TopScreen.UNLOCK -> UnlockRoute(container = container, activity = activity, unlockSucceeded = unlockSucceeded)
            TopScreen.VAULT -> VaultRoute(container = container, activity = activity)
        }
    }
}

/**
 * Opening the vault fades in gently. Locking is INSTANT: vault content must never stay on screen,
 * not even for a fade, once the vault is locked.
 */
private fun topScreenTransition(target: TopScreen, reduceMotion: Boolean): ContentTransform {
    if (target != TopScreen.VAULT) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    return fadeIn(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion)) togetherWith
        fadeOut(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion))
}

/**
 * Moving between vault screens (spec 5.14): a short fade with a slight upward slide; the viewer
 * opens with a gentle zoom-in instead.
 */
private fun vaultScreenTransition(target: VaultScreen, reduceMotion: Boolean): ContentTransform {
    val fade = fadeIn(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion))
    val enter = if (target is VaultScreen.Viewer) {
        fade + scaleIn(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion), initialScale = VIEWER_START_SCALE)
    } else {
        fade + slideInVertically(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion)) { height -> height / SLIDE_FRACTION }
    }
    val exit = fadeOut(motionSpec(MotionDurations.SCREEN_MILLIS, reduceMotion))
    return enter togetherWith exit
}

/** The viewer starts at 96% size and grows to full size as it fades in. */
private const val VIEWER_START_SCALE = 0.96f

/** Screens slide in from 1/24 of their height below: enough to feel smooth, small enough to stay calm. */
private const val SLIDE_FRACTION = 24

@Composable
private fun VaultRoute(container: AppContainer, activity: FragmentActivity) {
    // The current screen comes from VaultNavigator (memory only), never from saved screen state:
    // after any unlock, even one following the phone closing the app, the vault opens at HOME.
    val navigator = container.vaultNavigator
    val screen by navigator.screen.collectAsStateWithLifecycle()
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = screen,
        transitionSpec = { vaultScreenTransition(targetState, reduceMotion) },
        label = "vaultScreen",
    ) { shown ->
        when (shown) {
            // On the list, Back is not handled here, so it leaves the app normally.
            VaultScreen.List -> {
                val documents by remember { container.vaultRepository.observeDocuments() }
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                VaultListScreen(
                    documents = documents,
                    onOpenDocument = { documentId -> navigator.open(VaultScreen.Viewer(documentId)) },
                    onAddDocument = { navigator.open(VaultScreen.AddDocument) },
                )
            }
            VaultScreen.AddDocument -> AddDocumentRoute(
                container = container,
                activity = activity,
                onClose = { navigator.handleBack() },
            )
            is VaultScreen.Viewer -> ViewerRoute(container = container, activity = activity, documentId = shown.documentId)
        }
    }
}

@Composable
private fun ViewerRoute(container: AppContainer, activity: FragmentActivity, documentId: String) {
    val viewerViewModel: ViewerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ViewerViewModel(
                    repository = container.vaultRepository,
                    unlocker = container.biometricUnlocker,
                    lockState = container.vaultLockState,
                    navigator = container.vaultNavigator,
                    tempFileCleaner = container.tempFileCleaner,
                )
            }
        },
    )
    val metrics = activity.resources.displayMetrics
    val screenWidth = metrics.widthPixels
    val screenLongest = maxOf(metrics.widthPixels, metrics.heightPixels)
    LaunchedEffect(documentId) {
        viewerViewModel.show(activity, documentId, screenWidth, screenLongest)
    }
    val uiState by viewerViewModel.uiState.collectAsStateWithLifecycle()
    // Back: closes the Rename dialog if open (the dialog itself also does this), otherwise tidies
    // up (PDF deleted, picture freed) and returns to the list.
    BackHandler(onBack = viewerViewModel::onBack)
    ViewerScreen(
        uiState = uiState,
        actions = ViewerActions(
            onBack = viewerViewModel::onBack,
            onRetry = { viewerViewModel.retry(activity) },
            onPreviousPage = viewerViewModel::previousPage,
            onNextPage = viewerViewModel::nextPage,
            onRenameClick = viewerViewModel::openRename,
            onRenameTextChanged = viewerViewModel::onRenameTextChanged,
            onRenameConfirm = viewerViewModel::confirmRename,
            onRenameDismiss = viewerViewModel::closeRename,
        ),
    )
}

@Composable
private fun AddDocumentRoute(container: AppContainer, activity: FragmentActivity, onClose: () -> Unit) {
    val addViewModel: AddDocumentViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AddDocumentViewModel(
                    appContext = activity.applicationContext,
                    saver = container.addDocumentSaver,
                    unlocker = container.biometricUnlocker,
                    lockState = container.vaultLockState,
                )
            }
        },
    )
    val uiState by addViewModel.uiState.collectAsStateWithLifecycle()
    // Back, Cancel and Done all wipe any picked file bytes before returning to the list.
    val closeAndForget = {
        addViewModel.reset()
        onClose()
    }
    BackHandler(onBack = closeAndForget)
    AddDocumentScreen(
        uiState = uiState,
        onFilePicked = addViewModel::onFilePicked,
        onTypeChosen = addViewModel::onTypeChosen,
        onNameChanged = addViewModel::onNameChanged,
        onSaveClick = { addViewModel.onSaveClicked(activity) },
        onClose = closeAndForget,
    )
}

@Composable
private fun UnlockRoute(container: AppContainer, activity: FragmentActivity, unlockSucceeded: Boolean) {
    val unlockViewModel: UnlockViewModel = viewModel(
        factory = viewModelFactory {
            initializer { UnlockViewModel(container.biometricUnlocker) }
        },
    )
    val uiState by unlockViewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        unlockViewModel.refreshFingerprintNote(activity)
        onPauseOrDispose {}
    }

    UnlockScreen(
        uiState = uiState,
        unlockSucceeded = unlockSucceeded,
        onUnlockClick = { unlockViewModel.onUnlockClicked(activity) },
    )
}

private fun isDeviceSecure(context: Context): Boolean {
    val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    return keyguardManager.isDeviceSecure
}

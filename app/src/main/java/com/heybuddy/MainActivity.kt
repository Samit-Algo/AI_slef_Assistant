package com.heybuddy

import com.heybuddy.ui.camera.phoneHasCamera
import com.heybuddy.ui.camera.CameraRoute
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import com.heybuddy.settings.ThemeChoice
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.onboarding.OnboardingScreen
import com.heybuddy.ui.settings.LicenceTextScreen
import com.heybuddy.ui.settings.SettingsActions
import com.heybuddy.ui.settings.SettingsScreen
import com.heybuddy.ui.settings.SettingsViewModel
import com.heybuddy.ui.theme.HeyBuddyDarkColors
import com.heybuddy.ui.theme.HeyBuddyLightColors
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.heybuddy.ui.TopScreen
import com.heybuddy.ui.TopScreenHost
import com.heybuddy.ui.theme.LocalThemeCrossfade
import com.heybuddy.ui.theme.ThemeCrossfadeHost
import com.heybuddy.ui.VaultBackHandler
import com.heybuddy.ui.VaultScreen
import com.heybuddy.ui.VaultScreenHost
import com.heybuddy.ui.growsFromCard
import com.heybuddy.ui.adddocument.AddStage
import com.heybuddy.ui.adddocument.AddDocumentActions
import com.heybuddy.ui.adddocument.AddDocumentScreen
import com.heybuddy.ui.adddocument.AddDocumentViewModel
import com.heybuddy.ui.chooseTopScreen
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.LocalObscuredTouchReporter
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.recovery.RecoveryScreen
import com.heybuddy.ui.theme.Dimens
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
import com.heybuddy.util.LogEvent
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's single activity. A FragmentActivity because BiometricPrompt needs one.
 *
 * Security set-up for the whole window (Task 1.9): FLAG_SECURE (rule 8), touches through other
 * apps' overlays are dropped (tapjacking), and auto-lock events (rule 9) are fed from here.
 */
class MainActivity : FragmentActivity() {

    private val container: AppContainer
        get() = (application as HeyBuddyApp).container

    // Locks at once when the screen turns off, even if a picker pass is active (rule 9).
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            container.autoLock.onScreenOff()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startDark = isDarkFor(container.appSettings.theme.value, isPhoneInDarkMode())
        // Draw behind the system bars; ScreenScaffold keeps content clear of them (spec 5.14).
        applySystemBars(startDark)
        super.onCreate(savedInstanceState)
        // The chosen theme's background from the very first frame: no white flash in dark mode.
        applyWindowBackground(startDark)
        protectWindow()
        registerScreenOffReceiver()
        setContent {
            val themeChoice by container.appSettings.theme.collectAsStateWithLifecycle()
            val dark = isDarkFor(themeChoice, isSystemInDarkTheme())
            LaunchedEffect(dark) {
                applySystemBars(dark)
                applyWindowBackground(dark)
            }
            HeyBuddyTheme(darkTheme = dark) {
                AppRoot(container = container, activity = this)
            }
        }
    }

    // Status/navigation bar icons must match the app's theme, not the phone's, when they differ.
    private fun applySystemBars(dark: Boolean) {
        val transparent = Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun applyWindowBackground(dark: Boolean) {
        val scheme = if (dark) HeyBuddyDarkColors else HeyBuddyLightColors
        window.setBackgroundDrawable(scheme.background.toArgb().toDrawable())
    }

    private fun isPhoneInDarkMode(): Boolean {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onStart() {
        super.onStart()
        container.autoLock.onAppStarted(lifecycleScope)
        container.vaultHealthMonitor.refresh()
    }

    override fun onStop() {
        container.autoLock.onAppStopped(isChangingConfigurations, lifecycleScope)
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        container.autoLock.onUserActivity()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }

    private fun protectWindow() {
        // Rule 8: screenshots and screen recordings show black; the recent-apps preview is hidden.
        // Dialogs and menus inherit this from the window (Compose's default secure policy).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        // Tapjacking: drop any touch made through another app's overlay, anywhere in the window.
        // Sensitive buttons are stricter still (rejectObscuredTouches).
        window.decorView.filterTouchesWhenObscured = true
    }

    private fun registerScreenOffReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(this, screenOffReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

/** Picks the top-level screen from the screen-lock check, the vault's key condition and the lock state. */
@Composable
private fun AppRoot(container: AppContainer, activity: FragmentActivity) {
    val lockStatus by container.vaultLockState.status.collectAsStateWithLifecycle()
    val condition by container.vaultHealthMonitor.condition.collectAsStateWithLifecycle()
    var isDeviceSecure by remember { mutableStateOf(isDeviceSecure(activity)) }

    // Checked again on every resume: the user may have just set (or removed) a screen lock in Settings.
    LifecycleResumeEffect(Unit) {
        isDeviceSecure = isDeviceSecure(activity)
        onPauseOrDispose {}
    }

    val onboardingDone by container.appSettings.onboardingDone.collectAsStateWithLifecycle()
    val erasing by container.vaultEraser.erasing.collectAsStateWithLifecycle()
    val targetScreen = chooseTopScreen(isDeviceSecure, lockStatus, condition, onboardingDone, erasing)
    var openedScreen by remember { mutableStateOf(targetScreen) }
    var unlockSucceeded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val unlockCelebrationMillis = motionDelayMillis(MotionDurations.UNLOCK_SUCCESS_MILLIS)

    LaunchedEffect(targetScreen) {
        val isUnlocking = openedScreen == TopScreen.UNLOCK && targetScreen == TopScreen.VAULT
        if (isUnlocking) {
            // Let the lock visibly open (with a light tap) before the vault appears.
            unlockSucceeded = true
            performConfirmHaptic(view)
            delay(unlockCelebrationMillis)
        }
        unlockSucceeded = false
        openedScreen = targetScreen
    }

    // Leaving the vault happens in THIS frame (not after the effect above), so not even one more
    // frame of vault content is drawn after a lock. Only opening the vault waits for the animation.
    val shownScreen = if (targetScreen != TopScreen.VAULT) targetScreen else openedScreen

    ObscuredTouchNoticeHost {
        TopScreenHost(screen = shownScreen) { screen ->
            when (screen) {
                TopScreen.ERASING -> ErasingScreen()
                TopScreen.ONBOARDING -> OnboardingScreen(
                    onFinished = {
                        SafeLog.info(LogEvent.ONBOARDING_FINISHED)
                        container.appSettings.markOnboardingDone()
                    },
                )
                TopScreen.NO_SCREEN_LOCK -> NoScreenLockScreen()
                TopScreen.RECOVERY -> RecoveryRoute(container = container)
                TopScreen.UNLOCK -> UnlockRoute(container = container, activity = activity, unlockSucceeded = unlockSucceeded)
                TopScreen.VAULT -> VaultRoute(container = container, activity = activity)
            }
        }
    }
}

/**
 * Lets any sensitive button report a tap that was blocked by another app's overlay, and shows the
 * explanation at the top of the screen for a few seconds.
 */
@Composable
private fun ObscuredTouchNoticeHost(content: @Composable () -> Unit) {
    var noticeVisible by remember { mutableStateOf(false) }
    val showNotice = {
        SafeLog.info(LogEvent.TOUCH_BLOCKED_OVERLAY)
        noticeVisible = true
    }
    LaunchedEffect(noticeVisible) {
        if (noticeVisible) {
            delay(OVERLAY_NOTICE_MILLIS)
            noticeVisible = false
        }
    }
    CompositionLocalProvider(LocalObscuredTouchReporter provides showNotice) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            if (noticeVisible) {
                MessageBanner(
                    text = stringResource(R.string.touch_blocked_overlay),
                    kind = BannerKind.ERROR,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(Dimens.SpaceL),
                )
            }
        }
    }
}

/** How long the "another app is drawing over Hey Buddy" message stays on screen. */
private const val OVERLAY_NOTICE_MILLIS = 6_000L

/** Shown while "Delete everything" runs: nothing to tap, so nothing can interfere. */
@Composable
private fun ErasingScreen() {
    ScreenScaffold(centerContent = true) {
        LoadingState(text = stringResource(R.string.erasing_text))
    }
}

/**
 * Light or dark for the chosen theme.
 *
 * @param choice The theme from Settings.
 * @param phoneIsDark Whether the phone itself is in dark mode.
 * @return true for the dark theme.
 */
private fun isDarkFor(choice: ThemeChoice, phoneIsDark: Boolean): Boolean {
    return when (choice) {
        ThemeChoice.FOLLOW_PHONE -> phoneIsDark
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
}

@Composable
private fun RecoveryRoute(container: AppContainer) {
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    RecoveryScreen(
        deleting = deleting,
        onDeleteConfirmed = {
            deleting = true
            // The eraser runs to the end even if this screen goes away (NonCancellable inside).
            scope.launch { container.vaultEraser.eraseEverything() }
        },
    )
}

@Composable
private fun VaultRoute(container: AppContainer, activity: FragmentActivity) {
    // The current screen comes from VaultNavigator (memory only), never from saved screen state:
    // after any unlock, even one following the phone closing the app, the vault opens at HOME.
    val navigator = container.vaultNavigator
    val screen by navigator.screen.collectAsStateWithLifecycle()
    // Lives as long as the vault is open: cleared by every lock, so each unlock shows the list's
    // gentle entrance once, and coming back from a document does not repeat it.
    val shownCards = remember { mutableSetOf<String>() }
    ThemeCrossfadeHost {
        VaultScreenHost(screen = screen, currentScreen = { navigator.screen.value }) { shown ->
            when (shown) {
                // On the list, Back is not handled here, so it leaves the app normally.
                VaultScreen.List -> {
                    val documents by remember { container.vaultRepository.observeDocuments() }
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    VaultListScreen(
                        documents = documents,
                        onOpenDocument = { documentId -> navigator.open(VaultScreen.Viewer(documentId)) },
                        onAddDocument = { navigator.open(VaultScreen.AddDocument) },
                        onOpenSettings = { navigator.open(VaultScreen.Settings) },
                        shownCards = shownCards,
                    )
                }
                VaultScreen.AddDocument -> AddDocumentRoute(
                    container = container,
                    activity = activity,
                    onClose = { navigator.handleBack() },
                )
                VaultScreen.Settings -> SettingsRoute(container = container, activity = activity)
                is VaultScreen.Viewer -> ViewerRoute(container = container, activity = activity, documentId = shown.documentId)
            }
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
                    preparer = container.pagePreparer,
                    saver = container.addDocumentSaver,
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
    // "Add page → Take photo" shows the in-app camera on top of the viewer (Task 2.4).
    var cameraOpen by remember { mutableStateOf(false) }
    if (cameraOpen) {
        CameraRoute(
            lockState = container.vaultLockState,
            onBeforeExternalScreen = container.autoLock::issueExternalScreenPass,
            onPhotoKept = { jpeg ->
                cameraOpen = false
                viewerViewModel.onPhotoTaken(activity, jpeg)
            },
            onChooseInstead = {
                cameraOpen = false
                // Back to "Add a page", which offers Photos and files.
                viewerViewModel.openAddPage()
            },
            onClose = { cameraOpen = false },
        )
        return
    }
    // Back: closes a dialog if one is open, otherwise tidies up (PDF deleted, picture freed) and
    // returns to the list. Only the second can be previewed with the back gesture.
    val leavesTheViewer = uiState.rename == null && !uiState.deleteConfirmOpen && !uiState.addPageChoiceOpen &&
        !uiState.removePageConfirmOpen && !uiState.busy
    VaultBackHandler(previewEnabled = leavesTheViewer, onBack = viewerViewModel::onBack)
    ViewerScreen(
        modifier = Modifier.growsFromCard(documentId),
        uiState = uiState,
        actions = ViewerActions(
            onBack = viewerViewModel::onBack,
            onRetry = { viewerViewModel.retry(activity) },
            onPreviousPage = viewerViewModel::previousPage,
            onNextPage = viewerViewModel::nextPage,
            onRenameClick = viewerViewModel::openRename,
            onRenameTextChanged = { text ->
                container.autoLock.onUserActivity()
                viewerViewModel.onRenameTextChanged(text)
            },
            onRenameConfirm = viewerViewModel::confirmRename,
            onRenameDismiss = viewerViewModel::closeRename,
            onDeleteClick = viewerViewModel::openDelete,
            onDeleteConfirm = viewerViewModel::confirmDelete,
            onDeleteDismiss = viewerViewModel::closeDelete,
            onDocumentPageChanged = { index -> viewerViewModel.showDocumentPage(activity, index) },
            onAddPageClick = viewerViewModel::openAddPage,
            onAddPageDismiss = viewerViewModel::closeAddPage,
            onPagesPicked = { uris -> viewerViewModel.onPagesPicked(activity, uris) },
            onRemovePageClick = viewerViewModel::openRemovePage,
            onRemovePageConfirm = { viewerViewModel.confirmRemovePage(activity) },
            onRemovePageDismiss = viewerViewModel::closeRemovePage,
            onBeforeExternalScreen = container.autoLock::issueExternalScreenPass,
            onSuccessNoticeShown = viewerViewModel::clearSuccessNotice,
            onTakePhotoClick = if (phoneHasCamera(activity)) ({ cameraOpen = true }) else null,
        ),
    )
}

@Composable
private fun SettingsRoute(container: AppContainer, activity: FragmentActivity) {
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = container.appSettings,
                    deleteFlow = container.deleteEverythingFlow,
                    unlocker = container.biometricUnlocker,
                    onIdleLimitChanged = container.autoLock::onIdleLimitChanged,
                    lockState = container.vaultLockState,
                )
            }
        },
    )
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val theme by settingsViewModel.theme.collectAsStateWithLifecycle()
    val autoLock by settingsViewModel.autoLock.collectAsStateWithLifecycle()
    val navigator = container.vaultNavigator
    val themeCrossfade = LocalThemeCrossfade.current
    val themeScope = rememberCoroutineScope()
    // Back closes the licence text or the dialog first; otherwise returns to the list.
    val goBack = {
        val usedHere = settingsViewModel.onBack()
        if (!usedHere) navigator.handleBack()
    }
    val leavesSettings = uiState.openLicence == null && uiState.deleteDialog == null
    VaultBackHandler(previewEnabled = leavesSettings, onBack = goBack)
    val licence = uiState.openLicence
    if (licence != null) {
        LicenceTextScreen(entry = licence, onBack = goBack)
        return
    }
    SettingsScreen(
        theme = theme,
        autoLock = autoLock,
        uiState = uiState,
        versionText = versionText(activity),
        actions = SettingsActions(
            onBack = goBack,
            onThemeChosen = { choice ->
                // Cross-fades the whole screen to the new theme (spec 5.14, item 9).
                themeScope.launch {
                    val crossfade = themeCrossfade
                    if (crossfade == null) settingsViewModel.setTheme(choice) else crossfade.change { settingsViewModel.setTheme(choice) }
                }
            },
            onAutoLockChosen = settingsViewModel::setAutoLock,
            onLicenceClick = settingsViewModel::openLicence,
            onDeleteAllClick = settingsViewModel::openDeleteAll,
            onDeleteTextChanged = { text ->
                // Keyboard taps go to the keyboard's window, so typing is reported to the idle timer here.
                container.autoLock.onUserActivity()
                settingsViewModel.onDeleteTextChanged(text)
            },
            onDeleteAllConfirm = { settingsViewModel.confirmDeleteAll(activity) },
            onDeleteAllDismiss = settingsViewModel::closeDeleteAll,
        ),
    )
}

@Composable
private fun versionText(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = info.versionName ?: ""
    return stringResource(R.string.settings_version, versionName, info.longVersionCode.toInt())
}

@Composable
private fun AddDocumentRoute(container: AppContainer, activity: FragmentActivity, onClose: () -> Unit) {
    val addViewModel: AddDocumentViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AddDocumentViewModel(
                    appContext = activity.applicationContext,
                    preparer = container.pagePreparer,
                    saver = container.addDocumentSaver,
                    unlocker = container.biometricUnlocker,
                    lockState = container.vaultLockState,
                )
            }
        },
    )
    val uiState by addViewModel.uiState.collectAsStateWithLifecycle()
    // The in-app camera is shown on top of this screen (Task 2.4); memory only, never saved state.
    var cameraOpen by remember { mutableStateOf(false) }
    var pickPhotosNow by remember { mutableStateOf(false) }
    if (cameraOpen) {
        CameraRoute(
            lockState = container.vaultLockState,
            onBeforeExternalScreen = container.autoLock::issueExternalScreenPass,
            onPhotoKept = { jpeg ->
                cameraOpen = false
                addViewModel.onPhotoTaken(jpeg)
            },
            onChooseInstead = {
                cameraOpen = false
                pickPhotosNow = true
            },
            onClose = { cameraOpen = false },
        )
        return
    }
    // Back, Cancel and Done all delete any pages not saved yet before returning to the list.
    val closeAndForget = {
        addViewModel.reset()
        onClose()
    }
    // While saving, Back still waits for the save, so there is nothing to preview.
    VaultBackHandler(previewEnabled = uiState.stage != AddStage.SAVING, onBack = closeAndForget)
    AddDocumentScreen(
        uiState = uiState,
        actions = AddDocumentActions(
            onFilesPicked = addViewModel::onFilesPicked,
            onRemovePage = addViewModel::onRemovePage,
            onMovePageUp = addViewModel::onMovePageUp,
            onMovePageDown = addViewModel::onMovePageDown,
            onTypeChosen = addViewModel::onTypeChosen,
            onNameChanged = { name ->
                // Keyboard taps go to the keyboard's window, so typing is reported to the idle timer here.
                container.autoLock.onUserActivity()
                addViewModel.onNameChanged(name)
            },
            onSaveClick = { addViewModel.onSaveClicked(activity) },
            onClose = closeAndForget,
            onBeforeExternalScreen = container.autoLock::issueExternalScreenPass,
            onTakePhoto = if (phoneHasCamera(activity)) ({ cameraOpen = true }) else null,
            onPickPhotosNowHandled = { pickPhotosNow = false },
        ),
        pickPhotosNow = pickPhotosNow,
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

package com.heybuddy

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.heybuddy.ui.TopScreen
import com.heybuddy.ui.adddocument.AddDocumentScreen
import com.heybuddy.ui.adddocument.AddDocumentViewModel
import com.heybuddy.ui.chooseTopScreen
import com.heybuddy.ui.theme.HeyBuddyTheme
import com.heybuddy.ui.vault.VaultHomePlaceholder
import com.heybuddy.ui.unlock.NoScreenLockScreen
import com.heybuddy.ui.unlock.UnlockScreen
import com.heybuddy.ui.unlock.UnlockViewModel

/**
 * The app's single activity. A FragmentActivity because BiometricPrompt needs one.
 * Later tasks add FLAG_SECURE (Task 1.9) and navigation.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

    when (chooseTopScreen(isDeviceSecure, lockStatus)) {
        TopScreen.NO_SCREEN_LOCK -> NoScreenLockScreen()
        TopScreen.UNLOCK -> UnlockRoute(container = container, activity = activity)
        TopScreen.VAULT -> VaultRoute(container = container, activity = activity)
    }
}

/** Screens inside the unlocked vault. Navigation Compose replaces this in Task 1.8. */
private enum class VaultScreen {
    HOME,
    ADD_DOCUMENT,
}

@Composable
private fun VaultRoute(container: AppContainer, activity: FragmentActivity) {
    // Not saved across a lock: this route leaves the screen on lock, so unlock starts at HOME.
    var screen by rememberSaveable { mutableStateOf(VaultScreen.HOME) }
    when (screen) {
        VaultScreen.HOME -> {
            val documents by remember { container.vaultRepository.observeDocuments() }
                .collectAsStateWithLifecycle(initialValue = emptyList())
            VaultHomePlaceholder(
                documentCount = documents.size,
                onAddDocument = { screen = VaultScreen.ADD_DOCUMENT },
            )
        }
        VaultScreen.ADD_DOCUMENT -> AddDocumentRoute(
            container = container,
            activity = activity,
            onClose = { screen = VaultScreen.HOME },
        )
    }
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
private fun UnlockRoute(container: AppContainer, activity: FragmentActivity) {
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
        onUnlockClick = { unlockViewModel.onUnlockClicked(activity) },
    )
}

private fun isDeviceSecure(context: Context): Boolean {
    val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    return keyguardManager.isDeviceSecure
}

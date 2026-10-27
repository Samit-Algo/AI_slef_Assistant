package com.heybuddy

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.heybuddy.ui.TopScreen
import com.heybuddy.ui.chooseTopScreen
import com.heybuddy.ui.theme.HeyBuddyTheme
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
        TopScreen.VAULT -> VaultUnlockedPlaceholder()
    }
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

/** Temporary screen shown after unlock. Replaced by the vault list in Task 1.8. */
@Composable
private fun VaultUnlockedPlaceholder() {
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.vault_unlocked_placeholder),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun isDeviceSecure(context: Context): Boolean {
    val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    return keyguardManager.isDeviceSecure
}

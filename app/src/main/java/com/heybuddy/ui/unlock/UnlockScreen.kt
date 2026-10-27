package com.heybuddy.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.ui.theme.HeyBuddyTheme

/**
 * The Unlock screen: app name, one Unlock button, and any message.
 *
 * @param uiState What to show.
 * @param onUnlockClick Called when the user taps Unlock.
 */
@Composable
fun UnlockScreen(uiState: UnlockUiState, onUnlockClick: () -> Unit) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Button(onClick = onUnlockClick) {
                Text(text = stringResource(R.string.unlock_button))
            }
            if (uiState.showNoFingerprintNote) {
                Text(
                    text = stringResource(R.string.unlock_note_no_fingerprint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            val errorMessage = uiState.errorMessage
            if (errorMessage != null) {
                Text(
                    text = stringResource(errorMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockScreenPreview() {
    HeyBuddyTheme {
        UnlockScreen(
            uiState = UnlockUiState(
                errorMessage = R.string.unlock_error_cancelled,
                showNoFingerprintNote = true,
            ),
            onUnlockClick = {},
        )
    }
}

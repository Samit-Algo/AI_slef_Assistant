package com.heybuddy.ui.unlock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.ui.theme.HeyBuddyTheme

/**
 * Shown when the phone has no screen lock (rule 10). The vault stays blocked until one is set.
 */
@Composable
fun NoScreenLockScreen() {
    val context = LocalContext.current
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
                text = stringResource(R.string.no_lock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.no_lock_body),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { openSecuritySettings(context) }) {
                Text(text = stringResource(R.string.no_lock_button))
            }
        }
    }
}

/** Opens Android's security settings. Some phone makers remove that screen, so fall back to main Settings. */
private fun openSecuritySettings(context: Context) {
    val securityIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
    try {
        context.startActivity(securityIntent)
    } catch (notAvailable: ActivityNotFoundException) {
        val settingsIntent = Intent(Settings.ACTION_SETTINGS)
        context.startActivity(settingsIntent)
    }
}

@Preview(showBackground = true)
@Composable
private fun NoScreenLockScreenPreview() {
    HeyBuddyTheme {
        NoScreenLockScreen()
    }
}

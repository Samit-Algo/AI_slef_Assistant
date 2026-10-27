package com.heybuddy.ui.unlock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.heybuddy.R
import com.heybuddy.ui.components.HeroIcon
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.HeyBuddyTheme

/**
 * Shown when the phone has no screen lock (rule 10). The vault stays blocked until one is set.
 */
@Composable
fun NoScreenLockScreen() {
    val context = LocalContext.current
    ScreenScaffold(centerContent = true) {
        HeroIcon(icon = R.drawable.ic_shield)
        Spacer(modifier = Modifier.height(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.no_lock_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.no_lock_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceL))
        PrimaryButton(text = stringResource(R.string.no_lock_button), onClick = { openSecuritySettings(context) })
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

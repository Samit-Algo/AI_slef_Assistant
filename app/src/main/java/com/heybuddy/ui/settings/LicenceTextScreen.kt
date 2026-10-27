package com.heybuddy.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.heybuddy.R
import com.heybuddy.ui.components.ScreenScaffold

/**
 * Shows one full licence text, read from inside the app (no internet needed).
 *
 * @param entry Which licence.
 * @param onBack Back arrow tapped.
 */
@Composable
fun LicenceTextScreen(entry: LicenceEntry, onBack: () -> Unit) {
    val resources = LocalResources.current
    // Read off the main thread: the Apache text is about 11 KB.
    val licenceText by produceState(initialValue = "", entry) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(entry.licenceText).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }
    }
    ScreenScaffold(topBar = { SettingsTopBar(R.string.settings_licences_title, onBack) }) {
        Text(text = stringResource(entry.libraryName), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(entry.licenceName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = licenceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

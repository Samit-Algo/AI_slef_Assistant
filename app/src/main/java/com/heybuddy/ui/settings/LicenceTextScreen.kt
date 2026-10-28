package com.heybuddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.heybuddy.R
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows one full licence text, read from inside the app (no internet needed).
 *
 * The text is shown as a lazy list of paragraphs, so even ML Kit's 2.3 MB list of open-source
 * licences opens at once and scrolls smoothly (one huge text block would freeze the screen).
 *
 * @param entry Which licence.
 * @param onBack Back arrow tapped.
 */
@Composable
fun LicenceTextScreen(entry: LicenceEntry, onBack: () -> Unit) {
    val resources = LocalResources.current
    // Read off the main thread; null until loaded.
    val paragraphs by produceState<List<String>?>(initialValue = null, entry) {
        value = withContext(Dispatchers.IO) {
            val text = resources.openRawResource(entry.licenceText).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            splitIntoParagraphs(text)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { SettingsTopBar(R.string.settings_licences_title, onBack) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXl),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            item { LicenceHeader(entry) }
            val loaded = paragraphs
            if (loaded == null) {
                item { LoadingState(text = stringResource(R.string.licence_loading)) }
            } else {
                items(loaded) { paragraph ->
                    Text(text = paragraph, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LicenceHeader(entry: LicenceEntry) {
    Text(text = stringResource(entry.libraryName), style = MaterialTheme.typography.titleLarge)
    Text(
        text = stringResource(entry.licenceName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    val note = entry.note
    if (note != null) {
        MessageBanner(text = stringResource(note), kind = BannerKind.INFO, modifier = Modifier.padding(top = Dimens.SpaceM))
    }
}

/**
 * Splits a licence text at blank lines. Long licence files are many short paragraphs, so the list
 * only ever draws the few that are on screen.
 *
 * @param text The whole licence text.
 * @return Its paragraphs, without empty ones.
 */
internal fun splitIntoParagraphs(text: String): List<String> {
    return text.split(BLANK_LINE).map { paragraph -> paragraph.trim() }.filter { paragraph -> paragraph.isNotEmpty() }
}

/** One or more empty lines (Windows or Unix line ends). */
private val BLANK_LINE = Regex("\\r?\\n\\s*\\r?\\n")

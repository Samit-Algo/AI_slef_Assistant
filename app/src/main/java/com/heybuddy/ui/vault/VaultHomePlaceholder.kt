package com.heybuddy.ui.vault

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.heybuddy.R
import com.heybuddy.ui.components.HeroIcon
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.theme.Dimens

/**
 * Temporary vault screen: shows how many documents are saved, so saving can be checked.
 * Replaced by the vault list in Task 1.8b.
 *
 * @param documentCount How many documents are in the vault.
 * @param onAddDocument Called when the user taps "Add document".
 */
@Composable
fun VaultHomePlaceholder(documentCount: Int, onAddDocument: () -> Unit) {
    ScreenScaffold(centerContent = true) {
        HeroIcon(
            icon = R.drawable.ic_lock_open,
            tint = MaterialTheme.colorScheme.tertiary,
            container = MaterialTheme.colorScheme.tertiaryContainer,
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.vault_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = pluralStringResource(R.plurals.vault_unlocked_count, documentCount, documentCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceL))
        PrimaryButton(text = stringResource(R.string.add_document_button), onClick = onAddDocument, icon = R.drawable.ic_add)
    }
}

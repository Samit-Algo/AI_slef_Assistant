package com.heybuddy.ui.vault

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heybuddy.R

/**
 * Temporary vault screen: shows how many documents are saved, so saving can be checked.
 * Replaced by the vault list in Task 1.8.
 *
 * @param documentCount How many documents are in the vault.
 * @param onAddDocument Called when the user taps "Add document".
 */
@Composable
fun VaultHomePlaceholder(documentCount: Int, onAddDocument: () -> Unit) {
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
                text = pluralStringResource(R.plurals.vault_unlocked_count, documentCount, documentCount),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddDocument) {
                Text(text = stringResource(R.string.add_document_button))
            }
        }
    }
}

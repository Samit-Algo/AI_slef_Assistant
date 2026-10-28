package com.heybuddy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.data.DocumentType
import com.heybuddy.ui.theme.Dimens

private val EncryptedIconSize = 14.dp

/**
 * One document in the vault list (spec 5.14): type badge, name, type and date, and a small
 * "Encrypted" label. Never a thumbnail or any document content.
 *
 * TalkBack reads the whole card as one item, for example "My Aadhaar, Aadhaar card, Added 27 Oct 2026,
 * encrypted".
 *
 * @param name The document's name.
 * @param type The document type (for the badge).
 * @param typeLabel The type's name in text, for example "Aadhaar card".
 * @param addedText The date line, for example "Added 27 Oct 2026".
 * @param onClick Called when the card is tapped.
 * @param modifier Layout changes from the caller.
 */
@Composable
fun DocumentCard(
    name: String,
    type: DocumentType,
    typeLabel: String,
    addedText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val encryptedLabel = stringResource(R.string.label_encrypted)
    val spokenDescription = listOf(name, typeLabel, addedText, encryptedLabel).joinToString(", ")
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(Dimens.BorderWidth, MaterialTheme.colorScheme.outlineVariant),
        interactionSource = interactionSource,
        modifier = modifier
            .pressScale(interactionSource)
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = spokenDescription },
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceL),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeBadge(type = type)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$typeLabel · $addedText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EncryptedLabel(text = encryptedLabel)
            }
        }
    }
}

@Composable
private fun EncryptedLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Icon(
            painter = painterResource(R.drawable.ic_lock),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(EncryptedIconSize),
        )
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

package com.heybuddy.ui.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.data.DocumentSummary
import com.heybuddy.ui.adddocument.labelFor
import com.heybuddy.ui.components.DocumentCard
import com.heybuddy.ui.components.EmptyState
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.motionSpec
import kotlinx.coroutines.delay
import java.time.ZoneId

/** Cards appear one after another, this far apart, so the list "settles" instead of popping in. */
private const val CARD_STAGGER_MILLIS = 40L

/** No card waits longer than this, so a long list never feels slow. */
private const val MAX_CARD_DELAY_MILLIS = 240L

/** Cards rise this far while they fade in (spec 5.14). */
private val CardRiseDistance = 8.dp

/** Room below the last card so the "+" button never covers it. */
private val SpaceForAddButton = 88.dp

/**
 * The vault list (spec 5.14): one card per document, newest first, or a friendly empty state.
 * No thumbnails and no document content.
 *
 * @param documents The documents, newest first (as VaultRepository gives them).
 * @param onOpenDocument Called with a document's id when its card is tapped.
 * @param onAddDocument Called when "+" is tapped.
 */
@Composable
fun VaultListScreen(
    documents: List<DocumentSummary>,
    onOpenDocument: (String) -> Unit,
    onAddDocument: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = { AddButton(onAddDocument) },
    ) { innerPadding ->
        if (documents.isEmpty()) {
            EmptyVault(innerPadding)
        } else {
            DocumentList(documents, innerPadding, onOpenDocument)
        }
    }
}

@Composable
private fun AddButton(onAddDocument: () -> Unit) {
    FloatingActionButton(
        onClick = onAddDocument,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(painter = painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.add_document_button))
    }
}

@Composable
private fun EmptyVault(innerPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .padding(horizontal = Dimens.ScreenPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        VaultHeader(documentCount = 0)
        EmptyState(
            icon = R.drawable.ic_shield,
            title = stringResource(R.string.vault_empty_title),
            body = stringResource(R.string.vault_empty_body),
        )
    }
}

@Composable
private fun DocumentList(
    documents: List<DocumentSummary>,
    innerPadding: PaddingValues,
    onOpenDocument: (String) -> Unit,
) {
    // Cards already shown once do not animate again (for example when scrolling back up).
    val alreadyShown = remember { mutableSetOf<String>() }
    val layoutDirection = LocalLayoutDirection.current
    val listPadding = PaddingValues(
        start = innerPadding.calculateStartPadding(layoutDirection) + Dimens.ScreenPadding,
        end = innerPadding.calculateEndPadding(layoutDirection) + Dimens.ScreenPadding,
        top = innerPadding.calculateTopPadding() + Dimens.SpaceXl,
        bottom = innerPadding.calculateBottomPadding() + SpaceForAddButton,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        item(key = HEADER_KEY) {
            VaultHeader(documentCount = documents.size)
        }
        items(items = documents, key = { document -> document.id }) { document ->
            val position = documents.indexOf(document)
            AppearingCard(document, position, alreadyShown, onOpenDocument)
        }
    }
}

@Composable
private fun VaultHeader(documentCount: Int) {
    Column(
        modifier = Modifier.padding(bottom = Dimens.SpaceS),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        Text(
            text = stringResource(R.string.vault_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (documentCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.vault_document_count, documentCount, documentCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A card that fades in and rises 8 dp the first time it appears (instant if motion is reduced). */
@Composable
private fun AppearingCard(
    document: DocumentSummary,
    position: Int,
    alreadyShown: MutableSet<String>,
    onOpenDocument: (String) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val animate = !reduceMotion && document.id !in alreadyShown
    var visible by remember(document.id) { mutableStateOf(!animate) }
    LaunchedEffect(document.id) {
        if (!visible) {
            delay(minOf(position * CARD_STAGGER_MILLIS, MAX_CARD_DELAY_MILLIS))
            visible = true
        }
        alreadyShown.add(document.id)
    }
    val riseDistancePixels = with(LocalDensity.current) { CardRiseDistance.roundToPx() }
    val enter: EnterTransition = fadeIn(motionSpec(MotionDurations.LIST_ITEM_MILLIS, reduceMotion)) +
        slideInVertically(motionSpec(MotionDurations.LIST_ITEM_MILLIS, reduceMotion)) { riseDistancePixels }
    AnimatedVisibility(visible = visible, enter = enter) {
        DocumentCardFor(document, onOpenDocument)
    }
}

@Composable
private fun DocumentCardFor(document: DocumentSummary, onOpenDocument: (String) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val dateText = formatAddedDate(document.createdAt, ZoneId.systemDefault(), locale)
    DocumentCard(
        name = document.displayName,
        type = document.type,
        typeLabel = stringResource(labelFor(document.type)),
        addedText = stringResource(R.string.vault_added_on, dateText),
        onClick = { onOpenDocument(document.id) },
    )
}

private const val HEADER_KEY = "vault_header"

package com.heybuddy.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.heybuddy.R
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.LocalObscuredTouchReporter
import com.heybuddy.ui.components.rejectObscuredTouches
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.SecondaryButton
import com.heybuddy.ui.theme.Dimens

/**
 * Everything the viewer screen can ask for.
 *
 * @property onBack Back arrow tapped.
 * @property onRetry "Try again" after a cancelled "Confirm it's you".
 * @property onPreviousPage Previous PDF page.
 * @property onNextPage Next PDF page.
 * @property onRenameClick Rename chosen from the menu.
 * @property onRenameTextChanged The Rename field changed.
 * @property onRenameConfirm Save in the Rename dialog.
 * @property onRenameDismiss Cancel (or Back) in the Rename dialog.
 */
class ViewerActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onPreviousPage: () -> Unit,
    val onNextPage: () -> Unit,
    val onRenameClick: () -> Unit,
    val onRenameTextChanged: (String) -> Unit,
    val onRenameConfirm: () -> Unit,
    val onRenameDismiss: () -> Unit,
)

/**
 * The document viewer: a zoomable photo, or a PDF one page at a time, with a Rename menu.
 *
 * @param uiState What to show.
 * @param actions What happens on each tap.
 */
@Composable
fun ViewerScreen(uiState: ViewerUiState, actions: ViewerActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { ViewerTopBar(uiState, actions) },
        bottomBar = { if (uiState.isPdf && uiState.stage == ViewerStage.READY) PageControls(uiState, actions) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (uiState.stage) {
                ViewerStage.OPENING -> LoadingState(text = stringResource(R.string.viewer_opening))
                ViewerStage.READY -> DocumentArea(uiState)
                ViewerStage.FAILED -> FailedSection(uiState, actions)
            }
        }
    }
    val dialog = uiState.rename
    if (dialog != null) {
        RenameDialog(dialog, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(uiState: ViewerUiState, actions: ViewerActions) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(text = uiState.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            IconButton(onClick = actions.onBack) {
                Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            if (uiState.stage == ViewerStage.READY) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.viewer_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.viewer_rename)) },
                        leadingIcon = { Icon(painter = painterResource(R.drawable.ic_edit), contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            actions.onRenameClick()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/**
 * The document sits on a slightly darker "desk" with a small margin, so a white page or photo edge
 * is always visible, in light and dark.
 */
@Composable
private fun DocumentArea(uiState: ViewerUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(Dimens.SpaceL),
        contentAlignment = Alignment.Center,
    ) {
        val picture = uiState.picture
        if (picture != null && !picture.isRecycled) {
            ZoomableImage(picture = picture, contentDescription = uiState.title)
        }
        if (uiState.pageLoading) {
            CircularProgressIndicator(modifier = Modifier.size(Dimens.MinTouchTarget))
        }
    }
}

@Composable
private fun PageControls(uiState: ViewerUiState, actions: ViewerActions) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Dimens.SpaceS, vertical = Dimens.SpaceXs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onPreviousPage, enabled = uiState.hasPreviousPage) {
                Icon(painter = painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.viewer_previous_page))
            }
            Text(
                text = stringResource(R.string.viewer_page_of, uiState.pageIndex + 1, uiState.pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = actions.onNextPage, enabled = uiState.hasNextPage) {
                Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.viewer_next_page))
            }
        }
    }
}

@Composable
private fun FailedSection(uiState: ViewerUiState, actions: ViewerActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        val message = uiState.errorMessage ?: R.string.viewer_error_other
        MessageBanner(text = stringResource(message), kind = BannerKind.ERROR)
        if (uiState.canRetry) {
            PrimaryButton(text = stringResource(R.string.viewer_retry), onClick = actions.onRetry)
        }
        SecondaryButton(text = stringResource(R.string.viewer_back_to_vault), onClick = actions.onBack)
    }
}

/**
 * The Rename dialog. The Task 1.6 name rules are checked as you type; Save is greyed out while the
 * name breaks a rule. imePadding moves it above the keyboard. Back closes only the dialog.
 */
@Composable
private fun RenameDialog(dialog: RenameDialogState, actions: ViewerActions) {
    AlertDialog(
        onDismissRequest = actions.onRenameDismiss,
        modifier = Modifier.imePadding(),
        title = { Text(text = stringResource(R.string.rename_title)) },
        text = {
            val problem = dialog.problem
            OutlinedTextField(
                value = dialog.text,
                onValueChange = actions.onRenameTextChanged,
                label = { Text(text = stringResource(R.string.add_name_label)) },
                singleLine = true,
                isError = problem != null,
                supportingText = { if (problem != null) Text(text = stringResource(problem.message)) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            val reportBlockedTouch = LocalObscuredTouchReporter.current
            TextButton(
                onClick = actions.onRenameConfirm,
                enabled = dialog.canSave,
                modifier = Modifier.rejectObscuredTouches(reportBlockedTouch),
            ) {
                Text(text = stringResource(R.string.rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onRenameDismiss) {
                Text(text = stringResource(R.string.add_cancel))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

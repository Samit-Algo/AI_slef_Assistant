package com.heybuddy.ui.viewer

import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.annotation.StringRes
import androidx.annotation.PluralsRes
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.heybuddy.R
import com.heybuddy.files.DetectedFileType
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.DangerConfirmDialog
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.LocalObscuredTouchReporter
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.SecondaryButton
import com.heybuddy.ui.components.SuccessNotice
import com.heybuddy.ui.components.popIn
import com.heybuddy.ui.components.rejectObscuredTouches
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT
import kotlinx.coroutines.delay

/** How long "Name changed" / "Page added" stays: long enough to read, short enough not to clutter. */
private const val SUCCESS_NOTICE_MILLIS = 2_000L

/** Every type the file picker may offer; the real type is checked from the bytes afterwards. */
private val ACCEPTED_MIME_TYPES: Array<String> = DetectedFileType.entries.map { type -> type.mimeType }.toTypedArray()

/**
 * Everything the viewer screen can ask for.
 *
 * @property onBack Back arrow tapped.
 * @property onRetry "Try again" after a cancelled "Confirm it's you".
 * @property onPreviousPage Previous page inside a PDF.
 * @property onNextPage Next page inside a PDF.
 * @property onRenameClick Rename chosen from the menu.
 * @property onRenameTextChanged The Rename field changed.
 * @property onRenameConfirm Save in the Rename dialog.
 * @property onRenameDismiss Cancel (or Back) in the Rename dialog.
 * @property onDeleteClick Delete chosen from the menu.
 * @property onDeleteConfirm Delete confirmed in "Delete this document?".
 * @property onDeleteDismiss Cancel (or Back) in "Delete this document?".
 * @property onDocumentPageChanged Another document page was swiped to or chosen with ‹ ›.
 * @property onAddPageClick "Add page" chosen from the menu.
 * @property onAddPageDismiss "Add a page" closed (also just before a picker opens).
 * @property onPagesPicked Files picked for new pages (empty if the user backed out).
 * @property onRemovePageClick "Remove this page" chosen from the menu.
 * @property onRemovePageConfirm Remove confirmed in "Remove this page?".
 * @property onRemovePageDismiss Cancel (or Back) in "Remove this page?".
 * @property onBeforeExternalScreen Called right before a picker opens (one-time auto-lock pass, rule 9).
 * @property onSuccessNoticeShown The success message has been on screen long enough.
 * @property onTakePhotoClick "Take photo" in "Add a page", or null on phones without a camera (hidden).
 * @property onDeleteDetailsChoice "Yes, delete them" (true) or "No, keep them" (false) for the
 *   details read from what is being deleted.
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
    val onDeleteClick: () -> Unit = {},
    val onDeleteConfirm: () -> Unit = {},
    val onDeleteDismiss: () -> Unit = {},
    val onDocumentPageChanged: (Int) -> Unit = {},
    val onAddPageClick: () -> Unit = {},
    val onAddPageDismiss: () -> Unit = {},
    val onPagesPicked: (List<Uri>) -> Unit = {},
    val onRemovePageClick: () -> Unit = {},
    val onRemovePageConfirm: () -> Unit = {},
    val onRemovePageDismiss: () -> Unit = {},
    val onBeforeExternalScreen: () -> Unit = {},
    val onSuccessNoticeShown: () -> Unit = {},
    val onTakePhotoClick: (() -> Unit)? = null,
    val onDeleteDetailsChoice: (Boolean) -> Unit = {},
)

/**
 * The document viewer: one page at a time (swipe or ‹ › between pages), a zoomable photo or a
 * PDF, with Rename, Add page, Remove page and Delete.
 *
 * @param uiState What to show.
 * @param actions What happens on each tap.
 * @param modifier Layout changes from the caller (the app links the viewer to its list card here).
 */
@Composable
fun ViewerScreen(uiState: ViewerUiState, actions: ViewerActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { ViewerTopBar(uiState, actions) },
        bottomBar = { if (uiState.isPdf && uiState.stage == ViewerStage.READY && !uiState.busy) PdfPageControls(uiState, actions) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            val notice = uiState.notice
            if (notice != null) {
                MessageBanner(
                    text = stringResource(notice),
                    kind = BannerKind.ERROR,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                )
            }
            val successNotice = uiState.successNotice
            if (successNotice != null) {
                SuccessNotice(
                    text = stringResource(successNotice),
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
                )
                LaunchedEffect(successNotice) {
                    delay(SUCCESS_NOTICE_MILLIS)
                    actions.onSuccessNoticeShown()
                }
            }
            if (uiState.documentPageCount > 1 && !uiState.busy) {
                DocumentPageBar(uiState, actions)
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                MainArea(uiState, actions)
            }
        }
    }
    ViewerDialogs(uiState, actions)
}

@Composable
private fun MainArea(uiState: ViewerUiState, actions: ViewerActions) {
    when {
        uiState.deleting -> LoadingState(text = stringResource(R.string.viewer_deleting))
        uiState.addingPages -> LoadingState(text = stringResource(R.string.viewer_adding_pages))
        uiState.removingPage -> LoadingState(text = stringResource(R.string.viewer_removing_page))
        !uiState.documentLoaded && uiState.stage == ViewerStage.FAILED -> FailedSection(uiState, actions)
        !uiState.documentLoaded -> LoadingState(text = stringResource(R.string.viewer_opening))
        else -> DocumentPager(uiState, actions)
    }
}

/**
 * The document's pages side by side. Only the page on screen is decrypted: the pages next to it
 * show an empty "desk" until the swipe settles and the viewer opens them.
 */
@Composable
private fun DocumentPager(uiState: ViewerUiState, actions: ViewerActions) {
    val pagerState = rememberPagerState(initialPage = uiState.documentPageIndex) { uiState.documentPageCount }
    FollowViewModelPage(pagerState, uiState.documentPageIndex)
    val onPageSettled by rememberUpdatedState(actions.onDocumentPageChanged)
    LaunchedEffect(pagerState) {
        // Report only settled pages: half-way through a swipe nothing should be decrypted.
        snapshotFlow { pagerState.settledPage }.collect { settled -> onPageSettled(settled) }
    }
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { index ->
        if (index == uiState.documentPageIndex) {
            CurrentPage(uiState, actions)
        } else {
            DeskBackground()
        }
    }
}

/** When the ViewModel moves to another page (‹ › tapped, page added or removed), the pager follows. */
@Composable
private fun FollowViewModelPage(pagerState: PagerState, documentPageIndex: Int) {
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(documentPageIndex) {
        if (pagerState.currentPage == documentPageIndex) return@LaunchedEffect
        if (reduceMotion) pagerState.scrollToPage(documentPageIndex) else pagerState.animateScrollToPage(documentPageIndex)
    }
}

@Composable
private fun CurrentPage(uiState: ViewerUiState, actions: ViewerActions) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (uiState.stage) {
            ViewerStage.OPENING -> LoadingState(text = stringResource(R.string.viewer_opening))
            ViewerStage.READY -> DocumentArea(uiState)
            ViewerStage.FAILED -> FailedSection(uiState, actions)
        }
    }
}

@Composable
private fun DeskBackground() {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(uiState: ViewerUiState, actions: ViewerActions) {
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
            if (uiState.documentLoaded && !uiState.busy) {
                ViewerMenu(uiState, actions)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun ViewerMenu(uiState: ViewerUiState, actions: ViewerActions) {
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(painter = painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.viewer_menu))
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        val closeThen = { action: () -> Unit ->
            menuOpen = false
            action()
        }
        MenuItem(R.string.viewer_rename, R.drawable.ic_edit) { closeThen(actions.onRenameClick) }
        if (uiState.documentPageCount < MAX_PAGES_PER_DOCUMENT) {
            MenuItem(R.string.viewer_add_page, R.drawable.ic_add) { closeThen(actions.onAddPageClick) }
        }
        // A document always keeps at least one page; to remove the last one, delete the document.
        if (uiState.documentPageCount > 1) {
            MenuItem(R.string.viewer_remove_page, R.drawable.ic_close) { closeThen(actions.onRemovePageClick) }
        }
        MenuItem(R.string.viewer_delete, R.drawable.ic_delete, danger = true) { closeThen(actions.onDeleteClick) }
    }
}

@Composable
private fun MenuItem(label: Int, icon: Int, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(text = stringResource(label), color = color) },
        leadingIcon = { Icon(painter = painterResource(icon), contentDescription = null, tint = color) },
        onClick = onClick,
    )
}

/** "‹ Page 1 of 2 ›" for the document's pages. Swiping does the same; these buttons also work with TalkBack. */
@Composable
private fun DocumentPageBar(uiState: ViewerUiState, actions: ViewerActions) {
    val index = uiState.documentPageIndex
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.SpaceS),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.onDocumentPageChanged(index - 1) }, enabled = index > 0) {
            Icon(painter = painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.viewer_previous_page))
        }
        Text(
            text = stringResource(R.string.viewer_page_of, index + 1, uiState.documentPageCount),
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = { actions.onDocumentPageChanged(index + 1) }, enabled = index < uiState.documentPageCount - 1) {
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.viewer_next_page))
        }
    }
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

/** ‹ › for the pages INSIDE a PDF. Says "PDF page" when the document also has its own pages. */
@Composable
private fun PdfPageControls(uiState: ViewerUiState, actions: ViewerActions) {
    val label = if (uiState.documentPageCount > 1) R.string.viewer_pdf_page_of else R.string.viewer_page_of
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
                Icon(painter = painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.viewer_previous_pdf_page))
            }
            Text(
                text = stringResource(label, uiState.pageIndex + 1, uiState.pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = actions.onNextPage, enabled = uiState.hasNextPage) {
                Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.viewer_next_pdf_page))
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

@Composable
private fun ViewerDialogs(uiState: ViewerUiState, actions: ViewerActions) {
    val dialog = uiState.rename
    if (dialog != null) {
        RenameDialog(dialog, actions)
    }
    if (uiState.deleteConfirmOpen) {
        DangerConfirmDialog(
            title = stringResource(R.string.delete_one_title),
            body = stringResource(R.string.delete_one_body),
            confirmText = stringResource(R.string.delete_one_confirm),
            onCancel = actions.onDeleteDismiss,
            onConfirm = actions.onDeleteConfirm,
            extraContent = detailsChoice(uiState, R.plurals.delete_details_from_document, actions),
        )
    }
    if (uiState.removePageConfirmOpen) {
        DangerConfirmDialog(
            title = stringResource(R.string.remove_page_title),
            body = stringResource(R.string.remove_page_body, uiState.documentPageIndex + 1),
            confirmText = stringResource(R.string.remove_page_confirm),
            onCancel = actions.onRemovePageDismiss,
            onConfirm = actions.onRemovePageConfirm,
            extraContent = detailsChoice(uiState, R.plurals.delete_details_from_page, actions),
        )
    }
    // Always composed, so the picker result still arrives after the dialog has closed.
    AddPageChoice(open = uiState.addPageChoiceOpen, actions = actions)
}

/**
 * "Also delete the N details read from this…?" (spec 7.10), Yes selected by default. Only when
 * there are details; otherwise the dialog stays exactly as before.
 */
private fun detailsChoice(uiState: ViewerUiState, @PluralsRes question: Int, actions: ViewerActions): (@Composable () -> Unit)? {
    val count = uiState.detailsOnDelete
    if (count == 0) return null
    return {
        Column(modifier = Modifier.selectableGroup()) {
            Text(text = pluralStringResource(question, count, count), style = MaterialTheme.typography.bodyMedium)
            DetailsChoiceRow(R.string.delete_details_yes, selected = uiState.deleteDetailsToo) { actions.onDeleteDetailsChoice(true) }
            DetailsChoiceRow(R.string.delete_details_no, selected = !uiState.deleteDetailsToo) { actions.onDeleteDetailsChoice(false) }
        }
    }
}

@Composable
private fun DetailsChoiceRow(@StringRes label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Dimens.SpaceS))
    }
}

/**
 * "Add a page": the camera, photos or a file. The pickers open straight from here; both grant
 * one-time access only.
 */
@Composable
private fun AddPageChoice(open: Boolean, actions: ViewerActions) {
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PAGES_PER_DOCUMENT),
        actions.onPagesPicked,
    )
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), actions.onPagesPicked)
    if (!open) return
    val pickPhotos = {
        actions.onAddPageDismiss()
        actions.onBeforeExternalScreen()
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val pickFiles = {
        actions.onAddPageDismiss()
        actions.onBeforeExternalScreen()
        filePicker.launch(ACCEPTED_MIME_TYPES)
    }
    AlertDialog(
        onDismissRequest = actions.onAddPageDismiss,
        modifier = Modifier.popIn(),
        title = { Text(text = stringResource(R.string.add_page_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                Text(text = stringResource(R.string.add_page_dialog_body))
                AddPageChoiceButtons(actions.onTakePhotoClick, pickPhotos, pickFiles, actions.onAddPageDismiss)
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onAddPageDismiss) {
                Text(text = stringResource(R.string.add_cancel))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** One full-width choice per source; "Take photo" only on phones with a camera. */
@Composable
private fun AddPageChoiceButtons(takePhoto: (() -> Unit)?, pickPhotos: () -> Unit, pickFiles: () -> Unit, close: () -> Unit) {
    if (takePhoto != null) {
        SecondaryButton(
            text = stringResource(R.string.add_page_dialog_camera),
            onClick = {
                close()
                takePhoto()
            },
            icon = R.drawable.ic_photo_camera,
        )
    }
    SecondaryButton(text = stringResource(R.string.add_page_dialog_photos), onClick = pickPhotos, icon = R.drawable.ic_image)
    SecondaryButton(text = stringResource(R.string.add_page_dialog_files), onClick = pickFiles, icon = R.drawable.ic_picture_as_pdf)
}

/**
 * The Rename dialog. The Task 1.6 name rules are checked as you type; Save is greyed out while the
 * name breaks a rule. imePadding moves it above the keyboard. Back closes only the dialog.
 */
@Composable
private fun RenameDialog(dialog: RenameDialogState, actions: ViewerActions) {
    AlertDialog(
        onDismissRequest = actions.onRenameDismiss,
        modifier = Modifier.imePadding().popIn(),
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

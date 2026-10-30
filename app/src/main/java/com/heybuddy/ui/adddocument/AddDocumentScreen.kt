package com.heybuddy.ui.adddocument

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.data.DocumentType
import com.heybuddy.files.DetectedFileType
import com.heybuddy.ui.components.AnimatedCheckMark
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.QuietButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.components.SecondaryButton
import com.heybuddy.ui.components.TypeBadge
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT

/** Every type the picker may offer; the real type is checked from the bytes afterwards. */
private val ACCEPTED_MIME_TYPES: Array<String> = DetectedFileType.entries.map { type -> type.mimeType }.toTypedArray()

/**
 * Thumbnail box on each page row: a small portrait shape. Narrow on purpose: with three 48 dp
 * buttons beside it, "Page 10" must still fit on a 360 dp wide phone.
 */
private val ThumbnailWidth = 56.dp
private val ThumbnailHeight = 72.dp

/**
 * Everything the Add document screen can ask for.
 *
 * @property onFilesPicked Called with the picked files (empty if the user backed out).
 * @property onRemovePage Called with a page's id when its Remove button is tapped.
 * @property onMovePageUp Called with a page's place when "move up" is tapped.
 * @property onMovePageDown Called with a page's place when "move down" is tapped.
 * @property onTypeChosen Called when a type is picked from the dropdown.
 * @property onNameChanged Called on every change to the name field.
 * @property onSaveClick Called when the user taps Save.
 * @property onClose Called on back, Cancel and Done.
 * @property onBeforeExternalScreen Called right before a picker opens, so auto-lock knows the app
 *   is not really being left (one-time 2-minute pass, rule 9).
 * @property onTakePhoto Opens the in-app camera, or null on phones without a camera (button hidden).
 * @property onPickPhotosNowHandled Called once the photo picker asked for by "Choose a photo
 *   instead" (in the camera) has been opened.
 */
class AddDocumentActions(
    val onFilesPicked: (List<Uri>) -> Unit,
    val onRemovePage: (String) -> Unit,
    val onMovePageUp: (Int) -> Unit,
    val onMovePageDown: (Int) -> Unit,
    val onTypeChosen: (DocumentType) -> Unit,
    val onNameChanged: (String) -> Unit,
    val onSaveClick: () -> Unit,
    val onClose: () -> Unit,
    val onBeforeExternalScreen: () -> Unit = {},
    val onTakePhoto: (() -> Unit)? = null,
    val onPickPhotosNowHandled: () -> Unit = {},
)

/**
 * Opens the two pickers and the camera. The pickers grant one-time access only; nothing here asks
 * to keep it. [takePhoto] is null on phones without a camera.
 */
private class Pickers(val pickPhotos: () -> Unit, val pickFiles: () -> Unit, val takePhoto: (() -> Unit)?)

/**
 * The Add document screen: pick pages, put them in order, choose type and name, save.
 *
 * @param uiState What to show.
 * @param actions What happens on each tap.
 * @param pickPhotosNow True to open the photo picker straight away ("Choose a photo instead" in the camera).
 */
@Composable
fun AddDocumentScreen(uiState: AddDocumentUiState, actions: AddDocumentActions, pickPhotosNow: Boolean = false) {
    val pickers = rememberPickers(actions)
    LaunchedEffect(pickPhotosNow) {
        if (pickPhotosNow) {
            actions.onPickPhotosNowHandled()
            pickers.pickPhotos()
        }
    }
    ScreenScaffold(topBar = { AddDocumentTopBar(onBack = actions.onClose) }) {
        when (uiState.stage) {
            AddStage.CHOOSING -> ChooseFileSection(pickers)
            AddStage.READING -> LoadingState(text = stringResource(R.string.add_reading))
            AddStage.READY -> ReadySection(uiState, actions, pickers)
            AddStage.SAVING -> LoadingState(text = stringResource(R.string.add_saving))
            AddStage.SAVED -> SavedSection(onDone = actions.onClose)
        }
        val errorMessage = uiState.errorMessage
        if (errorMessage != null) {
            MessageBanner(text = stringResource(errorMessage), kind = BannerKind.ERROR)
        }
    }
}

@Composable
private fun rememberPickers(actions: AddDocumentActions): Pickers {
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PAGES_PER_DOCUMENT),
        actions.onFilesPicked,
    )
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), actions.onFilesPicked)
    return Pickers(
        pickPhotos = {
            actions.onBeforeExternalScreen()
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        pickFiles = {
            actions.onBeforeExternalScreen()
            filePicker.launch(ACCEPTED_MIME_TYPES)
        },
        takePhoto = actions.onTakePhoto,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDocumentTopBar(onBack: () -> Unit) {
    // TopAppBar pads itself for the status bar, so the title is never hidden behind it.
    TopAppBar(
        title = { Text(text = stringResource(R.string.add_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun ChooseFileSection(pickers: Pickers) {
    Text(
        text = stringResource(R.string.add_intro),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val takePhoto = pickers.takePhoto
    if (takePhoto != null) {
        // The camera is the quickest way for a paper document, so it comes first (Task 2.4).
        PrimaryButton(text = stringResource(R.string.add_take_photo), onClick = takePhoto, icon = R.drawable.ic_photo_camera)
        SecondaryButton(text = stringResource(R.string.add_choose_photo), onClick = pickers.pickPhotos, icon = R.drawable.ic_image)
    } else {
        PrimaryButton(text = stringResource(R.string.add_choose_photo), onClick = pickers.pickPhotos, icon = R.drawable.ic_image)
    }
    SecondaryButton(text = stringResource(R.string.add_choose_file), onClick = pickers.pickFiles, icon = R.drawable.ic_picture_as_pdf)
}

@Composable
private fun ColumnScope.ReadySection(uiState: AddDocumentUiState, actions: AddDocumentActions, pickers: Pickers) {
    PageList(uiState.pages, actions)
    AddMorePages(uiState.canAddPage, pickers)
    TypeDropdown(selected = uiState.documentType, onTypeChosen = actions.onTypeChosen)
    NameField(uiState, actions.onNameChanged)
    PrimaryButton(
        text = stringResource(R.string.add_save_button),
        onClick = actions.onSaveClick,
        enabled = uiState.canSave,
        icon = R.drawable.ic_lock,
        sensitive = true,
    )
    QuietButton(text = stringResource(R.string.add_cancel), onClick = actions.onClose, modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
private fun PageList(pages: List<DraftPage>, actions: AddDocumentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
        Text(
            text = stringResource(R.string.add_pages_title, pages.size),
            style = MaterialTheme.typography.titleMedium,
        )
        for ((index, page) in pages.withIndex()) {
            PageRow(page = page, index = index, isLast = index == pages.lastIndex, actions = actions)
        }
    }
}

/**
 * One page: its thumbnail, "Page 1", and buttons to move it or remove it. Buttons rather than
 * drag-and-drop, so the order can also be changed with TalkBack.
 */
@Composable
private fun PageRow(page: DraftPage, index: Int, isLast: Boolean, actions: AddDocumentActions) {
    val pageNumber = index + 1
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.BorderWidth, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
        ) {
            PageThumbnail(page, pageNumber)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.add_page_label, pageNumber), style = MaterialTheme.typography.titleSmall)
                if (page.isPdf) {
                    Text(
                        text = stringResource(R.string.add_page_is_pdf),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PageRowButton(R.drawable.ic_arrow_upward, stringResource(R.string.add_move_page_up, pageNumber), enabled = index > 0) {
                actions.onMovePageUp(index)
            }
            PageRowButton(R.drawable.ic_arrow_downward, stringResource(R.string.add_move_page_down, pageNumber), enabled = !isLast) {
                actions.onMovePageDown(index)
            }
            PageRowButton(R.drawable.ic_close, stringResource(R.string.add_remove_page, pageNumber), enabled = true) {
                actions.onRemovePage(page.pageId)
            }
        }
    }
}

@Composable
private fun PageThumbnail(page: DraftPage, pageNumber: Int) {
    Box(
        modifier = Modifier
            .size(width = ThumbnailWidth, height = ThumbnailHeight)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val thumbnail = page.thumbnail
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = stringResource(R.string.add_preview_description, pageNumber),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = ThumbnailWidth, height = ThumbnailHeight),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_picture_as_pdf),
                contentDescription = stringResource(R.string.add_preview_unavailable, pageNumber),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageRowButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painter = painterResource(icon), contentDescription = description)
    }
}

@Composable
private fun AddMorePages(canAddPage: Boolean, pickers: Pickers) {
    if (!canAddPage) {
        Text(
            text = stringResource(R.string.add_page_limit_reached, MAX_PAGES_PER_DOCUMENT),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val takePhoto = pickers.takePhoto
    if (takePhoto != null) {
        SecondaryButton(text = stringResource(R.string.add_take_another_photo), onClick = takePhoto, icon = R.drawable.ic_photo_camera)
    }
    SecondaryButton(text = stringResource(R.string.add_more_photos), onClick = pickers.pickPhotos, icon = R.drawable.ic_add)
    SecondaryButton(text = stringResource(R.string.add_more_files), onClick = pickers.pickFiles, icon = R.drawable.ic_picture_as_pdf)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(selected: DocumentType?, onTypeChosen: (DocumentType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (selected != null) stringResource(labelFor(selected)) else ""
    // The chosen type's badge sits in the field; before a choice there is no badge.
    val selectedBadge: (@Composable () -> Unit)? = selected?.let { type ->
        { TypeBadge(type = type, modifier = Modifier.padding(start = Dimens.SpaceS)) }
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { isOpen -> expanded = isOpen }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.add_type_label)) },
            placeholder = { Text(text = stringResource(R.string.add_type_placeholder)) },
            leadingIcon = selectedBadge,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (type in DocumentType.entries) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(labelFor(type)), style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { TypeBadge(type = type) },
                    onClick = {
                        onTypeChosen(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NameField(uiState: AddDocumentUiState, onNameChanged: (String) -> Unit) {
    // Before a type is chosen the field is empty on purpose; do not shout "empty name" yet.
    val visibleProblem = if (uiState.documentType != null) uiState.nameProblem else null
    OutlinedTextField(
        value = uiState.name,
        onValueChange = onNameChanged,
        label = { Text(text = stringResource(R.string.add_name_label)) },
        singleLine = true,
        isError = visibleProblem != null,
        supportingText = {
            if (visibleProblem != null) Text(text = stringResource(visibleProblem.message))
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SavedSection(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        // Draws itself, with a light confirm haptic (spec 5.14, item 8).
        AnimatedCheckMark(size = Dimens.HeroIconCircle)
        Text(text = stringResource(R.string.add_saved_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.add_saved_note),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    PrimaryButton(text = stringResource(R.string.add_done), onClick = onDone)
}

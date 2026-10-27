package com.heybuddy.ui.adddocument

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.data.DocumentType
import com.heybuddy.files.DetectedFileType
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.HeroIcon
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.QuietButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.components.SecondaryButton
import com.heybuddy.ui.components.TypeBadge
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.performConfirmHaptic

/** Every type the picker may offer; the real type is checked from the bytes afterwards. */
private val ACCEPTED_MIME_TYPES: Array<String> = DetectedFileType.entries.map { type -> type.mimeType }.toTypedArray()

private val PreviewMaxHeight = 320.dp

/**
 * The Add document screen: pick a file, see it, choose type and name, save.
 *
 * @param uiState What to show.
 * @param onFilePicked Called with the picked file (null if the user backed out).
 * @param onTypeChosen Called when a type is picked from the dropdown.
 * @param onNameChanged Called on every change to the name field.
 * @param onSaveClick Called when the user taps Save.
 * @param onClose Called on back, Cancel and Done.
 * @param onBeforeExternalScreen Called right before a picker opens, so auto-lock knows the app
 *   is not really being left (one-time 2-minute pass, rule 9).
 */
@Composable
fun AddDocumentScreen(
    uiState: AddDocumentUiState,
    onFilePicked: (Uri?) -> Unit,
    onTypeChosen: (DocumentType) -> Unit,
    onNameChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onClose: () -> Unit,
    onBeforeExternalScreen: () -> Unit = {},
) {
    // Both pickers grant one-time access only; nothing here asks to keep it.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), onFilePicked)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), onFilePicked)
    val pickPhoto = {
        onBeforeExternalScreen()
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val pickFile = {
        onBeforeExternalScreen()
        filePicker.launch(ACCEPTED_MIME_TYPES)
    }

    ScreenScaffold(topBar = { AddDocumentTopBar(onBack = onClose) }) {
        when (uiState.stage) {
            AddStage.CHOOSING -> ChooseFileSection(pickPhoto, pickFile)
            AddStage.READING -> LoadingState(text = stringResource(R.string.add_reading))
            AddStage.READY -> ReadySection(uiState, onTypeChosen, onNameChanged, onSaveClick, pickFile, onClose)
            AddStage.SAVING -> LoadingState(text = stringResource(R.string.add_saving))
            AddStage.SAVED -> SavedSection(onDone = onClose)
        }
        val errorMessage = uiState.errorMessage
        if (errorMessage != null) {
            MessageBanner(text = stringResource(errorMessage), kind = BannerKind.ERROR)
        }
    }
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
private fun ChooseFileSection(onPickPhoto: () -> Unit, onPickFile: () -> Unit) {
    Text(
        text = stringResource(R.string.add_intro),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryButton(text = stringResource(R.string.add_choose_photo), onClick = onPickPhoto, icon = R.drawable.ic_image)
    SecondaryButton(text = stringResource(R.string.add_choose_file), onClick = onPickFile, icon = R.drawable.ic_picture_as_pdf)
}

@Composable
private fun ColumnScope.ReadySection(
    uiState: AddDocumentUiState,
    onTypeChosen: (DocumentType) -> Unit,
    onNameChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onPickOtherFile: () -> Unit,
    onClose: () -> Unit,
) {
    PreviewCard(uiState)
    TypeDropdown(selected = uiState.documentType, onTypeChosen = onTypeChosen)
    NameField(uiState, onNameChanged)
    PrimaryButton(
        text = stringResource(R.string.add_save_button),
        onClick = onSaveClick,
        enabled = uiState.canSave,
        icon = R.drawable.ic_lock,
        sensitive = true,
    )
    SecondaryButton(text = stringResource(R.string.add_choose_other_file), onClick = onPickOtherFile)
    QuietButton(text = stringResource(R.string.add_cancel), onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally))
}

/** The picked file inside a quiet bordered card, so photos of any colour sit calmly on the page. */
@Composable
private fun PreviewCard(uiState: AddDocumentUiState) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.BorderWidth, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(Dimens.SpaceM), contentAlignment = Alignment.Center) {
            val preview = uiState.preview
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = stringResource(R.string.add_preview_description),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = PreviewMaxHeight),
                )
            } else if (uiState.isPdf) {
                PdfWithoutPreview()
            }
        }
    }
}

@Composable
private fun PdfWithoutPreview() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
    ) {
        HeroIcon(icon = R.drawable.ic_picture_as_pdf)
        Text(
            text = stringResource(R.string.add_preview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
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
    val view = LocalView.current
    LaunchedEffect(Unit) { performConfirmHaptic(view) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        HeroIcon(
            icon = R.drawable.ic_check_circle,
            tint = MaterialTheme.colorScheme.tertiary,
            container = MaterialTheme.colorScheme.tertiaryContainer,
        )
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

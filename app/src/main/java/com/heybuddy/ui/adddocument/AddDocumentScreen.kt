package com.heybuddy.ui.adddocument

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.data.DocumentType
import com.heybuddy.files.DetectedFileType

/** Every type the picker may offer; the real type is checked from the bytes afterwards. */
private val ACCEPTED_MIME_TYPES: Array<String> = DetectedFileType.entries.map { type -> type.mimeType }.toTypedArray()

/**
 * The Add document screen: pick a file, see it, choose type and name, save.
 *
 * @param uiState What to show.
 * @param onFilePicked Called with the picked file (null if the user backed out).
 * @param onTypeChosen Called when a type is picked from the dropdown.
 * @param onNameChanged Called on every change to the name field.
 * @param onSaveClick Called when the user taps Save.
 * @param onClose Called on Cancel and Done.
 */
@Composable
fun AddDocumentScreen(
    uiState: AddDocumentUiState,
    onFilePicked: (Uri?) -> Unit,
    onTypeChosen: (DocumentType) -> Unit,
    onNameChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onClose: () -> Unit,
) {
    // Both pickers grant one-time access only; nothing here asks to keep it.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), onFilePicked)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), onFilePicked)
    val pickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val pickFile = { filePicker.launch(ACCEPTED_MIME_TYPES) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall)
            when (uiState.stage) {
                AddStage.CHOOSING -> ChooseFileSection(pickPhoto, pickFile, onClose)
                AddStage.READING -> ProgressSection(R.string.add_reading)
                AddStage.READY -> ReadySection(uiState, onTypeChosen, onNameChanged, onSaveClick, pickFile, onClose)
                AddStage.SAVING -> ProgressSection(R.string.add_saving)
                AddStage.SAVED -> SavedSection(onClose)
            }
            ErrorMessage(uiState.errorMessage)
        }
    }
}

@Composable
private fun ChooseFileSection(onPickPhoto: () -> Unit, onPickFile: () -> Unit, onClose: () -> Unit) {
    Text(text = stringResource(R.string.add_intro), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.add_choose_photo))
    }
    OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.add_choose_file))
    }
    TextButton(onClick = onClose) {
        Text(text = stringResource(R.string.add_cancel))
    }
}

@Composable
private fun ReadySection(
    uiState: AddDocumentUiState,
    onTypeChosen: (DocumentType) -> Unit,
    onNameChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onPickOtherFile: () -> Unit,
    onClose: () -> Unit,
) {
    PreviewSection(uiState)
    TypeDropdown(selected = uiState.documentType, onTypeChosen = onTypeChosen)
    NameField(uiState, onNameChanged)
    Button(onClick = onSaveClick, enabled = uiState.canSave, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.add_save_button))
    }
    OutlinedButton(onClick = onPickOtherFile, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.add_choose_other_file))
    }
    TextButton(onClick = onClose) {
        Text(text = stringResource(R.string.add_cancel))
    }
}

@Composable
private fun PreviewSection(uiState: AddDocumentUiState) {
    val preview = uiState.preview
    if (preview != null) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = stringResource(R.string.add_preview_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        )
        return
    }
    if (uiState.isPdf) {
        Box(
            modifier = Modifier.size(96.dp).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.add_pdf_badge), style = MaterialTheme.typography.titleLarge)
        }
        Text(text = stringResource(R.string.add_preview_unavailable), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(selected: DocumentType?, onTypeChosen: (DocumentType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (selected != null) stringResource(labelFor(selected)) else ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { isOpen -> expanded = isOpen }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(text = stringResource(R.string.add_type_label)) },
            placeholder = { Text(text = stringResource(R.string.add_type_placeholder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (type in DocumentType.entries) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(labelFor(type))) },
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
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProgressSection(messageRes: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(text = stringResource(messageRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SavedSection(onDone: () -> Unit) {
    Text(text = stringResource(R.string.add_saved_note), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.add_done))
    }
}

@Composable
private fun ErrorMessage(messageRes: Int?) {
    if (messageRes == null) return
    Text(
        text = stringResource(messageRes),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Start,
    )
}

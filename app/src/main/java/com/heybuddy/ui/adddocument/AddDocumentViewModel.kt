package com.heybuddy.ui.adddocument

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DisplayNameResult
import com.heybuddy.data.DisplayNameValidator
import com.heybuddy.data.DocumentType
import com.heybuddy.files.FileTooLargeException
import com.heybuddy.files.FileTypeDetector
import com.heybuddy.files.PickedFile
import com.heybuddy.files.PickedFileReader
import com.heybuddy.security.BiometricReAuthPrompt
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.LockStatus
import com.heybuddy.security.MasterKeyAccess
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.MAX_FILE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Which step of adding a document the screen is on. */
enum class AddStage {
    CHOOSING,
    READING,
    READY,
    SAVING,
    SAVED,
}

/**
 * What the Add document screen shows.
 *
 * @property stage The current step.
 * @property preview The picked file's preview, or null (for a PDF that cannot be previewed).
 * @property isPdf True when the picked file is a PDF (shows a PDF badge without a preview).
 * @property documentType The type the user chose, or null before choosing.
 * @property name The name in the text field.
 * @property nameProblem Why the current name is not allowed, or null if it is fine.
 * @property errorMessage The last failure message, or null.
 */
data class AddDocumentUiState(
    val stage: AddStage = AddStage.CHOOSING,
    val preview: Bitmap? = null,
    val isPdf: Boolean = false,
    val documentType: DocumentType? = null,
    val name: String = "",
    val nameProblem: DisplayNameProblem? = null,
    @StringRes val errorMessage: Int? = null,
) {
    /** Save is allowed once a type is chosen and the name passes every rule. */
    val canSave: Boolean
        get() = stage == AddStage.READY && documentType != null && nameProblem == null
}

/**
 * Runs the Add document screen: pick, check, preview, name, save.
 *
 * The picked file is read ONCE into memory; the type check, preview and save all use those exact
 * bytes. They are wiped after saving, on cancel, when another file is picked, and on lock.
 *
 * @param appContext The application context (for the content resolver and default names).
 * @param saver Saves the file and turns errors into outcomes.
 * @param unlocker Shows "Confirm it's you" when the unlock window has expired.
 * @param lockState The app's lock; the picked bytes are wiped as soon as it locks.
 */
class AddDocumentViewModel(
    private val appContext: Context,
    private val saver: AddDocumentSaver,
    private val unlocker: BiometricUnlocker,
    lockState: VaultLockState,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(AddDocumentUiState())

    /** The current screen state. */
    val uiState: StateFlow<AddDocumentUiState> = mutableUiState.asStateFlow()

    // Plaintext of the picked file. Only touched on the main thread.
    private var pickedFile: PickedFile? = null

    // True once the user typed their own name, so changing the type no longer replaces it.
    private var nameEditedByUser = false

    init {
        viewModelScope.launch {
            lockState.status.filter { status -> status == LockStatus.LOCKED }.collect { reset() }
        }
    }

    /**
     * Called with the file the user picked (or null if they backed out of the picker).
     *
     * @param uri The picked file. Read once now; no long-term access is kept.
     */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) return
        discardPickedFile()
        mutableUiState.value = AddDocumentUiState(stage = AddStage.READING)
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadPickedFile(uri) }
            showLoadedFile(loaded)
        }
    }

    /**
     * Called when the user picks a document type. Suggests a name unless they typed their own.
     *
     * @param type The chosen type.
     */
    fun onTypeChosen(type: DocumentType) {
        val current = mutableUiState.value
        val name = if (nameEditedByUser) current.name else appContext.getString(defaultNameFor(type))
        mutableUiState.value = current.copy(documentType = type, name = name, nameProblem = problemIn(name))
    }

    /**
     * Called on every change to the name field.
     *
     * @param name What the field now contains.
     */
    fun onNameChanged(name: String) {
        nameEditedByUser = true
        mutableUiState.value = mutableUiState.value.copy(name = name, nameProblem = problemIn(name))
    }

    /**
     * Saves the picked file. Asks "Confirm it's you" once if the unlock window has expired.
     *
     * @param activity Shows the fingerprint/PIN prompt if needed.
     */
    fun onSaveClicked(activity: FragmentActivity) {
        val file = pickedFile ?: return
        val state = mutableUiState.value
        val type = state.documentType ?: return
        if (!state.canSave) return
        mutableUiState.value = state.copy(stage = AddStage.SAVING, errorMessage = null)
        val keyAccess = MasterKeyAccess(BiometricReAuthPrompt(activity, unlocker))
        viewModelScope.launch {
            val outcome = saver.save(file, type, state.name, keyAccess)
            showSaveOutcome(outcome)
        }
    }

    /** Called from "Cancel", "Done" and when leaving the screen: forget everything. */
    fun reset() {
        discardPickedFile()
        nameEditedByUser = false
        mutableUiState.value = AddDocumentUiState()
    }

    override fun onCleared() {
        discardPickedFile()
    }

    private fun showSaveOutcome(outcome: SaveOutcome) {
        val current = mutableUiState.value
        // The vault may have locked during the save; reset() has then already cleared the screen.
        if (current.stage != AddStage.SAVING) return
        mutableUiState.value = when (outcome) {
            is SaveOutcome.Saved -> {
                discardPickedFile()
                current.copy(stage = AddStage.SAVED, preview = null)
            }
            is SaveOutcome.NameRejected -> current.copy(stage = AddStage.READY, nameProblem = outcome.problem)
            is SaveOutcome.Failed -> current.copy(stage = AddStage.READY, errorMessage = outcome.failure.message)
        }
    }

    private fun showLoadedFile(loaded: LoadResult) {
        // The vault may have locked while reading; then the bytes must not be kept.
        if (mutableUiState.value.stage != AddStage.READING) {
            loaded.file?.wipe()
            return
        }
        if (loaded.failure != null) {
            mutableUiState.value = AddDocumentUiState(errorMessage = loaded.failure.message)
            return
        }
        pickedFile = loaded.file
        val type = mutableUiState.value.documentType
        mutableUiState.value = AddDocumentUiState(
            stage = AddStage.READY,
            preview = loaded.preview,
            isPdf = loaded.file?.type?.isImage == false,
            documentType = type,
        )
    }

    /** Reads, type-checks and previews the file. Wipes the bytes itself on any failure. */
    private fun loadPickedFile(uri: Uri): LoadResult {
        val contentResolver = appContext.contentResolver
        val bytes = try {
            readOnce(contentResolver, uri)
        } catch (tooLarge: FileTooLargeException) {
            return LoadResult.failed(AddDocumentFailure.TOO_LARGE)
        } catch (unreadable: IOException) {
            return LoadResult.failed(AddDocumentFailure.UNREADABLE_FILE)
        } catch (unreadable: SecurityException) {
            return LoadResult.failed(AddDocumentFailure.UNREADABLE_FILE)
        }
        val type = FileTypeDetector.detect(bytes)
        if (type == null) {
            bytes.fill(0)
            return LoadResult.failed(AddDocumentFailure.WRONG_TYPE)
        }
        val file = PickedFile(bytes, type)
        return buildPreview(contentResolver, uri, file)
    }

    private fun buildPreview(contentResolver: ContentResolver, uri: Uri, file: PickedFile): LoadResult {
        if (!file.type.isImage) {
            val pdfPreview = FilePreview.renderFirstPdfPage(contentResolver, uri)
            return LoadResult(file = file, preview = pdfPreview, failure = null)
        }
        return try {
            val imagePreview = FilePreview.decodeImage(file.bytes)
            LoadResult(file = file, preview = imagePreview, failure = null)
        } catch (damaged: DamagedImageException) {
            file.wipe()
            LoadResult.failed(AddDocumentFailure.DAMAGED_IMAGE)
        }
    }

    private fun readOnce(contentResolver: ContentResolver, uri: Uri): ByteArray {
        val sizeHint = reportedSize(contentResolver, uri)
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Picker gave no data")
        input.use { stream ->
            return PickedFileReader.readAll(stream, MAX_FILE_BYTES, sizeHint)
        }
    }

    /** The size the picker reports. Only a hint for the buffer size; never trusted as a limit. */
    private fun reportedSize(contentResolver: ContentResolver, uri: Uri): Long {
        val columns = arrayOf(OpenableColumns.SIZE)
        contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0L
    }

    private fun problemIn(name: String): DisplayNameProblem? {
        val result = DisplayNameValidator.validate(name)
        return (result as? DisplayNameResult.Invalid)?.problem
    }

    private fun discardPickedFile() {
        pickedFile?.wipe()
        pickedFile = null
    }

    /** What loading a picked file produced: the file and preview, or a failure. */
    private class LoadResult(val file: PickedFile?, val preview: Bitmap?, val failure: AddDocumentFailure?) {
        companion object {
            fun failed(failure: AddDocumentFailure) = LoadResult(file = null, preview = null, failure = failure)
        }
    }
}

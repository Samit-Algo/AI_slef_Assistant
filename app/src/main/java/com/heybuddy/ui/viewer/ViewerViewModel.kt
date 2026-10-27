package com.heybuddy.ui.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heybuddy.R
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DisplayNameResult
import com.heybuddy.data.DisplayNameValidator
import com.heybuddy.data.DocumentNotFoundException
import com.heybuddy.data.InvalidDisplayNameException
import com.heybuddy.data.OpenedDocument
import com.heybuddy.data.VaultLockedException
import com.heybuddy.data.VaultRepository
import com.heybuddy.files.TempFileCleaner
import com.heybuddy.security.BiometricReAuthPrompt
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.DocumentUnreadableException
import com.heybuddy.security.KeyAccessResult
import com.heybuddy.security.LockStatus
import com.heybuddy.security.MasterKeyAccess
import com.heybuddy.security.ReAuthPrompt
import com.heybuddy.security.VaultLockState
import com.heybuddy.ui.VaultNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer

/** Where the viewer is. */
enum class ViewerStage {
    OPENING,
    READY,
    FAILED,
}

/**
 * The Rename dialog's state.
 *
 * @property text What the name field contains.
 * @property problem Why the text is not allowed, or null if it is fine.
 * @property saving True while the new name is being saved.
 */
data class RenameDialogState(
    val text: String,
    val problem: DisplayNameProblem? = null,
    val saving: Boolean = false,
) {
    /** Save is allowed when the name passes every rule and nothing is being saved. */
    val canSave: Boolean
        get() = problem == null && !saving
}

/**
 * What the viewer shows.
 *
 * @property stage Opening, ready or failed.
 * @property title The document's name.
 * @property isPdf True for a PDF (shows page controls).
 * @property picture The photo or the current PDF page, or null.
 * @property pageIndex The current PDF page, starting at 0.
 * @property pageCount Number of PDF pages (0 for photos).
 * @property pageLoading True while the next PDF page is being drawn.
 * @property errorMessage Why it failed, or null.
 * @property canRetry True when the failure was a cancelled "Confirm it's you".
 * @property rename The Rename dialog, or null when it is closed.
 * @property deleteConfirmOpen True while "Delete this document?" is showing.
 * @property deleting True while the document is being deleted.
 */
data class ViewerUiState(
    val stage: ViewerStage = ViewerStage.OPENING,
    val title: String = "",
    val isPdf: Boolean = false,
    val picture: Bitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val pageLoading: Boolean = false,
    @StringRes val errorMessage: Int? = null,
    val canRetry: Boolean = false,
    val rename: RenameDialogState? = null,
    val deleteConfirmOpen: Boolean = false,
    val deleting: Boolean = false,
) {
    /** True when there is a page before the current one. */
    val hasPreviousPage: Boolean
        get() = isPdf && pageIndex > 0

    /** True when there is a page after the current one. */
    val hasNextPage: Boolean
        get() = isPdf && pageIndex < pageCount - 1
}

/**
 * Opens one document, shows it, and always tidies up afterwards (rule 6).
 *
 * Tidy-up ("release") happens when the viewer closes, on Back, and at once on lock:
 * the PDF is closed and deleted from view_tmp (and view_tmp is emptied as a safety net), and the
 * picture is hidden first and only then freed, because freeing a picture that is still being
 * drawn would crash the app.
 *
 * One ViewModel is reused for every document, so nothing builds up over time.
 *
 * @param repository Opens and renames documents.
 * @param unlocker Shows "Confirm it's you" when the unlock window has expired.
 * @param lockState The app's lock; the viewer releases everything as soon as it locks.
 * @param navigator Goes back to the vault list.
 * @param tempFileCleaner Empties view_tmp.
 */
class ViewerViewModel(
    private val repository: VaultRepository,
    private val unlocker: BiometricUnlocker,
    lockState: VaultLockState,
    private val navigator: VaultNavigator,
    private val tempFileCleaner: TempFileCleaner,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(ViewerUiState())

    /** The current viewer state. */
    val uiState: StateFlow<ViewerUiState> = mutableUiState.asStateFlow()

    // Everything below is only touched on the main thread.
    private var currentDocumentId: String? = null
    private var released = true
    private var openedPdf: OpenedDocument.Pdf? = null
    private var pdfRenderer: PdfPageRenderer? = null
    private var screenWidthPixels = 0
    private var screenLongestPixels = 0
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            lockState.status.filter { status -> status == LockStatus.LOCKED }.collect {
                release()
                mutableUiState.value = ViewerUiState()
            }
        }
    }

    /**
     * Shows a document. Does nothing if it is already showing (for example after recomposition).
     *
     * @param activity Shows "Confirm it's you" if needed.
     * @param documentId The document to show.
     * @param screenWidth The screen width in pixels (for sharp PDF pages).
     * @param screenLongest The screen's longest side in pixels (for sharp photos).
     */
    fun show(activity: FragmentActivity, documentId: String, screenWidth: Int, screenLongest: Int) {
        if (documentId == currentDocumentId && !released) return
        val prompt = BiometricReAuthPrompt(activity, unlocker)
        showWith(prompt, documentId, screenWidth, screenLongest)
    }

    /** Test hook: [show] with any prompt (tests use a fake one). */
    internal fun showWith(prompt: ReAuthPrompt, documentId: String, screenWidth: Int, screenLongest: Int) {
        release()
        currentDocumentId = documentId
        released = false
        screenWidthPixels = screenWidth
        screenLongestPixels = screenLongest
        mutableUiState.value = ViewerUiState(stage = ViewerStage.OPENING)
        loadJob = viewModelScope.launch { loadDocument(prompt, documentId) }
    }

    /**
     * Tries again after a cancelled "Confirm it's you".
     *
     * @param activity Shows the prompt.
     */
    fun retry(activity: FragmentActivity) {
        val documentId = currentDocumentId ?: return
        released = true
        show(activity, documentId, screenWidthPixels, screenLongestPixels)
    }

    /** Shows the next PDF page. */
    fun nextPage() {
        if (mutableUiState.value.hasNextPage) showPage(mutableUiState.value.pageIndex + 1)
    }

    /** Shows the previous PDF page. */
    fun previousPage() {
        if (mutableUiState.value.hasPreviousPage) showPage(mutableUiState.value.pageIndex - 1)
    }

    /**
     * Handles the system Back gesture/button.
     * With a dialog open (Rename or Delete), Back closes only the dialog; otherwise the viewer
     * tidies up and goes back to the vault list. While deleting, Back waits for the delete.
     */
    fun onBack() {
        val current = mutableUiState.value
        if (current.rename != null) {
            closeRename()
            return
        }
        if (current.deleteConfirmOpen) {
            closeDelete()
            return
        }
        if (current.deleting) return
        release()
        navigator.handleBack()
    }

    /** Opens "Delete this document?". */
    fun openDelete() {
        val current = mutableUiState.value
        if (current.stage != ViewerStage.READY) return
        mutableUiState.value = current.copy(deleteConfirmOpen = true)
    }

    /** Closes "Delete this document?" without deleting. */
    fun closeDelete() {
        mutableUiState.value = mutableUiState.value.copy(deleteConfirmOpen = false)
    }

    /**
     * Deletes the open document after the user confirmed.
     *
     * The viewer tidies up FIRST (PDF closed and deleted from view_tmp, picture freed), so nothing
     * decrypted is left over; then the repository deletes the row (with its wrapped key) and the
     * .bin, in the Task 1.6 order; then the app goes back to the list.
     */
    fun confirmDelete() {
        val documentId = currentDocumentId ?: return
        val current = mutableUiState.value
        if (!current.deleteConfirmOpen) return
        mutableUiState.value = current.copy(deleteConfirmOpen = false, deleting = true)
        release()
        viewModelScope.launch { deleteAndLeave(documentId) }
    }

    /** Opens the Rename dialog with the current name filled in. */
    fun openRename() {
        val current = mutableUiState.value
        if (current.stage != ViewerStage.READY) return
        mutableUiState.value = current.copy(rename = RenameDialogState(text = current.title))
    }

    /**
     * Called on every change in the Rename field.
     *
     * @param text What the field now contains.
     */
    fun onRenameTextChanged(text: String) {
        val dialog = mutableUiState.value.rename ?: return
        mutableUiState.value = mutableUiState.value.copy(rename = dialog.copy(text = text, problem = problemIn(text)))
    }

    /** Closes the Rename dialog without saving. */
    fun closeRename() {
        mutableUiState.value = mutableUiState.value.copy(rename = null)
    }

    /** Saves the new name from the Rename dialog. */
    fun confirmRename() {
        val documentId = currentDocumentId ?: return
        val dialog = mutableUiState.value.rename ?: return
        if (!dialog.canSave) return
        mutableUiState.value = mutableUiState.value.copy(rename = dialog.copy(saving = true))
        viewModelScope.launch { saveRename(documentId, dialog.text) }
    }

    override fun onCleared() {
        // The screen is gone, so the picture can be freed at once.
        val picture = mutableUiState.value.picture
        release()
        picture?.recycle()
    }

    /** Test hook: true once everything from the last document has been released. */
    internal fun isReleasedForTest(): Boolean = released && pdfRenderer == null && openedPdf == null

    private suspend fun loadDocument(prompt: ReAuthPrompt, documentId: String) {
        val keyAccess = MasterKeyAccess(prompt)
        val result = keyAccess.runSuspending { repository.openDocument(documentId) }
        if (released || documentId != currentDocumentId) {
            // Closed or locked while opening: nothing may be kept.
            discard(result)
            return
        }
        when (result) {
            is KeyAccessResult.Success -> present(result.value)
            is KeyAccessResult.NeedsConfirmation -> fail(R.string.viewer_error_confirm, canRetry = true)
            is KeyAccessResult.Failed -> failFor(result.error)
        }
    }

    private suspend fun present(document: OpenedDocument) {
        mutableUiState.value = mutableUiState.value.copy(title = document.summary.displayName)
        when (document) {
            is OpenedDocument.Image -> presentImage(document)
            is OpenedDocument.Pdf -> presentPdf(document)
        }
    }

    private suspend fun presentImage(document: OpenedDocument.Image) {
        val picture = try {
            withContext(Dispatchers.Default) { decodeImage(document.bytes.bytes) }
        } catch (cannotDecode: IOException) {
            null
        } finally {
            // The picture is decoded (or failed); the decrypted file bytes are not needed any more.
            document.bytes.wipe()
        }
        if (picture == null) {
            fail(R.string.viewer_error_unreadable, canRetry = false)
            return
        }
        if (released) {
            picture.recycle()
            return
        }
        mutableUiState.value = mutableUiState.value.copy(stage = ViewerStage.READY, picture = picture, isPdf = false)
    }

    private suspend fun presentPdf(document: OpenedDocument.Pdf) {
        openedPdf = document
        val renderer = try {
            withContext(Dispatchers.IO) { PdfPageRenderer(document.file.file) }
        } catch (cannotOpen: IOException) {
            fail(R.string.viewer_error_unreadable, canRetry = false)
            return
        }
        if (released) {
            renderer.close()
            return
        }
        pdfRenderer = renderer
        mutableUiState.value = mutableUiState.value.copy(
            stage = ViewerStage.READY,
            isPdf = true,
            pageCount = renderer.pageCount,
            pageIndex = 0,
        )
        showPage(0)
    }

    private fun showPage(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        mutableUiState.value = mutableUiState.value.copy(pageLoading = true)
        viewModelScope.launch {
            val page = renderer.renderPage(pageIndex, screenWidthPixels) ?: return@launch
            if (released) {
                page.recycle()
                return@launch
            }
            val previousPicture = mutableUiState.value.picture
            mutableUiState.value = mutableUiState.value.copy(picture = page, pageIndex = pageIndex, pageLoading = false)
            recycleOnceHidden(previousPicture)
        }
    }

    private suspend fun deleteAndLeave(documentId: String) {
        try {
            repository.deleteDocument(documentId)
        } catch (locked: VaultLockedException) {
            // The lock listener already cleared the screen; the delete can be done after unlock.
            return
        } catch (failed: Exception) {
            mutableUiState.value = mutableUiState.value.copy(deleting = false)
            fail(R.string.viewer_error_delete, canRetry = false)
            return
        }
        mutableUiState.value = ViewerUiState()
        navigator.backToList()
    }

    private suspend fun saveRename(documentId: String, newName: String) {
        val outcome = try {
            repository.renameDocument(documentId, newName)
            null
        } catch (rejected: InvalidDisplayNameException) {
            rejected.problem
        } catch (locked: VaultLockedException) {
            // The lock listener clears the screen; nothing else to do.
            return
        }
        val dialog = mutableUiState.value.rename ?: return
        if (outcome != null) {
            mutableUiState.value = mutableUiState.value.copy(rename = dialog.copy(problem = outcome, saving = false))
            return
        }
        val cleanName = (DisplayNameValidator.validate(newName) as DisplayNameResult.Valid).cleanName
        mutableUiState.value = mutableUiState.value.copy(title = cleanName, rename = null)
    }

    /** Frees everything from the current document. Safe to call more than once. */
    private fun release() {
        loadJob?.cancel()
        loadJob = null
        released = true
        // Hide the picture first; free it only once the screen has stopped drawing it.
        val picture = mutableUiState.value.picture
        mutableUiState.value = mutableUiState.value.copy(picture = null)
        pdfRenderer?.close()
        pdfRenderer = null
        openedPdf?.file?.delete()
        openedPdf = null
        // Safety net (rule 6): nothing decrypted may stay in view_tmp after viewing.
        tempFileCleaner.clearViewTemp()
        recycleOnceHidden(picture)
    }

    /**
     * Frees a picture after two frames have been drawn without it. If no frame comes (the app is
     * in the background, so nothing is drawn), it is freed after a short wait anyway.
     */
    private fun recycleOnceHidden(picture: Bitmap?) {
        if (picture == null) return
        viewModelScope.launch {
            withTimeoutOrNull(FRAME_WAIT_MILLIS) {
                awaitFrame()
                awaitFrame()
            }
            picture.recycle()
        }
    }

    private fun discard(result: KeyAccessResult<OpenedDocument>) {
        val document = (result as? KeyAccessResult.Success)?.value ?: return
        when (document) {
            is OpenedDocument.Image -> document.bytes.wipe()
            is OpenedDocument.Pdf -> document.file.delete()
        }
        tempFileCleaner.clearViewTemp()
    }

    private fun failFor(error: Throwable) {
        when (error) {
            is DocumentNotFoundException -> {
                release()
                navigator.backToList()
            }
            is DocumentUnreadableException -> fail(R.string.viewer_error_unreadable, canRetry = false)
            is VaultLockedException -> Unit
            else -> fail(R.string.viewer_error_other, canRetry = false)
        }
    }

    private fun fail(@StringRes message: Int, canRetry: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(stage = ViewerStage.FAILED, errorMessage = message, canRetry = canRetry)
    }

    private fun decodeImage(imageBytes: ByteArray): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val sampleSize = imageSampleSize(info.size.width, info.size.height, screenLongestPixels)
            decoder.setTargetSampleSize(sampleSize)
            // A partly decoded picture means a damaged file; never show it.
            decoder.setOnPartialImageListener { false }
        }
    }

    private fun problemIn(name: String): DisplayNameProblem? {
        val result = DisplayNameValidator.validate(name)
        return (result as? DisplayNameResult.Invalid)?.problem
    }

    private companion object {
        /** Longest wait for frames before freeing a hidden picture (app in the background). */
        const val FRAME_WAIT_MILLIS = 500L
    }
}

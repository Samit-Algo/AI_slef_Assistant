package com.heybuddy.ui.viewer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heybuddy.R
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DisplayNameResult
import com.heybuddy.data.DisplayNameValidator
import com.heybuddy.data.DocumentDetails
import com.heybuddy.data.DocumentNotFoundException
import com.heybuddy.data.DocumentPage
import com.heybuddy.data.InvalidDisplayNameException
import com.heybuddy.data.OpenedPage
import com.heybuddy.data.PreparedPage
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
import com.heybuddy.ui.adddocument.AddDocumentFailure
import com.heybuddy.ui.adddocument.AddDocumentSaver
import com.heybuddy.ui.adddocument.PagePreparer
import com.heybuddy.ui.adddocument.PrepareResult
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT
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

/** Where the viewer is with the page being shown. */
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
 * Two kinds of "page": the document's pages (Task 1.10p, swiped), and the pages inside a PDF page
 * file (‹ › at the bottom).
 *
 * @property stage Opening, ready or failed, for the page being shown.
 * @property title The document's name.
 * @property documentPageCount How many pages the document has (0 until loaded).
 * @property documentPageIndex The document page being shown, starting at 0.
 * @property isPdf True when the page being shown is a PDF (shows PDF page controls).
 * @property picture The photo or the current PDF page, or null.
 * @property pageIndex The current page inside the PDF, starting at 0.
 * @property pageCount Number of pages inside the PDF (0 for photos).
 * @property pageLoading True while the next PDF page is being drawn.
 * @property errorMessage Why the page failed, or null.
 * @property canRetry True when the failure was a cancelled "Confirm it's you".
 * @property notice A message about the last add/remove, or null.
 * @property successNotice A short "it worked" message with a self-drawing check, or null.
 * @property rename The Rename dialog, or null when it is closed.
 * @property deleteConfirmOpen True while "Delete this document?" is showing.
 * @property deleting True while the document is being deleted.
 * @property addPageChoiceOpen True while "Add a page" (photos or file) is showing.
 * @property addingPages True while picked pages are being added.
 * @property removePageConfirmOpen True while "Remove this page?" is showing.
 * @property removingPage True while a page is being removed.
 */
data class ViewerUiState(
    val stage: ViewerStage = ViewerStage.OPENING,
    val title: String = "",
    val documentPageCount: Int = 0,
    val documentPageIndex: Int = 0,
    val isPdf: Boolean = false,
    val picture: Bitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val pageLoading: Boolean = false,
    @StringRes val errorMessage: Int? = null,
    val canRetry: Boolean = false,
    @StringRes val notice: Int? = null,
    @StringRes val successNotice: Int? = null,
    val rename: RenameDialogState? = null,
    val deleteConfirmOpen: Boolean = false,
    val deleting: Boolean = false,
    val addPageChoiceOpen: Boolean = false,
    val addingPages: Boolean = false,
    val removePageConfirmOpen: Boolean = false,
    val removingPage: Boolean = false,
) {
    /** True when there is a PDF page before the current one. */
    val hasPreviousPage: Boolean
        get() = isPdf && pageIndex > 0

    /** True when there is a PDF page after the current one. */
    val hasNextPage: Boolean
        get() = isPdf && pageIndex < pageCount - 1

    /** True once the document and its page list are known. */
    val documentLoaded: Boolean
        get() = documentPageCount > 0

    /** True while the document itself is changing (no menu, no swiping). */
    val busy: Boolean
        get() = deleting || addingPages || removingPage
}

/**
 * Opens one document, shows ONE page at a time, and always tidies up afterwards (rule 6).
 *
 * Only the page on screen is ever decrypted. Moving to another page first releases the current
 * one: the PDF is closed and deleted from view_tmp (and view_tmp is emptied as a safety net), and
 * the picture is hidden first and only then freed, because freeing a picture that is still being
 * drawn would crash the app. The same happens when the viewer closes, on Back, and at once on lock.
 *
 * One ViewModel is reused for every document, so nothing builds up over time.
 *
 * @param repository Opens, renames and changes documents.
 * @param unlocker Shows "Confirm it's you" when the unlock window has expired.
 * @param lockState The app's lock; the viewer releases everything as soon as it locks.
 * @param navigator Goes back to the vault list.
 * @param tempFileCleaner Empties view_tmp.
 * @param preparer Turns picked files into pending pages ("Add page").
 * @param saver Adds pending pages to the document.
 */
class ViewerViewModel(
    private val repository: VaultRepository,
    private val unlocker: BiometricUnlocker,
    private val lockState: VaultLockState,
    private val navigator: VaultNavigator,
    private val tempFileCleaner: TempFileCleaner,
    private val preparer: PagePreparer,
    private val saver: AddDocumentSaver,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(ViewerUiState())

    /** The current viewer state. */
    val uiState: StateFlow<ViewerUiState> = mutableUiState.asStateFlow()

    // Everything below is only touched on the main thread.
    private var currentDocumentId: String? = null
    private var pages: List<DocumentPage> = emptyList()
    private var released = true
    private var openedPdf: OpenedPage.Pdf? = null
    private var pdfRenderer: PdfPageRenderer? = null
    private var screenWidthPixels = 0
    private var screenLongestPixels = 0
    private var loadJob: Job? = null

    // Goes up every time the shown page is released. Work started for an older page compares it
    // and throws its result away, so a slow page can never appear on top of a newer one.
    private var pageToken = 0

    init {
        viewModelScope.launch {
            lockState.status.filter { status -> status == LockStatus.LOCKED }.collect {
                release()
                mutableUiState.value = ViewerUiState()
            }
        }
    }

    /**
     * Shows a document from its first page. Does nothing if it is already showing (for example
     * after recomposition).
     *
     * @param activity Shows "Confirm it's you" if needed.
     * @param documentId The document to show.
     * @param screenWidth The screen width in pixels (for sharp PDF pages).
     * @param screenLongest The screen's longest side in pixels (for sharp photos).
     */
    fun show(activity: FragmentActivity, documentId: String, screenWidth: Int, screenLongest: Int) {
        if (documentId == currentDocumentId && !released) return
        showWith(promptFor(activity), documentId, screenWidth, screenLongest)
    }

    /** Test hook: [show] with any prompt (tests use a fake one). */
    internal fun showWith(prompt: ReAuthPrompt, documentId: String, screenWidth: Int, screenLongest: Int) {
        release()
        currentDocumentId = documentId
        pages = emptyList()
        released = false
        screenWidthPixels = screenWidth
        screenLongestPixels = screenLongest
        mutableUiState.value = ViewerUiState(stage = ViewerStage.OPENING)
        loadJob = viewModelScope.launch { loadDocument(prompt, documentId, startAt = 0) }
    }

    /**
     * Shows another page of the document (after a swipe or a ‹ › tap).
     *
     * @param activity Shows "Confirm it's you" if needed.
     * @param index The page, starting at 0.
     */
    fun showDocumentPage(activity: FragmentActivity, index: Int) {
        showDocumentPageWith(promptFor(activity), index)
    }

    /** Test hook: [showDocumentPage] with any prompt. */
    internal fun showDocumentPageWith(prompt: ReAuthPrompt, index: Int) {
        val current = mutableUiState.value
        if (released || current.busy || index !in pages.indices) return
        if (index == current.documentPageIndex) return
        openPage(prompt, index)
    }

    /**
     * Tries again after a cancelled "Confirm it's you".
     *
     * @param activity Shows the prompt.
     */
    fun retry(activity: FragmentActivity) {
        retryWith(promptFor(activity))
    }

    /** Test hook: [retry] with any prompt. */
    internal fun retryWith(prompt: ReAuthPrompt) {
        val documentId = currentDocumentId ?: return
        if (pages.isEmpty()) {
            showWith(prompt, documentId, screenWidthPixels, screenLongestPixels)
            return
        }
        openPage(prompt, mutableUiState.value.documentPageIndex)
    }

    /** Shows the next page inside the PDF. */
    fun nextPage() {
        if (mutableUiState.value.hasNextPage) showPdfPage(mutableUiState.value.pageIndex + 1)
    }

    /** Shows the previous page inside the PDF. */
    fun previousPage() {
        if (mutableUiState.value.hasPreviousPage) showPdfPage(mutableUiState.value.pageIndex - 1)
    }

    /**
     * Handles the system Back gesture/button.
     * With a dialog open, Back closes only the dialog; otherwise the viewer tidies up and goes back
     * to the vault list. While the document is changing, Back waits.
     */
    fun onBack() {
        val current = mutableUiState.value
        when {
            current.rename != null -> closeRename()
            current.deleteConfirmOpen -> closeDelete()
            current.addPageChoiceOpen -> closeAddPage()
            current.removePageConfirmOpen -> closeRemovePage()
            current.busy -> Unit
            else -> {
                release()
                navigator.handleBack()
            }
        }
    }

    /** Opens "Delete this document?". */
    fun openDelete() {
        val current = mutableUiState.value
        if (!current.documentLoaded || current.busy) return
        mutableUiState.value = current.copy(deleteConfirmOpen = true, notice = null)
    }

    /** Closes "Delete this document?" without deleting. */
    fun closeDelete() {
        mutableUiState.value = mutableUiState.value.copy(deleteConfirmOpen = false)
    }

    /**
     * Deletes the open document (all pages) after the user confirmed.
     *
     * The viewer tidies up FIRST (PDF closed and deleted from view_tmp, picture freed), so nothing
     * decrypted is left over; then the repository deletes the rows (with all wrapped keys) and the
     * files, in that order; then the app goes back to the list.
     */
    fun confirmDelete() {
        val documentId = currentDocumentId ?: return
        val current = mutableUiState.value
        if (!current.deleteConfirmOpen) return
        mutableUiState.value = current.copy(deleteConfirmOpen = false, deleting = true)
        release()
        viewModelScope.launch { deleteAndLeave(documentId) }
    }

    /** Opens "Add a page" (choose photos or a file). Explains the limit instead if it is reached. */
    fun openAddPage() {
        val current = mutableUiState.value
        if (!current.documentLoaded || current.busy) return
        if (current.documentPageCount >= MAX_PAGES_PER_DOCUMENT) {
            mutableUiState.value = current.copy(notice = AddDocumentFailure.TOO_MANY_PAGES.message)
            return
        }
        mutableUiState.value = current.copy(addPageChoiceOpen = true, notice = null)
    }

    /** Closes "Add a page" (also called just before a picker opens). */
    fun closeAddPage() {
        mutableUiState.value = mutableUiState.value.copy(addPageChoiceOpen = false)
    }

    /**
     * Adds the picked files as new pages at the end of the document, then shows the first new page.
     *
     * @param activity Shows "Confirm it's you" if needed.
     * @param uris The picked files (empty if the user backed out).
     */
    fun onPagesPicked(activity: FragmentActivity, uris: List<Uri>) {
        onPagesPickedWith(promptFor(activity), uris)
    }

    /** Test hook: [onPagesPicked] with any prompt. */
    internal fun onPagesPickedWith(prompt: ReAuthPrompt, uris: List<Uri>) {
        val documentId = currentDocumentId ?: return
        val current = mutableUiState.value
        // A picker can return after the vault locked; the files must then not be read at all.
        if (uris.isEmpty() || released || current.busy || lockState.status.value == LockStatus.LOCKED) return
        if (current.documentPageCount + uris.size > MAX_PAGES_PER_DOCUMENT) {
            mutableUiState.value = current.copy(notice = AddDocumentFailure.TOO_MANY_PAGES.message)
            return
        }
        mutableUiState.value = current.copy(addingPages = true, notice = null)
        viewModelScope.launch { addPages(prompt, documentId, uris) }
    }

    /** Opens "Remove this page?". Only offered when the document has more than one page. */
    fun openRemovePage() {
        val current = mutableUiState.value
        if (current.documentPageCount < 2 || current.busy) return
        mutableUiState.value = current.copy(removePageConfirmOpen = true, notice = null)
    }

    /** Closes "Remove this page?" without removing. */
    fun closeRemovePage() {
        mutableUiState.value = mutableUiState.value.copy(removePageConfirmOpen = false)
    }

    /**
     * Removes the page on screen after the user confirmed, then shows the page now in its place.
     *
     * @param activity Shows "Confirm it's you" if needed for the next page.
     */
    fun confirmRemovePage(activity: FragmentActivity) {
        confirmRemovePageWith(promptFor(activity))
    }

    /** Test hook: [confirmRemovePage] with any prompt. */
    internal fun confirmRemovePageWith(prompt: ReAuthPrompt) {
        val documentId = currentDocumentId ?: return
        val current = mutableUiState.value
        if (!current.removePageConfirmOpen) return
        val page = pages.getOrNull(current.documentPageIndex) ?: return
        mutableUiState.value = current.copy(removePageConfirmOpen = false, removingPage = true)
        // Nothing decrypted from this page may outlive it.
        releasePage()
        viewModelScope.launch { removePageAndShowNext(prompt, documentId, page.pageId, current.documentPageIndex) }
    }

    /** Called once the success message has been shown long enough. */
    fun clearSuccessNotice() {
        mutableUiState.value = mutableUiState.value.copy(successNotice = null)
    }

    /** Opens the Rename dialog with the current name filled in. */
    fun openRename() {
        val current = mutableUiState.value
        if (!current.documentLoaded || current.busy) return
        mutableUiState.value = current.copy(rename = RenameDialogState(text = current.title), notice = null)
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

    private fun promptFor(activity: FragmentActivity): ReAuthPrompt = BiometricReAuthPrompt(activity, unlocker)

    private suspend fun loadDocument(prompt: ReAuthPrompt, documentId: String, startAt: Int) {
        val details = try {
            repository.documentDetails(documentId)
        } catch (gone: DocumentNotFoundException) {
            leaveToList()
            return
        } catch (locked: VaultLockedException) {
            return
        } catch (failed: Exception) {
            fail(R.string.viewer_error_other, canRetry = false)
            return
        }
        if (released || documentId != currentDocumentId) return
        showDetails(details)
        val startIndex = startAt.coerceIn(0, pages.lastIndex)
        openPage(prompt, startIndex)
    }

    private fun showDetails(details: DocumentDetails) {
        pages = details.pages
        mutableUiState.value = mutableUiState.value.copy(
            title = details.summary.displayName,
            documentPageCount = details.pages.size,
        )
    }

    /** Releases the shown page and decrypts page [index] instead. */
    private fun openPage(prompt: ReAuthPrompt, index: Int) {
        val documentId = currentDocumentId ?: return
        val page = pages.getOrNull(index) ?: return
        releasePage()
        val token = pageToken
        mutableUiState.value = mutableUiState.value.copy(
            stage = ViewerStage.OPENING,
            documentPageIndex = index,
            isPdf = false,
            pageIndex = 0,
            pageCount = 0,
            pageLoading = false,
            errorMessage = null,
            canRetry = false,
        )
        loadJob = viewModelScope.launch { loadPage(prompt, documentId, page, token) }
    }

    private suspend fun loadPage(prompt: ReAuthPrompt, documentId: String, page: DocumentPage, token: Int) {
        val keyAccess = MasterKeyAccess(prompt)
        val result = keyAccess.runSuspending { repository.openPage(documentId, page.pageId) }
        if (token != pageToken) {
            // Another page was chosen, or the viewer closed or locked, while this one opened.
            discard(result)
            return
        }
        when (result) {
            is KeyAccessResult.Success -> present(result.value, token)
            is KeyAccessResult.NeedsConfirmation -> fail(R.string.viewer_error_confirm, canRetry = true)
            is KeyAccessResult.Failed -> failFor(result.error)
        }
    }

    private suspend fun present(opened: OpenedPage, token: Int) {
        when (opened) {
            is OpenedPage.Image -> presentImage(opened, token)
            is OpenedPage.Pdf -> presentPdf(opened, token)
        }
    }

    private suspend fun presentImage(opened: OpenedPage.Image, token: Int) {
        val picture = try {
            withContext(Dispatchers.Default) { decodeImage(opened.bytes.bytes) }
        } catch (cannotDecode: IOException) {
            null
        } finally {
            // The picture is decoded (or failed); the decrypted file bytes are not needed any more.
            opened.bytes.wipe()
        }
        if (token != pageToken) {
            picture?.recycle()
            return
        }
        if (picture == null) {
            fail(R.string.viewer_error_unreadable, canRetry = false)
            return
        }
        mutableUiState.value = mutableUiState.value.copy(stage = ViewerStage.READY, picture = picture, isPdf = false)
    }

    private suspend fun presentPdf(opened: OpenedPage.Pdf, token: Int) {
        openedPdf = opened
        val renderer = try {
            withContext(Dispatchers.IO) { PdfPageRenderer(opened.file.file) }
        } catch (cannotOpen: IOException) {
            if (token == pageToken) fail(R.string.viewer_error_unreadable, canRetry = false)
            return
        }
        if (token != pageToken) {
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
        showPdfPage(0)
    }

    private fun showPdfPage(pdfPageIndex: Int) {
        val renderer = pdfRenderer ?: return
        val token = pageToken
        mutableUiState.value = mutableUiState.value.copy(pageLoading = true)
        viewModelScope.launch {
            val rendered = renderer.renderPage(pdfPageIndex, screenWidthPixels) ?: return@launch
            if (token != pageToken) {
                rendered.recycle()
                return@launch
            }
            val previousPicture = mutableUiState.value.picture
            mutableUiState.value = mutableUiState.value.copy(picture = rendered, pageIndex = pdfPageIndex, pageLoading = false)
            recycleOnceHidden(previousPicture)
        }
    }

    private suspend fun addPages(prompt: ReAuthPrompt, documentId: String, uris: List<Uri>) {
        val prepared = mutableListOf<PreparedPage>()
        try {
            val prepareFailure = prepareEach(documentId, uris, prepared)
            if (!isStillShowing(documentId)) return
            val addFailure = if (prepared.isEmpty()) prepareFailure else saver.addPages(documentId, prepared, MasterKeyAccess(prompt))
            if (!isStillShowing(documentId)) return
            finishAddingPages(prompt, documentId, addFailure, addedCount = if (addFailure == null) prepared.size else 0)
        } finally {
            // Pages that were added are in the vault already (nothing pending is left for them, so
            // this changes nothing); any others must not stay behind as pending files.
            preparer.discard(prepared)
        }
    }

    /** Prepares every picked file into [prepared]. Returns the last failure, or null. */
    private suspend fun prepareEach(documentId: String, uris: List<Uri>, prepared: MutableList<PreparedPage>): AddDocumentFailure? {
        var lastFailure: AddDocumentFailure? = null
        for (uri in uris) {
            if (!isStillShowing(documentId)) return null
            when (val result = preparer.prepare(documentId, uri)) {
                is PrepareResult.Prepared -> {
                    prepared.add(result.page)
                    // The viewer shows the real page after adding; this small picture was never drawn.
                    result.thumbnail?.recycle()
                }
                is PrepareResult.Failed -> lastFailure = result.failure
            }
        }
        return lastFailure
    }

    private suspend fun finishAddingPages(prompt: ReAuthPrompt, documentId: String, failure: AddDocumentFailure?, addedCount: Int) {
        val notice = when (failure) {
            null -> null
            AddDocumentFailure.CONFIRMATION_NEEDED -> R.string.viewer_error_add_confirm
            AddDocumentFailure.OTHER -> R.string.viewer_error_add_other
            else -> failure.message
        }
        mutableUiState.value = mutableUiState.value.copy(addingPages = false, notice = notice)
        if (addedCount == 0) return
        mutableUiState.value = mutableUiState.value.copy(successNotice = R.string.viewer_page_added)
        // Show the first new page: it now sits right after the pages that were there before.
        val firstNewPage = pages.size
        reloadAndShow(prompt, documentId, firstNewPage)
    }

    private suspend fun removePageAndShowNext(prompt: ReAuthPrompt, documentId: String, pageId: String, removedIndex: Int) {
        try {
            repository.removePage(documentId, pageId)
        } catch (locked: VaultLockedException) {
            // The lock listener already cleared the screen.
            return
        } catch (failed: Exception) {
            mutableUiState.value = mutableUiState.value.copy(removingPage = false, notice = R.string.viewer_error_remove_page)
            if (isStillShowing(documentId)) openPage(prompt, removedIndex)
            return
        }
        mutableUiState.value = mutableUiState.value.copy(removingPage = false)
        if (!isStillShowing(documentId)) return
        // The page after the removed one has moved into its place; after the last page, step back one.
        reloadAndShow(prompt, documentId, removedIndex)
    }

    private suspend fun reloadAndShow(prompt: ReAuthPrompt, documentId: String, index: Int) {
        releasePage()
        loadDocument(prompt, documentId, startAt = index)
    }

    private fun isStillShowing(documentId: String): Boolean = !released && documentId == currentDocumentId

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
        mutableUiState.value = mutableUiState.value.copy(title = cleanName, rename = null, successNotice = R.string.viewer_renamed)
    }

    /** Frees everything from the current document. Safe to call more than once. */
    private fun release() {
        released = true
        releasePage()
    }

    /** Frees everything from the page on screen. Safe to call more than once. */
    private fun releasePage() {
        pageToken++
        loadJob?.cancel()
        loadJob = null
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

    private fun leaveToList() {
        release()
        navigator.backToList()
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

    private fun discard(result: KeyAccessResult<OpenedPage>) {
        val opened = (result as? KeyAccessResult.Success)?.value ?: return
        when (opened) {
            is OpenedPage.Image -> opened.bytes.wipe()
            is OpenedPage.Pdf -> opened.file.delete()
        }
        tempFileCleaner.clearViewTemp()
    }

    private fun failFor(error: Throwable) {
        when (error) {
            is DocumentNotFoundException -> leaveToList()
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

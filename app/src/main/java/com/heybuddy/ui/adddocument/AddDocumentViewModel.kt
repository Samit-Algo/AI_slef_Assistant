package com.heybuddy.ui.adddocument

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DisplayNameResult
import com.heybuddy.data.DisplayNameValidator
import com.heybuddy.data.DocumentType
import com.heybuddy.data.PreparedPage
import com.heybuddy.data.movedDown
import com.heybuddy.data.movedUp
import com.heybuddy.data.pageLimitProblem
import com.heybuddy.security.BiometricReAuthPrompt
import com.heybuddy.security.BiometricUnlocker
import com.heybuddy.security.LockStatus
import com.heybuddy.security.MasterKeyAccess
import com.heybuddy.security.VaultLockState
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Which step of adding a document the screen is on. */
enum class AddStage {
    CHOOSING,
    READING,
    READY,
    SAVING,
    SAVED,
}

/**
 * One page on the Add screen.
 *
 * @property pageId The pending page's id.
 * @property thumbnail A small picture of it, or null (a PDF that cannot be previewed).
 * @property isPdf True for a PDF.
 */
data class DraftPage(val pageId: String, val thumbnail: Bitmap?, val isPdf: Boolean)

/**
 * What the Add document screen shows.
 *
 * @property stage The current step.
 * @property pages The pages so far, in the order they will be saved.
 * @property documentType The type the user chose, or null before choosing.
 * @property name The name in the text field.
 * @property nameProblem Why the current name is not allowed, or null if it is fine.
 * @property errorMessage The last failure message, or null.
 */
data class AddDocumentUiState(
    val stage: AddStage = AddStage.CHOOSING,
    val pages: List<DraftPage> = emptyList(),
    val documentType: DocumentType? = null,
    val name: String = "",
    val nameProblem: DisplayNameProblem? = null,
    @StringRes val errorMessage: Int? = null,
) {
    /** Save is allowed once there is a page, a type is chosen and the name passes every rule. */
    val canSave: Boolean
        get() = stage == AddStage.READY && pages.isNotEmpty() && documentType != null && nameProblem == null

    /** More pages may be added until the document limit (owner decision, Task 1.10p). */
    val canAddPage: Boolean
        get() = pages.size < MAX_PAGES_PER_DOCUMENT
}

/**
 * Runs the Add document screen: pick one or more pages, order them, choose type and name, save.
 *
 * Each picked file is read once, checked, thumbnailed and encrypted straight away into a pending
 * page (see [PagePreparer]); its plaintext is wiped at once. Only small thumbnails stay in memory.
 * The pending pages are deleted on Cancel, on Back, when removed, and as soon as the vault locks.
 *
 * @param appContext The application context (for default names).
 * @param preparer Turns picked files into pending pages.
 * @param saver Saves the pages as one document.
 * @param unlocker Shows "Confirm it's you" when the unlock window has expired.
 * @param lockState The app's lock; pending pages are deleted as soon as it locks.
 */
class AddDocumentViewModel(
    private val appContext: Context,
    private val preparer: PagePreparer,
    private val saver: AddDocumentSaver,
    private val unlocker: BiometricUnlocker,
    private val lockState: VaultLockState,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(AddDocumentUiState())

    /** The current screen state. */
    val uiState: StateFlow<AddDocumentUiState> = mutableUiState.asStateFlow()

    // Everything below is only touched on the main thread.
    // The id every page of this draft is bound to; made when the first page is picked.
    private var draftDocumentId: String? = null

    // The pending pages (with their keys), by page id. The ORDER is the one in uiState.pages.
    private val preparedPages = mutableMapOf<String, PreparedPage>()

    // True once the user typed their own name, so changing the type no longer replaces it.
    private var nameEditedByUser = false

    init {
        viewModelScope.launch {
            lockState.status.filter { status -> status == LockStatus.LOCKED }.collect { reset() }
        }
    }

    /**
     * Called with the files the user picked (empty if they backed out of the picker).
     *
     * @param uris The picked files. Each is read once now; no long-term access is kept.
     */
    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // A picker can return after the vault locked (for example the screen turned off while it
        // was open). The files must then not be read at all.
        if (lockState.status.value == LockStatus.LOCKED) return
        val current = mutableUiState.value
        if (current.stage != AddStage.CHOOSING && current.stage != AddStage.READY) return
        val documentId = draftDocumentId ?: preparer.newDocumentId()
        draftDocumentId = documentId
        mutableUiState.value = current.copy(stage = AddStage.READING, errorMessage = null)
        viewModelScope.launch { prepareAll(documentId, uris) }
    }

    /**
     * Removes a page before saving (its pending file is deleted).
     *
     * @param pageId The page to remove.
     */
    fun onRemovePage(pageId: String) {
        val current = mutableUiState.value
        if (current.stage != AddStage.READY) return
        val page = preparedPages.remove(pageId) ?: return
        preparer.discard(listOf(page))
        val remaining = current.pages.filter { draft -> draft.pageId != pageId }
        val stage = if (remaining.isEmpty()) AddStage.CHOOSING else AddStage.READY
        mutableUiState.value = current.copy(stage = stage, pages = remaining, errorMessage = null)
    }

    /**
     * Moves a page one place towards the start.
     *
     * @param index The page's current place.
     */
    fun onMovePageUp(index: Int) {
        val current = mutableUiState.value
        if (current.stage != AddStage.READY) return
        mutableUiState.value = current.copy(pages = movedUp(current.pages, index))
    }

    /**
     * Moves a page one place towards the end.
     *
     * @param index The page's current place.
     */
    fun onMovePageDown(index: Int) {
        val current = mutableUiState.value
        if (current.stage != AddStage.READY) return
        mutableUiState.value = current.copy(pages = movedDown(current.pages, index))
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
     * Saves the pages as one document. Asks "Confirm it's you" once if the unlock window has expired.
     *
     * @param activity Shows the fingerprint/PIN prompt if needed.
     */
    fun onSaveClicked(activity: FragmentActivity) {
        val documentId = draftDocumentId ?: return
        val state = mutableUiState.value
        val type = state.documentType ?: return
        if (!state.canSave) return
        val pagesInOrder = state.pages.mapNotNull { draft -> preparedPages[draft.pageId] }
        mutableUiState.value = state.copy(stage = AddStage.SAVING, errorMessage = null)
        val keyAccess = MasterKeyAccess(BiometricReAuthPrompt(activity, unlocker))
        viewModelScope.launch {
            val outcome = saver.save(documentId, pagesInOrder, type, state.name, keyAccess)
            showSaveOutcome(outcome)
        }
    }

    /** Called from "Cancel", "Done" and when leaving the screen: forget everything. */
    fun reset() {
        discardAllPages()
        nameEditedByUser = false
        mutableUiState.value = AddDocumentUiState()
    }

    override fun onCleared() {
        discardAllPages()
    }

    /** Test hook: the ids of the pending pages this screen holds. */
    internal fun pendingPageIdsForTest(): Set<String> = preparedPages.keys.toSet()

    private suspend fun prepareAll(documentId: String, uris: List<Uri>) {
        var lastFailure: AddDocumentFailure? = null
        for (uri in uris) {
            if (!isStillReading(documentId)) return
            if (mutableUiState.value.pages.size >= MAX_PAGES_PER_DOCUMENT) {
                lastFailure = AddDocumentFailure.TOO_MANY_PAGES
                break
            }
            val result = preparer.prepare(documentId, uri)
            if (!isStillReading(documentId)) {
                // Locked or cancelled while preparing: nothing may be kept.
                discardResult(result)
                return
            }
            val failure = keepIfAllowed(result)
            if (failure != null) lastFailure = failure
        }
        val current = mutableUiState.value
        val stage = if (current.pages.isEmpty()) AddStage.CHOOSING else AddStage.READY
        mutableUiState.value = current.copy(stage = stage, errorMessage = lastFailure?.message)
    }

    /** Adds a prepared page to the draft unless it breaks a limit. Returns the failure, if any. */
    private fun keepIfAllowed(result: PrepareResult): AddDocumentFailure? {
        if (result is PrepareResult.Failed) return result.failure
        val prepared = result as PrepareResult.Prepared
        val existingBytes = preparedPages.values.sumOf { page -> page.sizeBytes }
        val problem = pageLimitProblem(preparedPages.size, existingBytes, listOf(prepared.page.sizeBytes))
        if (problem != null) {
            discardResult(prepared)
            return failureForLimit(problem)
        }
        preparedPages[prepared.page.pageId] = prepared.page
        val draft = DraftPage(prepared.page.pageId, prepared.thumbnail, prepared.isPdf)
        val current = mutableUiState.value
        mutableUiState.value = current.copy(pages = current.pages + draft)
        return null
    }

    private fun isStillReading(documentId: String): Boolean {
        return mutableUiState.value.stage == AddStage.READING && documentId == draftDocumentId
    }

    private fun showSaveOutcome(outcome: SaveOutcome) {
        val current = mutableUiState.value
        // The vault may have locked during the save; reset() has then already cleared the screen.
        if (current.stage != AddStage.SAVING) return
        mutableUiState.value = when (outcome) {
            is SaveOutcome.Saved -> {
                // The pages are in the vault now; forget them without deleting anything.
                preparedPages.clear()
                draftDocumentId = null
                current.copy(stage = AddStage.SAVED, pages = emptyList())
            }
            is SaveOutcome.NameRejected -> current.copy(stage = AddStage.READY, nameProblem = outcome.problem)
            is SaveOutcome.Failed -> current.copy(stage = AddStage.READY, errorMessage = outcome.failure.message)
        }
    }

    private fun discardResult(result: PrepareResult) {
        val prepared = result as? PrepareResult.Prepared ?: return
        preparer.discard(listOf(prepared.page))
    }

    private fun discardAllPages() {
        preparer.discard(preparedPages.values.toList())
        preparedPages.clear()
        draftDocumentId = null
    }

    private fun problemIn(name: String): DisplayNameProblem? {
        val result = DisplayNameValidator.validate(name)
        return (result as? DisplayNameResult.Invalid)?.problem
    }
}

package com.heybuddy.ui.adddocument

import androidx.annotation.StringRes
import com.heybuddy.R
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DocumentSummary
import com.heybuddy.data.DocumentType
import com.heybuddy.data.InvalidDisplayNameException
import com.heybuddy.data.PageLimitException
import com.heybuddy.data.PageLimitProblem
import com.heybuddy.data.PreparedPage
import com.heybuddy.data.VaultLockedException
import com.heybuddy.data.VaultRepository
import com.heybuddy.files.FileTooLargeException
import com.heybuddy.files.isStorageFull
import com.heybuddy.security.KeyAccessResult
import com.heybuddy.security.MasterKeyAccess

/**
 * Why adding a document or page failed. Each has a plain message that says what to do next.
 *
 * @property message The text to show the user.
 */
enum class AddDocumentFailure(@StringRes val message: Int) {
    WRONG_TYPE(R.string.add_error_wrong_type),
    TOO_LARGE(R.string.add_error_too_large),
    DAMAGED_IMAGE(R.string.add_error_damaged_image),
    UNREADABLE_FILE(R.string.add_error_unreadable),
    VAULT_LOCKED(R.string.add_error_vault_locked),
    CONFIRMATION_NEEDED(R.string.add_error_confirmation_needed),
    STORAGE_FULL(R.string.add_error_storage_full),
    TOO_MANY_PAGES(R.string.add_error_too_many_pages),
    DOCUMENT_TOO_LARGE(R.string.add_error_document_too_large),
    OTHER(R.string.add_error_other),
}

/** The outcome of one save attempt. */
sealed interface SaveOutcome {

    /**
     * The document is in the vault.
     *
     * @property document What was saved.
     */
    data class Saved(val document: DocumentSummary) : SaveOutcome

    /**
     * The name broke a rule; nothing was saved.
     *
     * @property problem Which rule.
     */
    data class NameRejected(val problem: DisplayNameProblem) : SaveOutcome

    /**
     * Nothing was saved.
     *
     * @property failure Why.
     */
    data class Failed(val failure: AddDocumentFailure) : SaveOutcome
}

/**
 * Saves prepared pages into the vault and turns every possible error into an outcome.
 * Kept separate from the screens so the whole flow can be tested without UI.
 *
 * @param repository Saves the documents and pages.
 */
class AddDocumentSaver(private val repository: VaultRepository) {

    /**
     * Saves a new document. Never throws: every failure comes back as an outcome.
     * On failure the pages stay pending, so the user can fix the problem and tap Save again.
     *
     * @param documentId The id the pages were prepared for.
     * @param pages The pages, first page first.
     * @param type The type the user chose.
     * @param displayName The name the user typed.
     * @param keyAccess Asks "Confirm it's you" and retries once if the unlock window has expired.
     * @return What happened.
     */
    suspend fun save(
        documentId: String,
        pages: List<PreparedPage>,
        type: DocumentType,
        displayName: String,
        keyAccess: MasterKeyAccess,
    ): SaveOutcome {
        val result = keyAccess.runSuspending { repository.saveDocument(documentId, pages, displayName, type) }
        return when (result) {
            is KeyAccessResult.Success -> SaveOutcome.Saved(result.value)
            is KeyAccessResult.NeedsConfirmation -> SaveOutcome.Failed(AddDocumentFailure.CONFIRMATION_NEEDED)
            is KeyAccessResult.Failed -> outcomeForError(result.error)
        }
    }

    /**
     * Adds pages to the end of an existing document (the viewer's "Add page").
     *
     * @param documentId The document's id.
     * @param pages Pages prepared for it.
     * @param keyAccess Asks "Confirm it's you" and retries once if the unlock window has expired.
     * @return null when the pages were added, otherwise why not (the pages then stay pending).
     */
    suspend fun addPages(documentId: String, pages: List<PreparedPage>, keyAccess: MasterKeyAccess): AddDocumentFailure? {
        val result = keyAccess.runSuspending { repository.addPages(documentId, pages) }
        return when (result) {
            is KeyAccessResult.Success -> null
            is KeyAccessResult.NeedsConfirmation -> AddDocumentFailure.CONFIRMATION_NEEDED
            is KeyAccessResult.Failed -> failureFor(result.error)
        }
    }

    private fun outcomeForError(error: Throwable): SaveOutcome {
        if (error is InvalidDisplayNameException) return SaveOutcome.NameRejected(error.problem)
        return SaveOutcome.Failed(failureFor(error))
    }

    private fun failureFor(error: Throwable): AddDocumentFailure {
        return when {
            error is VaultLockedException -> AddDocumentFailure.VAULT_LOCKED
            error is FileTooLargeException -> AddDocumentFailure.TOO_LARGE
            error is PageLimitException -> failureForLimit(error.problem)
            isStorageFull(error) -> AddDocumentFailure.STORAGE_FULL
            else -> AddDocumentFailure.OTHER
        }
    }
}

/**
 * The message for a broken document limit.
 *
 * @param problem Which limit.
 * @return The matching failure.
 */
fun failureForLimit(problem: PageLimitProblem): AddDocumentFailure {
    return when (problem) {
        PageLimitProblem.TOO_MANY_PAGES -> AddDocumentFailure.TOO_MANY_PAGES
        PageLimitProblem.DOCUMENT_TOO_LARGE -> AddDocumentFailure.DOCUMENT_TOO_LARGE
    }
}

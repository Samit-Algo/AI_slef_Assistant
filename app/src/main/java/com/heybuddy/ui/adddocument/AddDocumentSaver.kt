package com.heybuddy.ui.adddocument

import androidx.annotation.StringRes
import com.heybuddy.R
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DocumentSummary
import com.heybuddy.data.DocumentType
import com.heybuddy.data.InvalidDisplayNameException
import com.heybuddy.data.VaultLockedException
import com.heybuddy.data.VaultRepository
import com.heybuddy.files.FileTooLargeException
import com.heybuddy.files.PickedFile
import com.heybuddy.files.isStorageFull
import com.heybuddy.security.KeyAccessResult
import com.heybuddy.security.MasterKeyAccess
import java.io.ByteArrayInputStream

/**
 * Why adding a document failed. Each has a plain message that says what to do next.
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
 * Saves a picked file into the vault and turns every possible error into a [SaveOutcome].
 * Kept separate from the screen so the whole flow can be tested without UI.
 *
 * @param repository Saves the document.
 * @param freeSpaceBytes How much storage space is free for the vault right now.
 */
class AddDocumentSaver(
    private val repository: VaultRepository,
    private val freeSpaceBytes: () -> Long,
) {

    /**
     * Saves the file. Never throws: every failure comes back as an outcome.
     *
     * @param pickedFile The file, already read and type-checked. Not wiped here (the caller may
     *   want to retry); the caller wipes it when done.
     * @param type The type the user chose.
     * @param displayName The name the user typed.
     * @param keyAccess Asks "Confirm it's you" and retries once if the unlock window has expired.
     * @return What happened.
     */
    suspend fun save(
        pickedFile: PickedFile,
        type: DocumentType,
        displayName: String,
        keyAccess: MasterKeyAccess,
    ): SaveOutcome {
        if (!hasRoomFor(pickedFile)) {
            return SaveOutcome.Failed(AddDocumentFailure.STORAGE_FULL)
        }
        val result = keyAccess.runSuspending {
            // A new stream for every attempt, so a retry after "Confirm it's you" reads from the start.
            val input = ByteArrayInputStream(pickedFile.bytes)
            repository.saveDocument(input, displayName, type, pickedFile.type.mimeType)
        }
        return toOutcome(result)
    }

    private fun hasRoomFor(pickedFile: PickedFile): Boolean {
        // The encrypted copy is a little bigger than the original; keep a safety margin on top.
        val needed = pickedFile.bytes.size + pickedFile.bytes.size / ENCRYPTION_OVERHEAD_DIVISOR + SAFETY_MARGIN_BYTES
        return freeSpaceBytes() >= needed
    }

    private fun toOutcome(result: KeyAccessResult<DocumentSummary>): SaveOutcome {
        return when (result) {
            is KeyAccessResult.Success -> SaveOutcome.Saved(result.value)
            is KeyAccessResult.NeedsConfirmation -> SaveOutcome.Failed(AddDocumentFailure.CONFIRMATION_NEEDED)
            is KeyAccessResult.Failed -> outcomeForError(result.error)
        }
    }

    private fun outcomeForError(error: Throwable): SaveOutcome {
        return when {
            error is InvalidDisplayNameException -> SaveOutcome.NameRejected(error.problem)
            error is VaultLockedException -> SaveOutcome.Failed(AddDocumentFailure.VAULT_LOCKED)
            error is FileTooLargeException -> SaveOutcome.Failed(AddDocumentFailure.TOO_LARGE)
            isStorageFull(error) -> SaveOutcome.Failed(AddDocumentFailure.STORAGE_FULL)
            else -> SaveOutcome.Failed(AddDocumentFailure.OTHER)
        }
    }

    private companion object {
        /** Tink adds well under 1% to a file; 1/64 is a generous estimate. */
        const val ENCRYPTION_OVERHEAD_DIVISOR = 64

        /** Room for the database row and other small writes during a save. */
        const val SAFETY_MARGIN_BYTES = 1024L * 1024L
    }
}

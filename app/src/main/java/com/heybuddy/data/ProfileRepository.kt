package com.heybuddy.data

import com.heybuddy.facts.Verhoeff
import com.heybuddy.util.AADHAAR_LAST_DIGITS
import com.heybuddy.util.LogEvent
import com.heybuddy.util.MAX_FACT_VALUE_LENGTH
import com.heybuddy.util.SafeLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * A detail the user confirmed and wants saved.
 *
 * @property kind What it is. For Aadhaar pass [ProfileFactKind.AADHAAR_FULL] with the 12 digits; the
 *   repository keeps only the last 4 unless the user chose to keep the full number.
 * @property value The confirmed value (dates YYYY-MM-DD).
 * @property documentId The source document, or null if typed.
 * @property pageId The source page, or null if typed.
 * @property origin Where it came from.
 */
data class NewProfileFact(
    val kind: ProfileFactKind,
    val value: String,
    val documentId: String?,
    val pageId: String?,
    val origin: FactOrigin,
)

/**
 * A stored detail, as screens see it.
 *
 * @property factId Its id.
 * @property kind What it is.
 * @property value Its value.
 * @property documentId The source document, or null.
 * @property pageId The source page, or null.
 * @property sourceDeleted True when the source was deleted but the detail kept ("page deleted").
 * @property origin Where it came from.
 * @property chosen True for the value shown when documents disagree.
 */
data class ProfileFact(
    val factId: String,
    val kind: ProfileFactKind,
    val value: String,
    val documentId: String?,
    val pageId: String?,
    val sourceDeleted: Boolean,
    val origin: FactOrigin,
    val chosen: Boolean,
)

/** The value does not fit its kind (for example an Aadhaar number that fails its check digit). */
class InvalidFactValueException : IllegalArgumentException("This value does not fit this detail")

/**
 * The only class screens use for the confirmed profile details (spec 7.10). Everything is stored in
 * the vault's SQLCipher-encrypted database, through [VaultDatabaseHolder] like the documents, so it
 * is readable only while the vault is unlocked, and "Delete everything" removes it with the
 * database. Nothing is saved without the user confirming it (Task 2.9).
 *
 * @param holder Opens the database while unlocked.
 * @param clock Current time in epoch milliseconds (tests pass a fixed clock).
 * @param newId Makes new ids.
 * @param ioDispatcher Where database work runs.
 */
class ProfileRepository(
    private val holder: VaultDatabaseHolder,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Saves confirmed details in one transaction. Aadhaar (owner rule 7.3-4): only the LAST 4
     * digits are kept, unless [keepFullAadhaar] is true because the user explicitly ticked it.
     *
     * @param facts The confirmed details.
     * @param keepFullAadhaar True only after the user's explicit choice.
     * @return How many were added (the same value of the same kind is not added twice).
     * @throws InvalidFactValueException If a value does not fit its kind.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun saveConfirmed(facts: List<NewProfileFact>, keepFullAadhaar: Boolean): Int {
        val now = clock()
        val rows = facts.map { fact -> rowFor(storableFact(fact, keepFullAadhaar), now) }
        val added = withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database -> database.profileFactDao().insertConfirmed(rows) }
        }
        SafeLog.info(LogEvent.DETAILS_SAVED, count = added)
        return added
    }

    /**
     * @return Every stored detail, oldest first.
     * @throws VaultLockedException If the vault is locked.
     */
    suspend fun allFacts(): List<ProfileFact> {
        val rows = withContext(ioDispatcher) { holder.withDatabase { database -> database.profileFactDao().all() } }
        return rows.mapNotNull { row -> row.toFact() }
    }

    /**
     * Changes a detail's value; it then counts as typed by the user.
     *
     * @param factId The detail.
     * @param newValue The new value.
     * @return False if there is no such detail.
     * @throws InvalidFactValueException If the value does not fit the detail's kind.
     */
    suspend fun editFact(factId: String, newValue: String): Boolean {
        val changed = withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database ->
                val dao = database.profileFactDao()
                val row = dao.findById(factId) ?: return@withDatabase 0
                val kind = ProfileFactKind.fromStoredValue(row.kind) ?: throw InvalidFactValueException()
                val value = checkedValue(kind, newValue)
                dao.updateValue(factId, value, FactOrigin.TYPED.name, clock())
            }
        }
        if (changed > 0) SafeLog.info(LogEvent.DETAIL_EDITED)
        return changed > 0
    }

    /**
     * Deletes one detail for good.
     *
     * @param factId The detail.
     * @return False if there is no such detail.
     */
    suspend fun deleteFact(factId: String): Boolean {
        val deleted = withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database -> database.profileFactDao().deleteAndRepair(factId) }
        }
        if (deleted) SafeLog.info(LogEvent.DETAIL_DELETED)
        return deleted
    }

    /**
     * The user picked the correct value when documents disagree.
     *
     * @param factId The picked detail.
     * @return False if there is no such detail.
     */
    suspend fun chooseFact(factId: String): Boolean {
        return withContext(ioDispatcher + NonCancellable) {
            holder.withDatabase { database -> database.profileFactDao().choose(factId) }
        }
    }

    /**
     * For the delete dialogs ("Also delete the N details read from this document?").
     *
     * @param documentId The document.
     * @return How many details were read from it.
     */
    suspend fun countForDocument(documentId: String): Int {
        return withContext(ioDispatcher) { holder.withDatabase { database -> database.profileFactDao().countForDocument(documentId) } }
    }

    /**
     * For the page delete dialog ("Also delete the N details read from this page?").
     *
     * @param pageId The page.
     * @return How many details were read from it.
     */
    suspend fun countForPage(pageId: String): Int {
        return withContext(ioDispatcher) { holder.withDatabase { database -> database.profileFactDao().countForPage(pageId) } }
    }

    /** Applies the Aadhaar rule and checks the value. */
    private fun storableFact(fact: NewProfileFact, keepFullAadhaar: Boolean): NewProfileFact {
        if (fact.kind != ProfileFactKind.AADHAAR_FULL) return fact.copy(value = checkedValue(fact.kind, fact.value))
        val digits = checkedValue(ProfileFactKind.AADHAAR_FULL, fact.value)
        if (keepFullAadhaar) return fact.copy(value = digits)
        return fact.copy(kind = ProfileFactKind.AADHAAR_LAST4, value = digits.takeLast(AADHAAR_LAST_DIGITS))
    }

    private fun checkedValue(kind: ProfileFactKind, raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_FACT_VALUE_LENGTH) throw InvalidFactValueException()
        return when (kind) {
            ProfileFactKind.AADHAAR_FULL -> fullAadhaar(value)
            ProfileFactKind.AADHAAR_LAST4 -> lastFour(value)
            else -> value
        }
    }

    private fun fullAadhaar(value: String): String {
        val digits = value.filter { character -> character != ' ' && character != '-' }
        val valid = digits.length == AADHAAR_LENGTH && digits.all { character -> character in '0'..'9' } && Verhoeff.isValid(digits)
        if (!valid) throw InvalidFactValueException()
        return digits
    }

    private fun lastFour(value: String): String {
        val valid = value.length == AADHAAR_LAST_DIGITS && value.all { character -> character in '0'..'9' }
        if (!valid) throw InvalidFactValueException()
        return value
    }

    private fun rowFor(fact: NewProfileFact, now: Long): ProfileFactEntity {
        return ProfileFactEntity(
            factId = newId(),
            kind = fact.kind.name,
            value = fact.value,
            documentId = fact.documentId,
            pageId = fact.pageId,
            sourceDeletedAt = null,
            origin = fact.origin.name,
            // Decided inside the save transaction (see ProfileFactDao.insertConfirmed).
            chosen = false,
            confirmedAt = now,
            updatedAt = now,
        )
    }

    private companion object {
        const val AADHAAR_LENGTH = 12
    }
}

/** A row of an unknown kind (written by a newer app version) is skipped, never shown wrongly. */
private fun ProfileFactEntity.toFact(): ProfileFact? {
    val kind = ProfileFactKind.fromStoredValue(kind) ?: return null
    val origin = FactOrigin.entries.firstOrNull { entry -> entry.name == origin } ?: FactOrigin.TYPED
    return ProfileFact(
        factId = factId,
        kind = kind,
        value = value,
        documentId = documentId,
        pageId = pageId,
        sourceDeleted = sourceDeletedAt != null,
        origin = origin,
        chosen = chosen,
    )
}

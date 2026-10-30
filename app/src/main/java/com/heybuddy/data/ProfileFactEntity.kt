package com.heybuddy.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a stored detail is (spec 7.10). Stored by name, so the order here never matters. */
enum class ProfileFactKind {
    FULL_NAME,
    FATHER_NAME,
    HUSBAND_NAME,
    RELATIVE_NAME,
    DATE_OF_BIRTH,
    YEAR_OF_BIRTH,
    GENDER,
    PAN,

    /** The last 4 digits only: the default for Aadhaar (owner rule 7.3-4). */
    AADHAAR_LAST4,

    /** The full 12 digits: only when the user explicitly chose to keep them. */
    AADHAAR_FULL,
    DRIVING_LICENCE_NUMBER,
    ADDRESS,
    PIN_CODE,
    FULL_NAME_TAMIL,
    FATHER_NAME_TAMIL,
    ADDRESS_TAMIL,
    ;

    companion object {
        /**
         * Reads a kind back from the database.
         *
         * @param storedValue The enum name saved in the `kind` column.
         * @return The kind, or null if unknown (for example written by a newer app version).
         */
        fun fromStoredValue(storedValue: String): ProfileFactKind? = entries.firstOrNull { kind -> kind.name == storedValue }
    }
}

/** Where a stored detail came from. */
enum class FactOrigin {
    /** Read from a page and confirmed by the user. */
    OCR,

    /** From a verified Aadhaar Secure QR (Task 2.12). */
    QR_VERIFIED,

    /** Typed or corrected by the user. */
    TYPED,
}

/**
 * One row of the `profile_facts` table (spec 7.10): a detail the user CONFIRMED. It lives in the
 * same SQLCipher-encrypted vault database as the documents, never in a separate file.
 *
 * The source columns point at the document and page it was read from. If the user deletes that
 * page or document but keeps the detail, they become null and [sourceDeletedAt] is set ("page
 * deleted"). The foreign keys do the same on their own (SET NULL) as a safety net.
 *
 * @property factId UUID.
 * @property kind The [ProfileFactKind] name.
 * @property value As confirmed by the user (dates as YYYY-MM-DD).
 * @property documentId The source document, or null (typed, or source deleted).
 * @property pageId The source page, or null (typed, or source deleted).
 * @property sourceDeletedAt When the source was deleted while the detail was kept, or null.
 * @property origin The [FactOrigin] name.
 * @property chosen True for the value the user picked when documents disagree (true when it is the only one).
 * @property confirmedAt When the user confirmed it (epoch milliseconds).
 * @property updatedAt When it last changed (epoch milliseconds).
 */
@Entity(
    tableName = "profile_facts",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = DocumentPageEntity::class, parentColumns = ["pageId"], childColumns = ["pageId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["documentId"]), Index(value = ["pageId"])],
)
data class ProfileFactEntity(
    @PrimaryKey val factId: String,
    val kind: String,
    val value: String,
    val documentId: String?,
    val pageId: String?,
    val sourceDeletedAt: Long?,
    val origin: String,
    val chosen: Boolean,
    val confirmedAt: Long,
    val updatedAt: Long,
)

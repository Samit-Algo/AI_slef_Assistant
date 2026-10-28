package com.heybuddy.security

import com.heybuddy.util.DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX
import com.heybuddy.util.PAGE_ASSOCIATED_DATA_INFIX
import com.heybuddy.util.PAGE_FORMAT_CURRENT
import com.heybuddy.util.PAGE_FORMAT_LEGACY
import java.util.UUID

/**
 * Binds one page's key and encrypted file to its document and to itself (rule 4, Task 1.10p).
 *
 * The associated data is "document:<documentId>:page:<pageId>". So a page file cannot be moved
 * to another document, swapped with another page, or given another page's key: each fails to
 * decrypt. The page ORDER is not in here on purpose (owner decision): it lives in the encrypted
 * database, so reordering or removing a page never needs re-encryption.
 *
 * Pages migrated from before Task 1.10p keep their old label "document:<documentId>"
 * ([PAGE_FORMAT_LEGACY]). Only [forStoredPage] can make such a binding, and only when the page id
 * equals the document id (as the migration writes it). New pages ([forNewPage]) never use it.
 *
 * @property documentId The owning document's id (a UUID).
 * @property pageId The page's own id (a UUID); also its file name, vault/<pageId>.bin.
 * @property formatVersion [PAGE_FORMAT_CURRENT] or [PAGE_FORMAT_LEGACY].
 */
class PageBinding private constructor(
    val documentId: String,
    val pageId: String,
    val formatVersion: Int,
) {

    /** True for pages saved with the current format (every page made since Task 1.10p). */
    val isCurrentFormat: Boolean
        get() = formatVersion == PAGE_FORMAT_CURRENT

    /**
     * The associated data for this page's wrapped key and encrypted file.
     *
     * @return The label as UTF-8 bytes.
     */
    fun associatedData(): ByteArray {
        val label = if (isCurrentFormat) {
            DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX + documentId + PAGE_ASSOCIATED_DATA_INFIX + pageId
        } else {
            DOCUMENT_KEY_ASSOCIATED_DATA_PREFIX + documentId
        }
        return label.toByteArray(Charsets.UTF_8)
    }

    /** Factories: the only ways to make a binding. */
    companion object {

        /**
         * The binding for a brand-new page. Always the current format.
         *
         * @param documentId The owning document's id.
         * @param pageId The new page's id.
         * @return The binding.
         * @throws IllegalArgumentException If either id is not a UUID in standard lowercase form.
         */
        fun forNewPage(documentId: String, pageId: String): PageBinding {
            requireUuid(documentId)
            requireUuid(pageId)
            return PageBinding(documentId, pageId, PAGE_FORMAT_CURRENT)
        }

        /**
         * The binding for a page read from the database.
         *
         * @param documentId The owning document's id.
         * @param pageId The page's id.
         * @param formatVersion The page's stored format.
         * @return The binding.
         * @throws IllegalArgumentException If an id is not a UUID, the format is unknown, or a
         *   legacy page's id differs from its document's id (the migration never writes that).
         */
        fun forStoredPage(documentId: String, pageId: String, formatVersion: Int): PageBinding {
            requireUuid(documentId)
            requireUuid(pageId)
            when (formatVersion) {
                PAGE_FORMAT_CURRENT -> Unit
                PAGE_FORMAT_LEGACY -> require(pageId == documentId) { "A legacy page's id must equal its document's id" }
                else -> throw IllegalArgumentException("Unknown page format")
            }
            return PageBinding(documentId, pageId, formatVersion)
        }

        // Ids become file names and labels, so they must be plain UUIDs (no "../" or slashes).
        private fun requireUuid(id: String) {
            val parsed = try {
                UUID.fromString(id)
            } catch (notAUuid: IllegalArgumentException) {
                throw IllegalArgumentException("Id must be a UUID", notAUuid)
            }
            require(parsed.toString() == id) { "Id must be a UUID in standard lowercase form" }
        }
    }
}

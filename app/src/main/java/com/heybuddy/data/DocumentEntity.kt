package com.heybuddy.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of the `documents` table (spec 5.4): what the user sees as one document.
 * Its pages, with their keys, are in `document_pages` (Task 1.10p).
 *
 * @property id UUID; part of every page's associated data.
 * @property displayName User-editable name, already validated by [DisplayNameValidator].
 * @property type The [DocumentType] name.
 * @property createdAt When it was added (epoch milliseconds).
 * @property updatedAt When it was last changed (epoch milliseconds).
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val type: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One row of the `document_pages` table (Task 1.10p): one page of a document, with its own key
 * and its own encrypted file, vault/<pageId>.bin.
 *
 * The page ORDER lives only here, inside the encrypted database (owner decision). The unique index
 * on (documentId, position) means two pages can never claim the same place.
 *
 * Deleting a document deletes its page rows with it (CASCADE). The repository also deletes them
 * itself in the same transaction, so this is only a safety net.
 *
 * A plain class rather than a data class: data classes compare ByteArray fields by reference,
 * which would make equality misleading for [wrappedKey].
 *
 * @property pageId UUID; also the encrypted file's name.
 * @property documentId The document this page belongs to.
 * @property position The page's place in the document: 0, 1, 2…, with no gaps.
 * @property mimeType Detected from the file's bytes, not its name.
 * @property sizeBytes Size of the original (plaintext) file.
 * @property wrappedKey This page's key, encrypted by the master key.
 * @property formatVersion Which associated data binds the key and file (see PageBinding).
 */
@Entity(
    tableName = "document_pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "position"], unique = true)],
)
class DocumentPageEntity(
    @PrimaryKey val pageId: String,
    val documentId: String,
    val position: Int,
    val mimeType: String,
    val sizeBytes: Long,
    val wrappedKey: ByteArray,
    val formatVersion: Int,
)

/**
 * A document row for lists, with its number of pages.
 *
 * @property id The document's id.
 * @property displayName The user's name for it.
 * @property type The [DocumentType] name.
 * @property createdAt When it was added.
 * @property updatedAt When it was last changed.
 * @property pageCount How many pages it has.
 */
data class DocumentListRow(
    val id: String,
    val displayName: String,
    val type: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
)

/**
 * A page row without its wrapped key. Screens never need (or see) the key.
 *
 * @property pageId The page's id.
 * @property documentId The document it belongs to.
 * @property position Its place in the document, starting at 0.
 * @property mimeType The detected file type.
 * @property sizeBytes The plaintext size.
 */
data class PageListRow(
    val pageId: String,
    val documentId: String,
    val position: Int,
    val mimeType: String,
    val sizeBytes: Long,
)

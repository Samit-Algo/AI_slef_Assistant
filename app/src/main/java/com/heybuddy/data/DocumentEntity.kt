package com.heybuddy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of the `documents` table (spec 5.4).
 *
 * A plain class rather than a data class: data classes compare ByteArray fields by reference,
 * which would make equality misleading for [wrappedKey].
 *
 * @property id UUID; also the associated data that binds the file and key to this row.
 * @property displayName User-editable name, already validated by [DisplayNameValidator].
 * @property type The [DocumentType] name.
 * @property mimeType Detected from the file's bytes, not its name.
 * @property sizeBytes Size of the original (plaintext) file.
 * @property wrappedKey This document's key, encrypted by the master key.
 * @property createdAt When it was added (epoch milliseconds).
 * @property updatedAt When it was last changed (epoch milliseconds).
 */
@Entity(tableName = "documents")
class DocumentEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val type: String,
    val mimeType: String,
    val sizeBytes: Long,
    val wrappedKey: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A row without its wrapped key, for lists. Screens never need (or see) the key.
 *
 * @property id The document's id.
 * @property displayName The user's name for it.
 * @property type The [DocumentType] name.
 * @property mimeType The detected file type.
 * @property sizeBytes The plaintext size.
 * @property createdAt When it was added.
 * @property updatedAt When it was last changed.
 */
data class DocumentListRow(
    val id: String,
    val displayName: String,
    val type: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

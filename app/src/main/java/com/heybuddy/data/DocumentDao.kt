package com.heybuddy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Database access for the `documents` table. Only [VaultRepository] uses this. */
@Dao
interface DocumentDao {

    /**
     * Adds a new document row.
     *
     * ABORT, never REPLACE: replacing would silently drop an existing row and its wrapped key,
     * destroying that document.
     *
     * @param document The row to add.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(document: DocumentEntity)

    /**
     * Watches all documents, newest first, without their wrapped keys.
     *
     * @return A live list that updates when rows change.
     */
    @Query(
        "SELECT id, displayName, type, mimeType, sizeBytes, createdAt, updatedAt " +
            "FROM documents ORDER BY createdAt DESC",
    )
    fun observeAll(): Flow<List<DocumentListRow>>

    /**
     * Finds one full row, including its wrapped key.
     *
     * @param id The document's id.
     * @return The row, or null if there is none.
     */
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun findById(id: String): DocumentEntity?

    /**
     * Changes a document's name.
     *
     * @param id The document's id.
     * @param displayName The new, already validated name.
     * @param updatedAt The time of the change.
     * @return How many rows changed (0 if the id does not exist).
     */
    @Query("UPDATE documents SET displayName = :displayName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, displayName: String, updatedAt: Long): Int

    /**
     * Deletes a row, and with it the document's wrapped key.
     *
     * @param id The document's id.
     * @return How many rows were deleted (0 if the id does not exist).
     */
    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

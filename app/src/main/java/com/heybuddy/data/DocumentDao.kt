package com.heybuddy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `documents` and `document_pages` tables. Only [VaultRepository] uses this.
 *
 * Every change that touches more than one row runs in ONE transaction, so a crash can never leave
 * a document with half of its pages, or pages numbered with a gap.
 *
 * Inserts use ABORT, never REPLACE: replacing would silently drop an existing row and its wrapped
 * key, destroying that page.
 */
@Dao
abstract class DocumentDao {

    /**
     * Adds a new document and all of its pages together.
     *
     * @param document The document row.
     * @param pages Its pages, already numbered 0, 1, 2…
     */
    @Transaction
    open suspend fun insertDocumentWithPages(document: DocumentEntity, pages: List<DocumentPageEntity>) {
        insertDocument(document)
        insertPages(pages)
    }

    /**
     * Adds pages to the end of an existing document and marks the document as changed.
     *
     * @param documentId The document's id.
     * @param pages The new pages, already numbered after the existing ones.
     * @param updatedAt The time of the change.
     */
    @Transaction
    open suspend fun appendPages(documentId: String, pages: List<DocumentPageEntity>, updatedAt: Long) {
        insertPages(pages)
        touch(documentId, updatedAt)
    }

    /**
     * Removes one page (and with it the page's wrapped key), then numbers the rest 0, 1, 2… again.
     *
     * @param documentId The page's document.
     * @param pageId The page to remove.
     * @param updatedAt The time of the change.
     */
    @Transaction
    open suspend fun removePageAndRenumber(documentId: String, pageId: String, updatedAt: Long) {
        deletePageRow(pageId)
        val remaining = pagesOf(documentId)
        val changes = positionChangesAfterRemoval(remaining.map { page -> PagePosition(page.pageId, page.position) })
        // Ascending order: each page moves into the place the one before it has just left, so the
        // unique (documentId, position) index never sees two pages in one place.
        for (change in changes) {
            setPosition(change.pageId, change.position)
        }
        touch(documentId, updatedAt)
    }

    /**
     * Deletes a document and all of its page rows (with their wrapped keys) together.
     *
     * @param documentId The document's id.
     * @return The ids of the deleted pages (their files can now be deleted), or empty if there was
     *   no such document.
     */
    @Transaction
    open suspend fun deleteDocumentWithPages(documentId: String): List<String> {
        val pageIds = pagesOf(documentId).map { page -> page.pageId }
        deletePagesOf(documentId)
        deleteDocumentRow(documentId)
        return pageIds
    }

    /**
     * Watches all documents, newest first, with their page counts.
     *
     * @return A live list that updates when rows change.
     */
    @Query(
        "SELECT id, displayName, type, createdAt, updatedAt, " +
            "(SELECT COUNT(*) FROM document_pages WHERE document_pages.documentId = documents.id) AS pageCount " +
            "FROM documents ORDER BY createdAt DESC",
    )
    abstract fun observeAll(): Flow<List<DocumentListRow>>

    /**
     * Finds one document row.
     *
     * @param id The document's id.
     * @return The row, or null if there is none.
     */
    @Query("SELECT * FROM documents WHERE id = :id")
    abstract suspend fun findById(id: String): DocumentEntity?

    /**
     * Lists a document's pages in order, without their keys.
     *
     * @param documentId The document's id.
     * @return The pages, first page first.
     */
    @Query(
        "SELECT pageId, documentId, position, mimeType, sizeBytes FROM document_pages " +
            "WHERE documentId = :documentId ORDER BY position",
    )
    abstract suspend fun pagesOf(documentId: String): List<PageListRow>

    /**
     * Finds one full page row, including its wrapped key.
     *
     * @param pageId The page's id.
     * @return The row, or null if there is none.
     */
    @Query("SELECT * FROM document_pages WHERE pageId = :pageId")
    abstract suspend fun findPage(pageId: String): DocumentPageEntity?

    /**
     * Lists every page id (for orphan file cleanup).
     *
     * @return All page ids in the table.
     */
    @Query("SELECT pageId FROM document_pages")
    abstract suspend fun allPageIds(): List<String>

    /**
     * Changes a document's name.
     *
     * @param id The document's id.
     * @param displayName The new, already validated name.
     * @param updatedAt The time of the change.
     * @return How many rows changed (0 if the id does not exist).
     */
    @Query("UPDATE documents SET displayName = :displayName, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun rename(id: String, displayName: String, updatedAt: Long): Int

    /**
     * Adds a document row on its own.
     *
     * @param document The row to add.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDocument(document: DocumentEntity)

    /**
     * Adds page rows.
     *
     * @param pages The rows to add.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPages(pages: List<DocumentPageEntity>)

    /**
     * Marks a document as changed.
     *
     * @param id The document's id.
     * @param updatedAt The time of the change.
     */
    @Query("UPDATE documents SET updatedAt = :updatedAt WHERE id = :id")
    protected abstract suspend fun touch(id: String, updatedAt: Long)

    /**
     * Moves a page to another place.
     *
     * @param pageId The page's id.
     * @param position Its new place.
     */
    @Query("UPDATE document_pages SET position = :position WHERE pageId = :pageId")
    protected abstract suspend fun setPosition(pageId: String, position: Int)

    /**
     * Deletes one page row, and with it the page's wrapped key.
     *
     * @param pageId The page's id.
     */
    @Query("DELETE FROM document_pages WHERE pageId = :pageId")
    protected abstract suspend fun deletePageRow(pageId: String)

    /**
     * Deletes all page rows of a document.
     *
     * @param documentId The document's id.
     */
    @Query("DELETE FROM document_pages WHERE documentId = :documentId")
    protected abstract suspend fun deletePagesOf(documentId: String)

    /**
     * Deletes a document row.
     *
     * @param id The document's id.
     */
    @Query("DELETE FROM documents WHERE id = :id")
    protected abstract suspend fun deleteDocumentRow(id: String)
}

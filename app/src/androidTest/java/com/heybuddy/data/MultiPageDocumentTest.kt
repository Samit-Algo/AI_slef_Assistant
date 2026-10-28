package com.heybuddy.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.security.DocumentUnreadableException
import com.heybuddy.security.requirePhoneUnlocked
import com.heybuddy.util.MAX_FILE_BYTES
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT
import com.heybuddy.util.PAGE_FORMAT_CURRENT
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * Documents with several pages (Task 1.10p), on a real phone: each page is its own encrypted file
 * with its own key, bound to "document:<documentId>:page:<pageId>". Uses a test vault only.
 */
@RunWith(AndroidJUnit4::class)
class MultiPageDocumentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vault = TestVault(context, name = "pages")
    private val repository = vault.repository

    @Before
    fun startClean() {
        requirePhoneUnlocked()
        vault.deleteAll()
        vault.unlock()
    }

    @After
    fun cleanUp() {
        vault.deleteAll()
    }

    @Test
    fun threePagesSaveAndOpenInOrderWithExactlyTheirOwnBytes() = runBlocking {
        val contents = listOf(TestVault.sampleJpegBytes(), TestVault.sampleTwoPagePdfBytes(), randomBytes(seed = 3))
        val mimeTypes = listOf(MIME_JPEG, MIME_PDF, MIME_JPEG)

        val saved = saveDocument(contents, mimeTypes)

        val details = repository.documentDetails(saved.id)
        assertEquals(3, saved.pageCount)
        assertEquals(listOf(0, 1, 2), details.pages.map { page -> page.position })
        assertEquals(mimeTypes, details.pages.map { page -> page.mimeType })
        for ((index, page) in details.pages.withIndex()) {
            assertArrayEquals("page ${index + 1}", contents[index], openBytes(saved.id, page))
        }
    }

    /** Owner rule (Task 1.10p): every new page uses the new binding, never the old one. */
    @Test
    fun everyNewPageUsesTheCurrentFormat() = runBlocking {
        val saved = saveDocument(listOf(randomBytes(1), randomBytes(2)))
        repository.addPages(saved.id, listOf(prepare(saved.id, randomBytes(3))))

        val formats = vault.holder.withDatabase { database ->
            database.documentDao().pagesOf(saved.id).map { page -> database.documentDao().findPage(page.pageId)!!.formatVersion }
        }

        assertEquals(listOf(PAGE_FORMAT_CURRENT, PAGE_FORMAT_CURRENT, PAGE_FORMAT_CURRENT), formats)
    }

    @Test
    fun swappingTwoPageFilesMakesBothUnreadable() = runBlocking {
        val saved = saveDocument(listOf(randomBytes(1), randomBytes(2)))
        val (firstFile, secondFile) = vault.pageFilesOf(saved.id)
        val firstBytes = firstFile.readBytes()
        firstFile.writeBytes(secondFile.readBytes())
        secondFile.writeBytes(firstBytes)

        for (page in repository.documentDetails(saved.id).pages) {
            val outcome = runCatching { repository.openPage(saved.id, page.pageId) }
            assertTrue("Got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is DocumentUnreadableException)
        }
    }

    @Test
    fun aPageFileCopiedIntoAnotherDocumentCannotBeOpened() = runBlocking {
        val documentA = saveDocument(listOf(randomBytes(1)))
        val documentB = saveDocument(listOf(randomBytes(2)))
        val fileOfA = vault.pageFilesOf(documentA.id).single()
        val fileOfB = vault.pageFilesOf(documentB.id).single()

        fileOfB.writeBytes(fileOfA.readBytes())

        val pageOfB = repository.documentDetails(documentB.id).pages.single()
        val outcome = runCatching { repository.openPage(documentB.id, pageOfB.pageId) }
        assertTrue("Got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is DocumentUnreadableException)
    }

    @Test
    fun aPageCannotBeOpenedThroughAnotherDocument() = runBlocking {
        val documentA = saveDocument(listOf(randomBytes(1)))
        val documentB = saveDocument(listOf(randomBytes(2)))
        val pageOfA = repository.documentDetails(documentA.id).pages.single()

        val outcome = runCatching { repository.openPage(documentB.id, pageOfA.pageId) }

        assertTrue("Got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is PageNotFoundException)
    }

    /**
     * Pages are bound by id, so even an edited order in the database (which SQLCipher already
     * protects) can only change the ORDER: each page still opens with its own content, never
     * another page's.
     */
    @Test
    fun anEditedOrderNeverMixesUpPageContents() = runBlocking {
        val contents = listOf(randomBytes(1), randomBytes(2))
        val saved = saveDocument(contents)
        val before = repository.documentDetails(saved.id).pages

        vault.holder.withDatabase { database ->
            val sql = database.openHelper.writableDatabase
            // Through a free place, because two pages may never share one.
            sql.execSQL("UPDATE document_pages SET position = 99 WHERE pageId = ?", arrayOf(before[0].pageId))
            sql.execSQL("UPDATE document_pages SET position = 0 WHERE pageId = ?", arrayOf(before[1].pageId))
            sql.execSQL("UPDATE document_pages SET position = 1 WHERE pageId = ?", arrayOf(before[0].pageId))
        }

        val after = repository.documentDetails(saved.id).pages
        assertEquals(listOf(before[1].pageId, before[0].pageId), after.map { page -> page.pageId })
        assertArrayEquals(contents[1], openBytes(saved.id, after[0]))
        assertArrayEquals(contents[0], openBytes(saved.id, after[1]))
    }

    @Test
    fun removingAPageDeletesItsRowKeyAndFileAndRenumbersTheRest() = runBlocking {
        val contents = listOf(randomBytes(1), randomBytes(2), randomBytes(3))
        val saved = saveDocument(contents)
        val pages = repository.documentDetails(saved.id).pages
        val middleFile = vault.pageFilesOf(saved.id)[1]

        repository.removePage(saved.id, pages[1].pageId)

        val after = repository.documentDetails(saved.id).pages
        val removedRow = vault.holder.withDatabase { database -> database.documentDao().findPage(pages[1].pageId) }
        assertNull("row and wrapped key gone", removedRow)
        assertFalse("file gone", middleFile.exists())
        assertEquals(listOf(pages[0].pageId, pages[2].pageId), after.map { page -> page.pageId })
        assertEquals(listOf(0, 1), after.map { page -> page.position })
        assertArrayEquals(contents[2], openBytes(saved.id, after[1]))
    }

    @Test
    fun theOnlyPageCannotBeRemoved() = runBlocking {
        val saved = saveDocument(listOf(randomBytes(1)))
        val page = repository.documentDetails(saved.id).pages.single()

        val outcome = runCatching { repository.removePage(saved.id, page.pageId) }

        assertTrue(outcome.exceptionOrNull() is LastPageException)
        assertTrue(vault.pageFilesOf(saved.id).single().exists())
    }

    @Test
    fun deletingADocumentRemovesEveryPageRowAndFile() = runBlocking {
        val saved = saveDocument(listOf(randomBytes(1), randomBytes(2), randomBytes(3)))
        val pageFiles = vault.pageFilesOf(saved.id)

        repository.deleteDocument(saved.id)

        val pageRows = vault.holder.withDatabase { database -> database.documentDao().pagesOf(saved.id) }
        assertTrue(pageRows.isEmpty())
        assertTrue(pageFiles.none { file -> file.exists() })
        assertTrue("nothing left in the vault folder", vault.vaultDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun addedPagesGoToTheEnd() = runBlocking {
        val saved = saveDocument(listOf(randomBytes(1)))
        val newContent = randomBytes(2)

        repository.addPages(saved.id, listOf(prepare(saved.id, newContent)))

        val pages = repository.documentDetails(saved.id).pages
        assertEquals(listOf(0, 1), pages.map { page -> page.position })
        assertArrayEquals(newContent, openBytes(saved.id, pages[1]))
    }

    @Test
    fun pagesPreparedForAnotherDocumentAreRefused() = runBlocking {
        val saved = saveDocument(listOf(randomBytes(1)))
        val foreignPage = prepare(repository.newDocumentId(), randomBytes(2))

        val outcome = runCatching { repository.addPages(saved.id, listOf(foreignPage)) }

        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, repository.documentDetails(saved.id).pages.size)
    }

    @Test
    fun elevenPagesAreRefusedAndNothingIsSaved() = runBlocking {
        val documentId = repository.newDocumentId()
        val pages = List(MAX_PAGES_PER_DOCUMENT + 1) { index -> prepare(documentId, randomBytes(index)) }

        val outcome = runCatching { repository.saveDocument(documentId, pages, "Too many", DocumentType.OTHER) }
        repository.discardPreparedPages(pages)

        assertEquals(PageLimitProblem.TOO_MANY_PAGES, (outcome.exceptionOrNull() as PageLimitException).problem)
        assertTrue(vault.vaultDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun addingPastTenPagesIsRefusedAndNothingChanges() = runBlocking {
        val saved = saveDocument(List(MAX_PAGES_PER_DOCUMENT) { index -> randomBytes(index) })
        val extra = prepare(saved.id, randomBytes(99))

        val outcome = runCatching { repository.addPages(saved.id, listOf(extra)) }

        assertEquals(PageLimitProblem.TOO_MANY_PAGES, (outcome.exceptionOrNull() as PageLimitException).problem)
        assertEquals(MAX_PAGES_PER_DOCUMENT, repository.documentDetails(saved.id).pages.size)
        assertFalse(vault.fileCrypto.encryptedFileFor(extra.pageId).exists())
    }

    @Test
    fun moreThanFiftyMegabytesIsRefused() = runBlocking {
        val documentId = repository.newDocumentId()
        // 20 + 20 + 11 MB: each page is allowed on its own, together they are over 50 MB.
        val sizes = listOf(MAX_FILE_BYTES, MAX_FILE_BYTES, ELEVEN_MEGABYTES)
        val pages = sizes.map { size -> repository.preparePage(documentId, ZeroStream(size), MIME_PDF) }

        val outcome = runCatching { repository.saveDocument(documentId, pages, "Too big", DocumentType.OTHER) }
        repository.discardPreparedPages(pages)

        assertEquals(PageLimitProblem.DOCUMENT_TOO_LARGE, (outcome.exceptionOrNull() as PageLimitException).problem)
        assertTrue(vault.vaultDir.listFiles().isNullOrEmpty())
    }

    /** If writing the rows fails, the files go back to pending: no .bin is ever left without a row. */
    @Test
    fun aFailedSaveLeavesNoSavedFilesAndKeepsThePagesPending() = runBlocking {
        val existing = saveDocument(listOf(randomBytes(1)))
        // Pages prepared for an id that already exists: the database refuses the second document.
        val pages = listOf(prepare(existing.id, randomBytes(2)), prepare(existing.id, randomBytes(3)))

        val outcome = runCatching { repository.saveDocument(existing.id, pages, "Clash", DocumentType.OTHER) }

        assertTrue(outcome.isFailure)
        assertTrue(pages.none { page -> vault.fileCrypto.encryptedFileFor(page.pageId).exists() })
        assertTrue(pages.all { page -> vault.fileCrypto.pendingFileFor(page.pageId).exists() })
        assertEquals(1, repository.documentDetails(existing.id).pages.size)
    }

    @Test
    fun orphanCleanupUsesPageIdsAndKeepsEveryRealPage() = runBlocking {
        val saved = saveDocument(listOf(randomBytes(1), randomBytes(2)))
        val orphan = vault.fileCrypto.encryptedFileFor(ORPHAN_ID).apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val removed = repository.removeOrphanFiles()

        assertEquals(1, removed)
        assertFalse(orphan.exists())
        assertTrue(vault.pageFilesOf(saved.id).all { file -> file.exists() })
    }

    private suspend fun saveDocument(contents: List<ByteArray>, mimeTypes: List<String> = contents.map { MIME_JPEG }): DocumentSummary {
        val documentId = repository.newDocumentId()
        val pages = contents.mapIndexed { index, bytes -> prepare(documentId, bytes, mimeTypes[index]) }
        return repository.saveDocument(documentId, pages, "Sample pages", DocumentType.AADHAAR)
    }

    private suspend fun prepare(documentId: String, bytes: ByteArray, mimeType: String = MIME_JPEG): PreparedPage {
        return repository.preparePage(documentId, ByteArrayInputStream(bytes), mimeType)
    }

    private suspend fun openBytes(documentId: String, page: DocumentPage): ByteArray {
        return when (val opened = repository.openPage(documentId, page.pageId)) {
            is OpenedPage.Image -> opened.bytes.bytes
            is OpenedPage.Pdf -> opened.file.file.readBytes().also { opened.file.delete() }
        }
    }

    private fun randomBytes(seed: Int): ByteArray = Random(seed).nextBytes(SMALL_BYTES)

    /** [size] zero bytes, without holding them in memory. */
    private class ZeroStream(private val size: Long) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            if (position >= size) return -1
            position++
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            buffer.fill(0, offset, offset + count)
            position += count
            return count
        }
    }

    private companion object {
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PDF = "application/pdf"
        const val SMALL_BYTES = 10_000
        const val ELEVEN_MEGABYTES = 11L * 1024 * 1024
        const val ORPHAN_ID = "0a0b0c0d-1111-4222-8333-444455556666"
    }
}

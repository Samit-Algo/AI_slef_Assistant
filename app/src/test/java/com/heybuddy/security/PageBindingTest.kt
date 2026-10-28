package com.heybuddy.security

import com.heybuddy.util.PAGE_FORMAT_CURRENT
import com.heybuddy.util.PAGE_FORMAT_LEGACY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The associated data that binds each page to its document and to itself (Task 1.10p, rule 4). */
class PageBindingTest {

    @Test
    fun newPagesAreBoundToTheirDocumentAndPage() {
        val binding = PageBinding.forNewPage(DOCUMENT_ID, PAGE_ID)

        assertTrue(binding.isCurrentFormat)
        assertEquals("document:$DOCUMENT_ID:page:$PAGE_ID", labelOf(binding))
    }

    @Test
    fun migratedPagesKeepTheOldDocumentLabel() {
        val binding = PageBinding.forStoredPage(DOCUMENT_ID, DOCUMENT_ID, PAGE_FORMAT_LEGACY)

        assertFalse(binding.isCurrentFormat)
        assertEquals("document:$DOCUMENT_ID", labelOf(binding))
    }

    @Test
    fun storedCurrentPagesGetTheSameLabelAsWhenTheyWereNew() {
        val stored = PageBinding.forStoredPage(DOCUMENT_ID, PAGE_ID, PAGE_FORMAT_CURRENT)

        assertEquals(labelOf(PageBinding.forNewPage(DOCUMENT_ID, PAGE_ID)), labelOf(stored))
    }

    @Test
    fun twoPagesOfOneDocumentGetDifferentLabels() {
        val first = PageBinding.forNewPage(DOCUMENT_ID, PAGE_ID)
        val second = PageBinding.forNewPage(DOCUMENT_ID, OTHER_PAGE_ID)

        assertFalse(labelOf(first) == labelOf(second))
    }

    /** The migration only ever writes legacy pages with page id = document id; anything else is refused. */
    @Test
    fun aLegacyPageWithItsOwnPageIdIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            PageBinding.forStoredPage(DOCUMENT_ID, PAGE_ID, PAGE_FORMAT_LEGACY)
        }
    }

    @Test
    fun anUnknownFormatIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            PageBinding.forStoredPage(DOCUMENT_ID, PAGE_ID, UNKNOWN_FORMAT)
        }
    }

    @Test
    fun idsMustBePlainLowercaseUuids() {
        val badIds = listOf("../evil", "", DOCUMENT_ID.uppercase(), "1-1-1-1-1")
        for (badId in badIds) {
            assertThrows("\"$badId\" must be refused", IllegalArgumentException::class.java) {
                PageBinding.forNewPage(badId, PAGE_ID)
            }
            assertThrows("\"$badId\" must be refused", IllegalArgumentException::class.java) {
                PageBinding.forNewPage(DOCUMENT_ID, badId)
            }
        }
    }

    private fun labelOf(binding: PageBinding): String = String(binding.associatedData(), Charsets.UTF_8)

    private companion object {
        const val DOCUMENT_ID = "6f1c2a4e-1b2c-4d3e-8f90-123456789abc"
        const val PAGE_ID = "0a0b0c0d-1111-4222-8333-444455556666"
        const val OTHER_PAGE_ID = "9f8e7d6c-5555-4666-8777-888899990000"
        const val UNKNOWN_FORMAT = 99
    }
}

package com.heybuddy.data

import com.heybuddy.util.MAX_DOCUMENT_BYTES
import com.heybuddy.util.MAX_FILE_BYTES
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Page order and document limits (Task 1.10p). */
class PageRulesTest {

    @Test
    fun removingAMiddlePageMovesOnlyThePagesAfterIt() {
        val remaining = listOf(PagePosition("a", 0), PagePosition("c", 2), PagePosition("d", 3))

        val changes = positionChangesAfterRemoval(remaining)

        assertEquals(listOf(PagePosition("c", 1), PagePosition("d", 2)), changes)
    }

    @Test
    fun removingTheLastPageMovesNothing() {
        val remaining = listOf(PagePosition("a", 0), PagePosition("b", 1))

        assertEquals(emptyList<PagePosition>(), positionChangesAfterRemoval(remaining))
    }

    @Test
    fun removingTheFirstPageMovesEveryOtherPage() {
        val remaining = listOf(PagePosition("b", 1), PagePosition("c", 2))

        val changes = positionChangesAfterRemoval(remaining)

        assertEquals(listOf(PagePosition("b", 0), PagePosition("c", 1)), changes)
    }

    @Test
    fun moveUpAndDownSwapNeighbours() {
        val pages = listOf("front", "back", "extra")

        assertEquals(listOf("back", "front", "extra"), movedUp(pages, 1))
        assertEquals(listOf("front", "extra", "back"), movedDown(pages, 1))
    }

    @Test
    fun movingPastTheEndsChangesNothing() {
        val pages = listOf("front", "back")

        assertEquals(pages, movedUp(pages, 0))
        assertEquals(pages, movedDown(pages, 1))
        assertEquals(pages, movedUp(pages, 5))
        assertEquals(pages, movedDown(pages, -1))
    }

    @Test
    fun tenPagesFitButElevenDoNot() {
        val tenSmallPages = List(MAX_PAGES_PER_DOCUMENT) { SMALL_PAGE }

        assertNull(pageLimitProblem(existingPageCount = 0, existingBytes = 0, newPageSizes = tenSmallPages))
        assertEquals(PageLimitProblem.TOO_MANY_PAGES, pageLimitProblem(0, 0, tenSmallPages + SMALL_PAGE))
        assertEquals(PageLimitProblem.TOO_MANY_PAGES, pageLimitProblem(MAX_PAGES_PER_DOCUMENT, 0, listOf(SMALL_PAGE)))
    }

    @Test
    fun exactlyFiftyMegabytesFitsButOneByteMoreDoesNot() {
        val twoFullPages = listOf(MAX_FILE_BYTES, MAX_FILE_BYTES)
        val restUpToTheLimit = MAX_DOCUMENT_BYTES - 2 * MAX_FILE_BYTES

        assertNull(pageLimitProblem(0, 0, twoFullPages + restUpToTheLimit))
        assertEquals(PageLimitProblem.DOCUMENT_TOO_LARGE, pageLimitProblem(0, 0, twoFullPages + (restUpToTheLimit + 1)))
    }

    @Test
    fun existingPagesCountTowardsTheSizeLimit() {
        assertEquals(
            PageLimitProblem.DOCUMENT_TOO_LARGE,
            pageLimitProblem(existingPageCount = 3, existingBytes = MAX_DOCUMENT_BYTES, newPageSizes = listOf(1L)),
        )
    }

    private companion object {
        const val SMALL_PAGE = 1_000L
    }
}

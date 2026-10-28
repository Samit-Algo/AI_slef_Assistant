package com.heybuddy.data

import com.heybuddy.util.MAX_DOCUMENT_BYTES
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT

/**
 * A page and its place in its document.
 *
 * @property pageId The page's id.
 * @property position Its place, starting at 0.
 */
data class PagePosition(val pageId: String, val position: Int)

/**
 * After a page was removed, finds which of the remaining pages must move so the places are
 * 0, 1, 2… again with no gap.
 *
 * @param remainingInOrder The remaining pages, sorted by their current place.
 * @return Only the pages whose place changes, with their new place, in ascending order.
 */
fun positionChangesAfterRemoval(remainingInOrder: List<PagePosition>): List<PagePosition> {
    val changes = mutableListOf<PagePosition>()
    for ((index, page) in remainingInOrder.withIndex()) {
        if (page.position != index) {
            changes.add(PagePosition(page.pageId, index))
        }
    }
    return changes
}

/**
 * The list with one item moved one place towards the start (the Add screen's "move up").
 *
 * @param items The items in order.
 * @param index The item to move.
 * @return A new list; unchanged if the item is already first or the index does not exist.
 */
fun <T> movedUp(items: List<T>, index: Int): List<T> {
    if (index <= 0 || index >= items.size) return items
    return swapped(items, index, index - 1)
}

/**
 * The list with one item moved one place towards the end (the Add screen's "move down").
 *
 * @param items The items in order.
 * @param index The item to move.
 * @return A new list; unchanged if the item is already last or the index does not exist.
 */
fun <T> movedDown(items: List<T>, index: Int): List<T> {
    if (index < 0 || index >= items.size - 1) return items
    return swapped(items, index, index + 1)
}

private fun <T> swapped(items: List<T>, first: Int, second: Int): List<T> {
    val copy = items.toMutableList()
    val firstItem = copy[first]
    copy[first] = copy[second]
    copy[second] = firstItem
    return copy
}

/** Which document limit (owner decision, Task 1.10p) a change would break. */
enum class PageLimitProblem {
    /** More than MAX_PAGES_PER_DOCUMENT pages. */
    TOO_MANY_PAGES,

    /** All pages together larger than MAX_DOCUMENT_BYTES. */
    DOCUMENT_TOO_LARGE,
}

/**
 * Checks whether a document may have these pages added.
 *
 * @param existingPageCount Pages the document already has (0 for a new document).
 * @param existingBytes Total size of those pages.
 * @param newPageSizes Sizes of the pages to add.
 * @return The broken limit, or null if the pages fit.
 */
fun pageLimitProblem(existingPageCount: Int, existingBytes: Long, newPageSizes: List<Long>): PageLimitProblem? {
    val totalPages = existingPageCount + newPageSizes.size
    if (totalPages > MAX_PAGES_PER_DOCUMENT) return PageLimitProblem.TOO_MANY_PAGES
    val totalBytes = existingBytes + newPageSizes.sum()
    if (totalBytes > MAX_DOCUMENT_BYTES) return PageLimitProblem.DOCUMENT_TOO_LARGE
    return null
}

/**
 * Adding these pages would break a document limit; nothing was saved.
 *
 * @property problem Which limit.
 */
class PageLimitException(val problem: PageLimitProblem) : IllegalStateException("Document page limit reached")

/** The page to remove is the document's only page; delete the whole document instead. */
class LastPageException : IllegalStateException("A document must keep at least one page")

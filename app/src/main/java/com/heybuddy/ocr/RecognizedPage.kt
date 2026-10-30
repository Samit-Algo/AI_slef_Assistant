package com.heybuddy.ocr

/**
 * Where a line sits in the picture that was read, in pixels.
 *
 * @property left Left edge.
 * @property top Top edge.
 * @property right Right edge.
 * @property bottom Bottom edge.
 */
data class LineBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    /** Height of the line. */
    val height: Int
        get() = bottom - top

    /** Vertical middle of the line. */
    val middle: Int
        get() = (top + bottom) / 2
}

/**
 * One line of text found on a page. Lives only in memory (spec 7.3-2); never saved or logged.
 *
 * @property text What was read.
 * @property box Where it was found, or null if the engine did not say.
 * @property confidence How sure the engine was, from 0 to 1, or null if it did not say.
 */
data class RecognizedLine(val text: String, val box: LineBox?, val confidence: Float? = null)

/**
 * Everything read from one page, in reading order. Lives only in memory (spec 7.3-2): drop every
 * reference to it as soon as it is no longer needed. (Kotlin strings cannot be overwritten with
 * zeros, so "wiping" text means dropping it; the same best effort as for keys.)
 *
 * @property lines The lines, top to bottom, left to right.
 */
class RecognizedPage(val lines: List<RecognizedLine>) {

    /** All lines joined with line breaks. */
    val text: String
        get() = lines.joinToString(separator = "\n") { line -> line.text }
}

/**
 * Puts lines in reading order: top to bottom, and left to right within one row. Two lines are in
 * the same row when their middles are closer than half the smaller line's height (so a slightly
 * tilted photo still keeps "Name: TESTA" together). Lines without a position keep their order at the end.
 *
 * @param lines Lines in any order.
 * @return The same lines in reading order.
 */
fun inReadingOrder(lines: List<RecognizedLine>): List<RecognizedLine> {
    val placed = lines.filter { line -> line.box != null }.sortedBy { line -> line.box!!.middle }
    val unplaced = lines.filter { line -> line.box == null }
    val rows = mutableListOf<MutableList<RecognizedLine>>()
    for (line in placed) {
        val row = rows.lastOrNull()
        if (row != null && isSameRow(row.last(), line)) row.add(line) else rows.add(mutableListOf(line))
    }
    val ordered = rows.flatMap { row -> row.sortedBy { line -> line.box!!.left } }
    return ordered + unplaced
}

private fun isSameRow(previous: RecognizedLine, next: RecognizedLine): Boolean {
    val previousBox = previous.box ?: return false
    val nextBox = next.box ?: return false
    val smallerHeight = minOf(previousBox.height, nextBox.height)
    return nextBox.middle - previousBox.middle < smallerHeight / 2
}

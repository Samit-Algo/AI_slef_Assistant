package com.heybuddy.ocr.measure

import com.heybuddy.util.TamilText

/**
 * The maths for the Task 2.2 accuracy measurement. Test code only (never in the app).
 *
 * Character accuracy of one expected line = 1 − (edits needed to turn the best-matching read line
 * into the expected line ÷ the expected line's length), never below 0. A page's accuracy is the
 * average over its expected lines, weighted by length. Extra lines (for example garbage read from
 * Tamil text) do not lower it; they are counted separately ([unexplainedLines]).
 */
object TextAccuracy {

    /** Below this similarity, a read line is not counted as a reading of any expected line. */
    const val EXPLAINED_SIMILARITY = 0.5

    /**
     * Unicode-normalises and collapses whitespace, so only real reading differences count.
     *
     * @param text Any text.
     * @return The cleaned text.
     */
    fun clean(text: String): String {
        // The app's shared cleanup (NFC, invisible joiners, Tamil vowel-sign order): the app sees the
        // read text only after it, so it is measured the same way.
        val cleaned = TamilText.clean(text)
        return cleaned.trim().replace(WHITESPACE, " ")
    }

    /**
     * The fewest single-character insertions, deletions or replacements that turn [from] into [to]
     * (Levenshtein distance), counted in Unicode code points.
     *
     * @param from One text.
     * @param to The other text.
     * @return The number of edits.
     */
    fun editDistance(from: String, to: String): Int {
        val source = from.codePoints().toArray()
        val target = to.codePoints().toArray()
        var previous = IntArray(target.size + 1) { index -> index }
        for (i in 1..source.size) {
            val current = IntArray(target.size + 1)
            current[0] = i
            for (j in 1..target.size) {
                val replaceCost = if (source[i - 1] == target[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + replaceCost)
            }
            previous = current
        }
        return previous[target.size]
    }

    /**
     * How close two texts are: 1 = identical, 0 = nothing in common.
     *
     * @param first One text.
     * @param second The other.
     * @return A value from 0 to 1.
     */
    fun similarity(first: String, second: String): Double {
        val a = clean(first)
        val b = clean(second)
        val longer = maxOf(a.codePointCount(0, a.length), b.codePointCount(0, b.length))
        if (longer == 0) return 1.0
        return 1.0 - editDistance(a, b).toDouble() / longer
    }

    /**
     * Character accuracy of one expected line against everything read on the page.
     *
     * @param expected The line as printed.
     * @param readLines What was read, in reading order.
     * @return A value from 0 to 1.
     */
    fun lineAccuracy(expected: String, readLines: List<String>): Double {
        val target = clean(expected)
        val length = target.codePointCount(0, target.length)
        if (length == 0) return 1.0
        val bestDistance = candidates(readLines).minOfOrNull { candidate -> editDistance(clean(candidate), target) } ?: length
        return (1.0 - bestDistance.toDouble() / length).coerceAtLeast(0.0)
    }

    /**
     * Character accuracy of a whole page: the length-weighted average of [lineAccuracy].
     *
     * @param expectedLines The answer key's lines.
     * @param readLines What was read.
     * @return A value from 0 to 1.
     */
    fun pageAccuracy(expectedLines: List<String>, readLines: List<String>): Double {
        var weighted = 0.0
        var totalLength = 0
        for (expected in expectedLines) {
            val length = clean(expected).length
            weighted += lineAccuracy(expected, readLines) * length
            totalLength += length
        }
        return if (totalLength == 0) 1.0 else weighted / totalLength
    }

    /**
     * Whether a field's exact value appears in what was read. Numbers are compared without spaces
     * (OCR often drops or adds the gaps in "9999 4567 1231").
     *
     * @param value The field's true value.
     * @param readText Everything read on the page.
     * @param ignoreSpaces True for numbers and codes.
     * @return true if found exactly.
     */
    fun fieldFound(value: String, readText: String, ignoreSpaces: Boolean): Boolean {
        val wanted = clean(value)
        val text = clean(readText)
        if (!ignoreSpaces) return text.contains(wanted)
        return text.replace(" ", "").contains(wanted.replace(" ", ""))
    }

    /**
     * Read lines that match none of the expected lines: garbage, typically from the watermark or
     * from Tamil text that ML Kit cannot read.
     *
     * @param readLines What was read.
     * @param expectedLines Every line that should have been read (not the Tamil ones).
     * @param ignored Text that is known to be there and is not counted (the "SAMPLE" watermark).
     * @return The unexplained lines.
     */
    fun unexplainedLines(readLines: List<String>, expectedLines: List<String>, ignored: List<String>): List<String> {
        val known = expectedLines + ignored
        return readLines.filter { line ->
            val best = known.maxOfOrNull { expected -> partialSimilarity(line, expected) } ?: 0.0
            best < EXPLAINED_SIMILARITY
        }
    }

    /**
     * Whether a garbage line could be mistaken for a field by simple rules: a 12-digit number, a
     * PAN-shaped code, a date, or an ALL-CAPS two-to-four-word name.
     *
     * @param line One read line.
     * @return true if it looks like a field.
     */
    fun looksLikeField(line: String): Boolean {
        val text = clean(line)
        return TWELVE_DIGITS.containsMatchIn(text) || PAN_SHAPE.containsMatchIn(text) ||
            DATE_SHAPE.containsMatchIn(text) || NAME_SHAPE.matches(text)
    }

    /**
     * @param text Any text.
     * @return true if it has at least one Devanagari letter.
     */
    fun containsDevanagari(text: String): Boolean = text.any { char -> char in DEVANAGARI }

    /**
     * @param text Any text.
     * @return true if it has at least one Tamil letter.
     */
    fun containsTamil(text: String): Boolean = text.any { char -> char in TAMIL }

    /** Each read line, and each pair of neighbouring lines joined (OCR sometimes splits one printed line). */
    private fun candidates(readLines: List<String>): List<String> {
        val joined = readLines.zipWithNext { first, second -> "$first $second" }
        return readLines + joined
    }

    /** Similarity of a read line to the best-matching part of an expected line of the same length or longer. */
    private fun partialSimilarity(readLine: String, expected: String): Double {
        val whole = similarity(readLine, expected)
        val inside = clean(expected).contains(clean(readLine)) && clean(readLine).length >= MIN_PART_LENGTH
        return if (inside) 1.0 else whole
    }

    private const val MIN_PART_LENGTH = 3
    private val WHITESPACE = Regex("\\s+")
    private val TWELVE_DIGITS = Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b")
    private val PAN_SHAPE = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")
    private val DATE_SHAPE = Regex("\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4}\\b")
    private val NAME_SHAPE = Regex("[A-Z]{2,}( [A-Z]{2,}){1,3}")
    private val DEVANAGARI = 'ऀ'..'ॿ'
    private val TAMIL = '஀'..'௿'
}

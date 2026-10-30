package com.heybuddy.ocr

import com.heybuddy.util.MERGE_MIN_TAMIL_SHARE
import com.heybuddy.util.MERGE_ML_KIT_GARBAGE_BELOW
import com.heybuddy.util.MERGE_ML_KIT_REAL_FROM
import com.heybuddy.util.MERGE_TAMIL_SURE_FROM
import kotlin.math.max
import kotlin.math.min

/**
 * How ML Kit's lines and Tesseract's lines become one list (spec 7.4a, owner rule of Task 2.3).
 *
 * ML Kit cannot read Tamil and turns it into look-alike "garbage"; Tesseract cannot read English or
 * Hindi and turns them into Tamil-looking garbage. So, in each spot where lines of both engines
 * overlap:
 * - ML Kit's line is REAL and Tesseract's is not surely Tamil → keep ML Kit's line only;
 * - Tesseract's line is SURELY TAMIL and ML Kit's is GARBAGE → keep Tesseract's line only;
 * - anything else (unsure) → keep BOTH. A line that might be real is never dropped.
 * A line with no line of the other engine on top of it is always kept.
 */
object LineMerge {

    /** What ML Kit's line looks like. */
    enum class MainLineKind {
        /** Only Latin, Devanagari, digits and punctuation, and ML Kit was sure. */
        REAL,

        /** Letters of another script (ML Kit's look-alikes for Tamil, often Bengali), or ML Kit was very unsure. */
        GARBAGE,

        /** Neither. */
        UNSURE,
    }

    /**
     * @param mainLines ML Kit's lines.
     * @param tamilLines Tesseract's lines (already cleaned with TamilText).
     * @return The kept lines, not yet in reading order.
     */
    fun merge(mainLines: List<RecognizedLine>, tamilLines: List<RecognizedLine>): List<RecognizedLine> {
        val droppedMain = mutableSetOf<Int>()
        val keptTamil = mutableListOf<RecognizedLine>()
        for (tamilLine in tamilLines) {
            val underneath = mainLines.indices.filter { index -> overlaps(mainLines[index], tamilLine) }
            val kinds = underneath.map { index -> kindOf(mainLines[index]) }
            val tamilIsSure = isSurelyTamil(tamilLine)
            val mainIsAllReal = underneath.isNotEmpty() && kinds.all { kind -> kind == MainLineKind.REAL }
            val mainIsAllGarbage = underneath.isNotEmpty() && kinds.all { kind -> kind == MainLineKind.GARBAGE }
            when {
                mainIsAllReal && !tamilIsSure -> Unit // Tesseract's garbage over real English or Hindi.
                tamilIsSure && mainIsAllGarbage -> {
                    droppedMain.addAll(underneath)
                    keptTamil.add(tamilLine)
                }
                else -> keptTamil.add(tamilLine)
            }
        }
        val keptMain = mainLines.filterIndexed { index, _ -> index !in droppedMain }
        return keptMain + keptTamil
    }

    /**
     * @param line One of ML Kit's lines.
     * @return Whether it is real English/Hindi, garbage, or unsure.
     */
    fun kindOf(line: RecognizedLine): MainLineKind {
        val confidence = line.confidence
        val hasForeignLetters = line.text.any { character -> Character.isLetter(character) && !isLatin(character) && !isDevanagari(character) }
        if (hasForeignLetters) return MainLineKind.GARBAGE
        if (confidence != null && confidence < MERGE_ML_KIT_GARBAGE_BELOW) return MainLineKind.GARBAGE
        if (confidence == null || confidence >= MERGE_ML_KIT_REAL_FROM) return MainLineKind.REAL
        return MainLineKind.UNSURE
    }

    /**
     * @param line One of Tesseract's lines.
     * @return True if most of its letters are Tamil and Tesseract was sure of it.
     */
    fun isSurelyTamil(line: RecognizedLine): Boolean {
        val letters = line.text.filter { character -> Character.isLetter(character) }
        if (letters.isEmpty()) return false
        val tamilShare = letters.count { character -> isTamil(character) }.toFloat() / letters.length
        val confidence = line.confidence ?: return false
        return tamilShare >= MERGE_MIN_TAMIL_SHARE && confidence >= MERGE_TAMIL_SURE_FROM
    }

    /**
     * Two lines are in the same spot when they share more than half of the lower one's height and
     * overlap at all from left to right.
     */
    private fun overlaps(first: RecognizedLine, second: RecognizedLine): Boolean {
        val a = first.box ?: return false
        val b = second.box ?: return false
        val sharedHeight = min(a.bottom, b.bottom) - max(a.top, b.top)
        val sharedWidth = min(a.right, b.right) - max(a.left, b.left)
        return sharedWidth > 0 && sharedHeight * 2 > min(a.height, b.height)
    }

    private fun isLatin(character: Char): Boolean = character in 'A'..'Z' || character in 'a'..'z'

    private fun isDevanagari(character: Char): Boolean = character in 'ऀ'..'ॿ'

    private fun isTamil(character: Char): Boolean = character in '஀'..'௿'
}

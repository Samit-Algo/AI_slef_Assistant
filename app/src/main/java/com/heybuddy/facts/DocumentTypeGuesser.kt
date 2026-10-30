package com.heybuddy.facts

import com.heybuddy.data.DocumentType
import com.heybuddy.util.TYPE_GUESS_DECISIVE_POINTS
import com.heybuddy.util.TYPE_GUESS_MIN_LEAD
import com.heybuddy.util.TYPE_GUESS_MIN_SCORE
import com.heybuddy.util.TYPE_GUESS_SUPPORTING_POINTS
import com.heybuddy.util.TamilText

/**
 * Suggests a document type from the text read on a page (spec 7.6). Pure rules: each type collects
 * points for its key words (English, Hindi, Tamil) and for a valid number of its kind. Only a clear
 * winner is suggested; otherwise there is no suggestion and the user picks. The user can always
 * change the suggestion.
 *
 * Tamil Nadu certificate types come in Task 2.11; until then Tamil "சான்றிதழ்" counts as Certificate.
 */
object DocumentTypeGuesser {

    /**
     * One key word or phrase.
     *
     * @property text The phrase, lower case.
     * @property type The type it points to.
     * @property points How strongly.
     * @property wholeWord False when the phrase may run straight into the next word, as "P<IND" does
     *   in a passport's machine-readable line ("P<INDSAMPLE<<…").
     */
    private class Clue(val text: String, val type: DocumentType, val points: Int, val wholeWord: Boolean = true)

    private val CLUES = listOf(
        // Aadhaar
        Clue("aadhaar", DocumentType.AADHAAR, TYPE_GUESS_DECISIVE_POINTS),
        Clue("आधार", DocumentType.AADHAAR, TYPE_GUESS_DECISIVE_POINTS),
        Clue("ஆதார்", DocumentType.AADHAAR, TYPE_GUESS_DECISIVE_POINTS),
        Clue("unique identification authority", DocumentType.AADHAAR, TYPE_GUESS_DECISIVE_POINTS),
        Clue("vid", DocumentType.AADHAAR, TYPE_GUESS_SUPPORTING_POINTS),
        // PAN
        Clue("permanent account number", DocumentType.PAN, TYPE_GUESS_DECISIVE_POINTS),
        Clue("income tax department", DocumentType.PAN, TYPE_GUESS_DECISIVE_POINTS),
        Clue("आयकर विभाग", DocumentType.PAN, TYPE_GUESS_DECISIVE_POINTS),
        Clue("स्थायी लेखा संख्या", DocumentType.PAN, TYPE_GUESS_DECISIVE_POINTS),
        // Driving licence
        Clue("driving licence", DocumentType.DRIVING_LICENCE, TYPE_GUESS_DECISIVE_POINTS),
        Clue("driving license", DocumentType.DRIVING_LICENCE, TYPE_GUESS_DECISIVE_POINTS),
        Clue("ஓட்டுநர் உரிமம்", DocumentType.DRIVING_LICENCE, TYPE_GUESS_DECISIVE_POINTS),
        Clue("dl no", DocumentType.DRIVING_LICENCE, TYPE_GUESS_SUPPORTING_POINTS),
        Clue("transport department", DocumentType.DRIVING_LICENCE, TYPE_GUESS_SUPPORTING_POINTS),
        Clue("union of india", DocumentType.DRIVING_LICENCE, TYPE_GUESS_SUPPORTING_POINTS),
        // Passport
        Clue("passport", DocumentType.PASSPORT, TYPE_GUESS_DECISIVE_POINTS),
        Clue("पासपोर्ट", DocumentType.PASSPORT, TYPE_GUESS_DECISIVE_POINTS),
        Clue("கடவுச்சீட்டு", DocumentType.PASSPORT, TYPE_GUESS_DECISIVE_POINTS),
        Clue("p<ind", DocumentType.PASSPORT, TYPE_GUESS_DECISIVE_POINTS, wholeWord = false),
        Clue("republic of india", DocumentType.PASSPORT, TYPE_GUESS_SUPPORTING_POINTS),
        // Certificate (Tamil Nadu certificate types follow in Task 2.11)
        Clue("certificate", DocumentType.CERTIFICATE, TYPE_GUESS_DECISIVE_POINTS),
        Clue("प्रमाण पत्र", DocumentType.CERTIFICATE, TYPE_GUESS_DECISIVE_POINTS),
        Clue("சான்றிதழ்", DocumentType.CERTIFICATE, TYPE_GUESS_DECISIVE_POINTS),
        Clue("தமிழ்நாடு அரசு", DocumentType.CERTIFICATE, TYPE_GUESS_SUPPORTING_POINTS),
        // On both Aadhaar and PAN, so it only supports them, never decides.
        Clue("government of india", DocumentType.AADHAAR, TYPE_GUESS_SUPPORTING_POINTS),
        Clue("government of india", DocumentType.PAN, TYPE_GUESS_SUPPORTING_POINTS),
        Clue("govt of india", DocumentType.PAN, TYPE_GUESS_SUPPORTING_POINTS),
        Clue("भारत सरकार", DocumentType.AADHAAR, TYPE_GUESS_SUPPORTING_POINTS),
        Clue("भारत सरकार", DocumentType.PAN, TYPE_GUESS_SUPPORTING_POINTS),
        Clue("இந்திய அரசு", DocumentType.AADHAAR, TYPE_GUESS_SUPPORTING_POINTS),
    )

    /**
     * @param text All text read on the page (any scripts).
     * @return The suggested type, or null for no suggestion (the user picks).
     */
    fun guess(text: String): DocumentType? {
        val ranked = scores(text).entries.sortedByDescending { entry -> entry.value }
        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.value ?: 0
        val clearWinner = best.value >= TYPE_GUESS_MIN_SCORE && best.value - secondScore >= TYPE_GUESS_MIN_LEAD
        return if (clearWinner) best.key else null
    }

    /**
     * Points per type (only types with at least one point).
     *
     * @param text All text read on the page.
     * @return The points.
     */
    fun scores(text: String): Map<DocumentType, Int> {
        val prepared = prepare(text)
        val points = mutableMapOf<DocumentType, Int>()
        for (clue in CLUES) {
            if (contains(prepared, clue)) points.merge(clue.type, clue.points, Int::plus)
        }
        // A valid number is the strongest sign: the check digit or the exact shape cannot happen by chance.
        if (IdNumbers.aadhaarNumbers(text).isNotEmpty()) points.merge(DocumentType.AADHAAR, TYPE_GUESS_DECISIVE_POINTS, Int::plus)
        if (IdNumbers.panNumbers(text).isNotEmpty()) points.merge(DocumentType.PAN, TYPE_GUESS_DECISIVE_POINTS, Int::plus)
        return points
    }

    /** Lower case, the shared Tamil cleanup, "Govt." as "govt", and single spaces. */
    private fun prepare(text: String): String {
        val cleaned = TamilText.clean(text).lowercase()
        val withoutDots = cleaned.replace(".", " ")
        return withoutDots.replace(WHITESPACE, " ")
    }

    /** Latin words must stand alone ("vid" is not part of "provided"); Indian scripts are matched as written. */
    private fun contains(text: String, clue: Clue): Boolean {
        val phrase = clue.text
        val isLatin = phrase.all { character -> character.code < LATIN_LIMIT }
        if (!isLatin) return text.contains(phrase)
        val end = if (clue.wholeWord) "(?![a-z0-9])" else ""
        val pattern = Regex("(?<![a-z0-9])" + Regex.escape(phrase) + end)
        return pattern.containsMatchIn(text)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val LATIN_LIMIT = 128
}

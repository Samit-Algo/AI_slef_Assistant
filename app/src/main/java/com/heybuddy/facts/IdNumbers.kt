package com.heybuddy.facts

/**
 * Finds identity numbers in read text (spec 7.7). Shared by the document type guess (Task 2.5) and
 * the fact rules (Task 2.6). Strict on purpose: when unsure, nothing is found.
 */
object IdNumbers {

    /**
     * Turns Devanagari, Bengali and Tamil digits into 0-9. The reader sometimes writes Indian
     * look-alike digits ("199০"), and a printed Tamil number may use Tamil digits (௦-௯).
     *
     * @param text Any text.
     * @return The same text with only ASCII digits.
     */
    fun withAsciiDigits(text: String): String {
        val result = StringBuilder(text.length)
        for (character in text) {
            result.append(asciiDigitFor(character) ?: character)
        }
        return result.toString()
    }

    /**
     * Aadhaar numbers: exactly 12 digits (spaces or hyphens between them are fine), not starting
     * with 0 or 1, with a correct Verhoeff check digit. A digit run of any other length (a 16-digit
     * VID, a 14-digit enrolment number, or 12 digits inside a longer run) is never one; neither is a
     * masked number ("XXXX XXXX 1231").
     *
     * @param text Read text.
     * @return The numbers found, digits only.
     */
    fun aadhaarNumbers(text: String): List<String> {
        val runs = digitRuns(withAsciiDigits(text))
        return runs.filter { digits -> isAadhaarNumber(digits) }
    }

    /**
     * PAN numbers: 5 letters, 4 digits, 1 letter, as a whole word, with a valid 4th letter (the
     * holder type). No OCR mix-ups are fixed here (that is Task 2.6).
     *
     * @param text Read text.
     * @return The PANs found.
     */
    fun panNumbers(text: String): List<String> {
        val upper = text.uppercase()
        return PAN_SHAPE.findAll(upper).map { match -> match.value }.filter { pan -> pan[HOLDER_TYPE_INDEX] in PAN_HOLDER_TYPES }.toList()
    }

    /**
     * Every run of digits, joined across single spaces or hyphens ("9999 4567 1231" → one run), so
     * a number is always seen whole and never as part of a longer one.
     *
     * @param text Text with ASCII digits.
     * @return Each run, digits only.
     */
    fun digitRuns(text: String): List<String> {
        return DIGIT_RUN.findAll(text).map { match -> match.value.filter { character -> character in '0'..'9' } }.toList()
    }

    private fun isAadhaarNumber(digits: String): Boolean {
        if (digits.length != AADHAAR_LENGTH) return false
        if (digits[0] == '0' || digits[0] == '1') return false
        return Verhoeff.isValid(digits)
    }

    private fun asciiDigitFor(character: Char): Char? {
        for (zero in INDIAN_ZEROS) {
            if (character in zero..(zero + LAST_DIGIT)) return '0' + (character - zero)
        }
        return null
    }

    private const val AADHAAR_LENGTH = 12
    private const val HOLDER_TYPE_INDEX = 3
    private const val LAST_DIGIT = 9

    /** Devanagari ०, Bengali ০ and Tamil ௦: each script's digits follow its zero. */
    private val INDIAN_ZEROS = charArrayOf('०', '০', '௦')

    /** A digit, then digits with at most one space or hyphen between them, not touching other digits. */
    private val DIGIT_RUN = Regex("(?<![0-9])[0-9](?:[ -]?[0-9])*(?![0-9])")

    private val PAN_SHAPE = Regex("(?<![A-Z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Z0-9])")

    /** The 4th letter of a PAN says who holds it: P person, C company, H family business, F firm, A, B, G, J, L, T. */
    private const val PAN_HOLDER_TYPES = "ABCFGHJLPT"
}

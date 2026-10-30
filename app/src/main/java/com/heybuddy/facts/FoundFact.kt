package com.heybuddy.facts

import com.heybuddy.util.TamilText

/** What a found fact is (Task 2.6 numbers and dates; names and addresses follow in Task 2.7). */
enum class FactKind {
    PAN,
    AADHAAR_NUMBER,
    DRIVING_LICENCE_NUMBER,

    /** Day-first date (ISO "YYYY-MM-DD"), or only a year ("YYYY") for "Year of Birth". */
    DATE_OF_BIRTH,

    /** Any other date on the page, such as an issue or validity date (ISO "YYYY-MM-DD"). */
    OTHER_DATE,
}

/** How sure the rules are. Nothing is ever saved without the user confirming it (spec 7.9). */
enum class FactStatus {
    /** Read exactly as printed and every check passed. */
    READ_CLEANLY,

    /** Found, but a reading mix-up was fixed (for example O→0): the user should look closely. */
    PLEASE_CHECK,

    /** Something of this kind is there but could not be read reliably: the user types it. */
    PLEASE_TYPE,
}

/**
 * One fact found on a page. In memory only (spec 7.3); saving comes in Task 2.8, after the user confirms.
 *
 * @property kind What it is.
 * @property value The value in a fixed form (PAN upper case, Aadhaar 12 digits, dates ISO). Empty
 *   for [FactStatus.PLEASE_TYPE] when nothing usable could be read.
 * @property status How sure the rules are.
 */
data class FoundFact(val kind: FactKind, val value: String, val status: FactStatus)

/** The text the fact rules work on: the shared Tamil cleanup, then 0-9 for every Indian digit. */
internal object FactText {

    /**
     * @param text Read text.
     * @return Cleaned text, lines kept.
     */
    fun prepare(text: String): String = IdNumbers.withAsciiDigits(TamilText.clean(text))
}

/**
 * OCR mix-ups between look-alike letters and digits (spec 7.7). They are fixed ONLY by position:
 * where the format needs a digit, a look-alike letter becomes that digit, and the other way round.
 */
internal object LookAlikes {

    private val LETTER_TO_DIGIT = mapOf('O' to '0', 'I' to '1', 'S' to '5', 'B' to '8')
    private val DIGIT_TO_LETTER = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B')

    /**
     * @param character A character in a digit position.
     * @return The digit it stands for, or null if it cannot be one.
     */
    fun asDigit(character: Char): Char? = if (character in '0'..'9') character else LETTER_TO_DIGIT[character]

    /**
     * @param character A character in a letter position.
     * @return The letter it stands for, or null if it cannot be one.
     */
    fun asLetter(character: Char): Char? = if (character in 'A'..'Z') character else DIGIT_TO_LETTER[character]
}

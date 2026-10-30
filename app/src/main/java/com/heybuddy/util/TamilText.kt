package com.heybuddy.util

import java.text.Normalizer

/**
 * The ONE place where Tamil text is cleaned (owner rule, Task 2.3). Everything that handles Tamil
 * uses it: reading results, typed fields and fact matching, so the same name always compares equal.
 */
object TamilText {

    /**
     * Cleans Tamil text without changing what it says:
     * 1. removes invisible joiners (Tesseract writes one after every pulli "்"; it looks the same);
     * 2. puts a vowel sign that was written BEFORE its letter back after it ("தந்‌ைத" → "தந்தை"),
     *    because Tesseract sometimes gives the visual order of ெ, ே and ை instead of the typed one;
     * 3. applies Unicode NFC, so letters typed in two different ways become identical.
     *
     * Text in other scripts passes through unchanged (apart from NFC).
     *
     * @param text Any text.
     * @return The cleaned text.
     */
    fun clean(text: String): String {
        val withoutJoiners = text.replace(INVISIBLE_JOINERS, "")
        // Split two-part vowel signs (ொ = ெ + ா) so step 2 sees the part that goes on the left.
        val decomposed = Normalizer.normalize(withoutJoiners, Normalizer.Form.NFD)
        val reordered = withVowelSignsAfterTheirLetters(decomposed)
        return Normalizer.normalize(reordered, Normalizer.Form.NFC)
    }

    /**
     * A Tamil vowel sign always follows its consonant. A left-side sign (ெ ே ை) that does NOT follow a
     * consonant but stands just before one belongs to that consonant, so the two swap places. A
     * consonant with a pulli cannot carry a vowel, so that case is left alone.
     */
    private fun withVowelSignsAfterTheirLetters(text: String): String {
        val characters = text.toCharArray()
        var index = 0
        while (index < characters.size - 1) {
            val sign = characters[index]
            val previous = if (index > 0) characters[index - 1] else null
            val next = characters[index + 1]
            val afterNext = if (index + 2 < characters.size) characters[index + 2] else null
            val misplaced = sign in LEFT_VOWEL_SIGNS && !isConsonant(previous) && isConsonant(next) && afterNext != PULLI
            if (misplaced) {
                characters[index] = next
                characters[index + 1] = sign
                index += 2
            } else {
                index++
            }
        }
        return String(characters)
    }

    private fun isConsonant(character: Char?): Boolean = character != null && character in CONSONANTS

    private val INVISIBLE_JOINERS = Regex("[\\u200C\\u200D]")

    /** க to ஹ (the Tamil consonant block, including the Grantha letters ஜ ஷ ஸ ஹ). */
    private val CONSONANTS = 'க'..'ஹ'

    /** ெ, ே and ை: the vowel signs drawn on the LEFT of their consonant. */
    private val LEFT_VOWEL_SIGNS = setOf('ெ', 'ே', 'ை')

    private const val PULLI = '்'
}

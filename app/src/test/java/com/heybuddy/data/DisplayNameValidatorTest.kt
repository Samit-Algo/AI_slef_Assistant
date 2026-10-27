package com.heybuddy.data

import com.heybuddy.util.MAX_DISPLAY_NAME_CHARACTERS
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks every document name rule (Task 1.6, point 6). */
class DisplayNameValidatorTest {

    @Test
    fun trimsSpacesAtBothEnds() {
        val result = DisplayNameValidator.validate("   My Aadhaar  ")

        assertEquals(DisplayNameResult.Valid("My Aadhaar"), result)
    }

    @Test
    fun emptyOrOnlySpacesIsRejected() {
        assertEquals(invalid(DisplayNameProblem.EMPTY), DisplayNameValidator.validate(""))
        assertEquals(invalid(DisplayNameProblem.EMPTY), DisplayNameValidator.validate("    "))
    }

    @Test
    fun exactlyTheMaximumIsAllowedAndOneMoreIsNot() {
        val longest = "a".repeat(MAX_DISPLAY_NAME_CHARACTERS)
        val tooLong = "a".repeat(MAX_DISPLAY_NAME_CHARACTERS + 1)

        assertEquals(DisplayNameResult.Valid(longest), DisplayNameValidator.validate(longest))
        assertEquals(invalid(DisplayNameProblem.TOO_LONG), DisplayNameValidator.validate(tooLong))
    }

    @Test
    fun emojiCountsAsOneCharacter() {
        // Each emoji is two UTF-16 units; 60 of them is still 60 characters.
        val emojiName = "📄".repeat(MAX_DISPLAY_NAME_CHARACTERS)

        assertEquals(DisplayNameResult.Valid(emojiName), DisplayNameValidator.validate(emojiName))
    }

    @Test
    fun lineBreaksAndTabsAreRejected() {
        val newline = DisplayNameValidator.validate("My\nAadhaar")
        val tab = DisplayNameValidator.validate("My\tAadhaar")

        assertEquals(invalid(DisplayNameProblem.CONTROL_CHARACTER), newline)
        assertEquals(invalid(DisplayNameProblem.CONTROL_CHARACTER), tab)
    }

    @Test
    fun textDirectionTrickIsRejected() {
        // U+202E (right-to-left override) can make "pdf.exe" look like "exe.pdf".
        val result = DisplayNameValidator.validate("Invoice‮fdp.exe")

        assertEquals(invalid(DisplayNameProblem.INVISIBLE_FORMATTING), result)
    }

    @Test
    fun normalNamesWithPunctuationAndOtherScriptsAreAllowed() {
        val names = listOf("PAN card (old)", "Aadhaar - front", "आधार कार्ड", "Photo #2, 2024")
        for (name in names) {
            assertEquals(name, DisplayNameResult.Valid(name), DisplayNameValidator.validate(name))
        }
    }

    private fun invalid(problem: DisplayNameProblem): DisplayNameResult = DisplayNameResult.Invalid(problem)
}

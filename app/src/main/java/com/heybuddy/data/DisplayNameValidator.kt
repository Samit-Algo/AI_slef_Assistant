package com.heybuddy.data

import androidx.annotation.StringRes
import com.heybuddy.R
import com.heybuddy.util.MAX_DISPLAY_NAME_CHARACTERS

/**
 * Why a document name was rejected. Each reason has its own plain message.
 *
 * @property message The text to show the user.
 */
enum class DisplayNameProblem(@StringRes val message: Int) {
    EMPTY(R.string.name_error_empty),
    TOO_LONG(R.string.name_error_too_long),
    CONTROL_CHARACTER(R.string.name_error_control_character),
    INVISIBLE_FORMATTING(R.string.name_error_invisible_formatting),
}

/** The result of checking a document name. */
sealed interface DisplayNameResult {

    /**
     * The name is fine.
     *
     * @property cleanName The name with spaces trimmed from both ends; save this one.
     */
    data class Valid(val cleanName: String) : DisplayNameResult

    /**
     * The name was rejected.
     *
     * @property problem Why.
     */
    data class Invalid(val problem: DisplayNameProblem) : DisplayNameResult
}

/** A document name failed [DisplayNameValidator]. */
class InvalidDisplayNameException(val problem: DisplayNameProblem) :
    IllegalArgumentException("Invalid document name: $problem")

/** Checks document names (Task 1.6, point 6). */
object DisplayNameValidator {

    /**
     * Checks a name the user typed.
     *
     * @param rawName The name as typed.
     * @return [DisplayNameResult.Valid] with the trimmed name, or the reason it was rejected.
     */
    fun validate(rawName: String): DisplayNameResult {
        val trimmed = rawName.trim()
        val problem = findProblem(trimmed)
        if (problem != null) return DisplayNameResult.Invalid(problem)
        return DisplayNameResult.Valid(trimmed)
    }

    private fun findProblem(name: String): DisplayNameProblem? {
        if (name.isEmpty()) return DisplayNameProblem.EMPTY
        // Count real characters (code points), so an emoji counts as one, not two.
        val characterCount = name.codePointCount(0, name.length)
        if (characterCount > MAX_DISPLAY_NAME_CHARACTERS) return DisplayNameProblem.TOO_LONG
        val codePoints = name.codePoints().toArray()
        if (codePoints.any { codePoint -> Character.isISOControl(codePoint) }) {
            return DisplayNameProblem.CONTROL_CHARACTER
        }
        // Invisible formatting (for example U+202E, which flips text direction) can make a name
        // look different from what it really is.
        if (codePoints.any { codePoint -> Character.getType(codePoint) == FORMAT_CATEGORY }) {
            return DisplayNameProblem.INVISIBLE_FORMATTING
        }
        return null
    }

    private const val FORMAT_CATEGORY = Character.FORMAT.toInt()
}

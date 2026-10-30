package com.heybuddy.facts

/**
 * The Verhoeff check digit used by Aadhaar numbers (spec 7.7). It catches every single wrong digit
 * and every swap of two neighbouring digits, so an OCR mistake makes the check fail and the app asks
 * the user instead of guessing.
 */
object Verhoeff {

    // The three fixed tables of the Verhoeff scheme (dihedral group D5).
    private val MULTIPLY = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val PERMUTE = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )
    private val INVERSE = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)
    private const val PERMUTE_CYCLE = 8

    /**
     * @param digits Only the digits 0-9, check digit last.
     * @return True if the check digit is right.
     */
    fun isValid(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all { character -> character in '0'..'9' }) return false
        return checksum(digits, offset = 0) == 0
    }

    /**
     * The check digit to add after [digits] (used to make valid FAKE numbers in tests).
     *
     * @param digits Only the digits 0-9, without a check digit.
     * @return The check digit, 0 to 9.
     */
    fun checkDigit(digits: String): Int {
        require(digits.all { character -> character in '0'..'9' }) { "Digits only" }
        return INVERSE[checksum(digits, offset = 1)]
    }

    /** Walks the digits from the right; [offset] 1 leaves room for a check digit not written yet. */
    private fun checksum(digits: String, offset: Int): Int {
        var check = 0
        val reversed = digits.reversed()
        for ((position, character) in reversed.withIndex()) {
            val digit = character - '0'
            val permuted = PERMUTE[(position + offset) % PERMUTE_CYCLE][digit]
            check = MULTIPLY[check][permuted]
        }
        return check
    }
}

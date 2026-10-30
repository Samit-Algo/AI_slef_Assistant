package com.heybuddy.facts

import com.heybuddy.util.DL_EARLIEST_YEAR
import com.heybuddy.util.PAN_MAX_FIXES

/**
 * PAN (spec 7.7): 5 letters, 4 digits, 1 letter. Mix-ups are fixed only by position, at most
 * [PAN_MAX_FIXES] of them (more would be guessing). Only a personal PAN (4th letter P) is a fact
 * about the user.
 */
object PanRule {

    /**
     * @param text Read text.
     * @return Each different personal PAN found.
     */
    fun find(text: String): List<FoundFact> {
        val upper = FactText.prepare(text).uppercase()
        val facts = mutableListOf<FoundFact>()
        for (match in CANDIDATE.findAll(upper)) {
            val fact = readPan(match.value) ?: continue
            if (facts.none { found -> found.value == fact.value }) facts.add(fact)
        }
        return facts
    }

    private fun readPan(candidate: String): FoundFact? {
        val fixed = StringBuilder()
        var fixes = 0
        for ((position, character) in candidate.withIndex()) {
            val wanted = if (position in DIGIT_POSITIONS) LookAlikes.asDigit(character) else LookAlikes.asLetter(character)
            if (wanted == null) return null
            if (wanted != character) fixes++
            fixed.append(wanted)
        }
        if (fixes > PAN_MAX_FIXES) return null
        val pan = fixed.toString()
        if (pan[HOLDER_TYPE_INDEX] != PERSONAL_HOLDER) return null
        val status = if (fixes == 0) FactStatus.READ_CLEANLY else FactStatus.PLEASE_CHECK
        return FoundFact(FactKind.PAN, pan, status)
    }

    /** Any 10 letters or digits as a whole word; the shape is checked position by position. */
    private val CANDIDATE = Regex("(?<![A-Z0-9])[A-Z0-9]{10}(?![A-Z0-9])")
    private val DIGIT_POSITIONS = 5..8
    private const val HOLDER_TYPE_INDEX = 3
    private const val PERSONAL_HOLDER = 'P'
}

/**
 * Aadhaar number (spec 7.7, Task 2.5 finder). Look-alike letters in a 4-4-4 number are changed only
 * when the Verhoeff check then passes, because the check proves the result. A 12-digit number that
 * fails the check is reported as "please type it", never guessed. VIDs, enrolment numbers and masked
 * numbers are never taken.
 */
object AadhaarRule {

    /**
     * @param text Read text.
     * @return The Aadhaar number (once, even if printed on front and back), or a "please type it",
     *   or nothing.
     */
    fun find(text: String): List<FoundFact> {
        val prepared = FactText.prepare(text)
        val clean = IdNumbers.aadhaarNumbers(prepared).distinct()
        if (clean.isNotEmpty()) return clean.map { number -> FoundFact(FactKind.AADHAAR_NUMBER, number, FactStatus.READ_CLEANLY) }
        val fixed = withLookAlikesFixed(prepared)
        if (fixed.isNotEmpty()) return fixed.map { number -> FoundFact(FactKind.AADHAAR_NUMBER, number, FactStatus.PLEASE_CHECK) }
        if (hasUnreadableNumber(prepared)) return listOf(FoundFact(FactKind.AADHAAR_NUMBER, "", FactStatus.PLEASE_TYPE))
        return emptyList()
    }

    private fun withLookAlikesFixed(text: String): List<String> {
        val numbers = mutableListOf<String>()
        for (match in GROUPED.findAll(text.uppercase())) {
            val raw = match.value.filter { character -> character != ' ' && character != '-' }
            if (raw.all { character -> character in '0'..'9' }) continue
            val digits = raw.map { character -> LookAlikes.asDigit(character) ?: return@map null }
            if (digits.any { digit -> digit == null }) continue
            val number = digits.joinToString("")
            if (IdNumbers.aadhaarNumbers(number).isNotEmpty() && number !in numbers) numbers.add(number)
        }
        return numbers
    }

    /** Exactly 12 digits (or a 4-4-4 group with look-alikes) that did not pass the checks. */
    private fun hasUnreadableNumber(text: String): Boolean {
        val twelveDigitRun = IdNumbers.digitRuns(text).any { digits -> digits.length == AADHAAR_LENGTH }
        return twelveDigitRun || GROUPED.containsMatchIn(text.uppercase())
    }

    private const val AADHAAR_LENGTH = 12

    /**
     * 4-4-4 with digits or look-alike letters, not touching other letters or digits, and not part
     * of a longer digit run (a VID "…1231 2468" has a 4th group).
     */
    private val GROUPED = Regex("(?<![A-Z0-9])(?<![0-9][ -])[0-9OISB]{4}[ -][0-9OISB]{4}[ -][0-9OISB]{4}(?![A-Z0-9])(?![ -][0-9OISB])")
}

/**
 * Driving licence number (Indian format): 2-letter state code, 2-digit office, 4-digit year,
 * 7-digit number, for example "TN00 20260001234". Mix-ups are fixed by position (the Task 2.2
 * "TNO0" miss); the state code must be a real one and the year sensible.
 */
object LicenceNumberRule {

    /**
     * @param text Read text.
     * @param currentYear This year (a licence cannot be from the future).
     * @return Each different licence number found.
     */
    fun find(text: String, currentYear: Int): List<FoundFact> {
        val upper = FactText.prepare(text).uppercase()
        val facts = mutableListOf<FoundFact>()
        for (match in CANDIDATE.findAll(upper)) {
            val fact = readNumber(match.value, currentYear) ?: continue
            if (facts.none { found -> found.value == fact.value }) facts.add(fact)
        }
        return facts
    }

    private fun readNumber(candidate: String, currentYear: Int): FoundFact? {
        val raw = candidate.filter { character -> character != ' ' && character != '-' }
        if (raw.length != LENGTH) return null
        val fixed = StringBuilder()
        var fixes = 0
        for ((position, character) in raw.withIndex()) {
            val wanted = if (position < STATE_LENGTH) LookAlikes.asLetter(character) else LookAlikes.asDigit(character)
            if (wanted == null) return null
            if (wanted != character) fixes++
            fixed.append(wanted)
        }
        val number = fixed.toString()
        val state = number.substring(0, STATE_LENGTH)
        val year = number.substring(YEAR_START, YEAR_END).toInt()
        if (state !in STATE_CODES || year < DL_EARLIEST_YEAR || year > currentYear) return null
        val shown = number.substring(0, YEAR_START) + " " + number.substring(YEAR_START)
        val status = if (fixes == 0) FactStatus.READ_CLEANLY else FactStatus.PLEASE_CHECK
        return FoundFact(FactKind.DRIVING_LICENCE_NUMBER, shown, status)
    }

    private const val LENGTH = 15
    private const val STATE_LENGTH = 2
    private const val YEAR_START = 4
    private const val YEAR_END = 8

    /** The 15 characters, with an optional space or hyphen after the office code and the year. */
    private val CANDIDATE = Regex("(?<![A-Z0-9])[A-Z0-9]{4}[ -]?[0-9OISB]{4}[ -]?[0-9OISB]{7}(?![A-Z0-9])")

    /** Indian state and union territory codes used on licences. */
    private val STATE_CODES = setOf(
        "AN", "AP", "AR", "AS", "BR", "CG", "CH", "DD", "DL", "DN", "GA", "GJ", "HP", "HR", "JH", "JK", "KA", "KL",
        "LA", "LD", "MH", "ML", "MN", "MP", "MZ", "NL", "OD", "OR", "PB", "PY", "RJ", "SK", "TN", "TR", "TS", "UK",
        "UP", "WB",
    )
}

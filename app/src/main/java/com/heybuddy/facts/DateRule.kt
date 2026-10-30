package com.heybuddy.facts

import com.heybuddy.util.DATE_EARLIEST_YEAR
import com.heybuddy.util.DATE_LATEST_YEAR
import com.heybuddy.util.MAX_AGE_YEARS
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Dates (spec 7.7). Always DAY FIRST, as on Indian documents: 05/08/1994 is 5 August, never May 8.
 * Understands DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY, "Year of Birth: YYYY", and day + month name +
 * year with English or Tamil month names. A date next to a date-of-birth label (English, Hindi,
 * Tamil, on the same line or the line above) is the date of birth; it must be real, not in the
 * future, and give an age of 0 to 120. Other dates (issue, validity) are kept as other dates.
 * Traditional Tamil calendar months (சித்திரை …) are a different calendar and are never converted:
 * such a date of birth is left for the user to type.
 */
object DateRule {

    /**
     * @param text Read text (lines kept).
     * @param today Today's date (a date of birth cannot be after it).
     * @return The date of birth (if labelled) and the other dates, each once.
     */
    fun find(text: String, today: LocalDate): List<FoundFact> {
        val lines = FactText.prepare(text).lines()
        val facts = mutableListOf<FoundFact>()
        for ((index, line) in lines.withIndex()) {
            val labelled = hasBirthLabel(line) || (index > 0 && isOnlyALabel(lines[index - 1]))
            for (fact in factsIn(line, labelled, today)) {
                if (facts.none { found -> found.kind == fact.kind && found.value == fact.value }) facts.add(fact)
            }
        }
        return facts
    }

    private fun factsIn(line: String, labelled: Boolean, today: LocalDate): List<FoundFact> {
        val facts = mutableListOf<FoundFact>()
        for (date in datesIn(line)) {
            val fact = if (labelled) birthFact(date, today) else otherDateFact(date)
            if (fact != null) facts.add(fact)
        }
        val year = yearOfBirthIn(line, today)
        if (year != null) facts.add(FoundFact(FactKind.DATE_OF_BIRTH, year.toString(), FactStatus.READ_CLEANLY))
        if (labelled && TRADITIONAL_TAMIL_DATE.containsMatchIn(line)) facts.add(FoundFact(FactKind.DATE_OF_BIRTH, "", FactStatus.PLEASE_TYPE))
        return facts
    }

    private fun birthFact(date: LocalDate, today: LocalDate): FoundFact? {
        val tooOld = date.isBefore(today.minusYears(MAX_AGE_YEARS.toLong()))
        if (date.isAfter(today) || tooOld) return null
        return FoundFact(FactKind.DATE_OF_BIRTH, date.toString(), FactStatus.READ_CLEANLY)
    }

    private fun otherDateFact(date: LocalDate): FoundFact? {
        if (date.year < DATE_EARLIEST_YEAR || date.year > DATE_LATEST_YEAR) return null
        return FoundFact(FactKind.OTHER_DATE, date.toString(), FactStatus.READ_CLEANLY)
    }

    /** Every real calendar date on one line, numeric or with a month name. */
    private fun datesIn(line: String): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        for (match in NUMERIC_DATE.findAll(line)) {
            val (day, month, year) = match.destructured
            dateOrNull(year.toInt(), month.toInt(), day.toInt())?.let { date -> dates.add(date) }
        }
        val lower = line.lowercase()
        for (match in NAMED_MONTH_DATE.findAll(lower)) {
            val (day, monthName, year) = match.destructured
            val month = MONTHS[monthName] ?: continue
            dateOrNull(year.toInt(), month, day.toInt())?.let { date -> dates.add(date) }
        }
        return dates
    }

    /** "Year of Birth: 1990" (older Aadhaar cards print only the year). */
    private fun yearOfBirthIn(line: String, today: LocalDate): Int? {
        val match = YEAR_OF_BIRTH.find(line.lowercase()) ?: return null
        val year = match.groupValues[1].toInt()
        val sensible = year <= today.year && year >= today.year - MAX_AGE_YEARS
        return if (sensible) year else null
    }

    /** Null for a date that does not exist, such as 31/02. */
    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? {
        return try {
            LocalDate.of(year, month, day)
        } catch (impossible: DateTimeException) {
            null
        }
    }

    private fun hasBirthLabel(line: String): Boolean {
        val lower = line.lowercase()
        return BIRTH_LABELS.any { label -> lower.contains(label) }
    }

    /** A line that is just a label (the PAN card prints "Date of Birth" above the date). */
    private fun isOnlyALabel(line: String): Boolean = hasBirthLabel(line) && datesIn(line).isEmpty() && !line.any { character -> character in '0'..'9' }

    private val NUMERIC_DATE = Regex("(?<![0-9])([0-9]{1,2})[/.-]([0-9]{1,2})[/.-]([0-9]{4})(?![0-9])")

    private val BIRTH_LABELS = listOf(
        "dob", "d.o.b", "date of birth", "birth date",
        "जन्म तिथि", "जन्म की तारीख", "जन्मतिथि",
        "பிறந்த தேதி", "பிறந்த நாள்",
    )

    private val YEAR_OF_BIRTH = Regex("(?:year of birth|yob|जन्म वर्ष|பிறந்த ஆண்டு)\\s*[:/-]?\\s*([0-9]{4})(?![0-9])")

    private val MONTHS: Map<String, Int> = buildMap {
        val english = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        for ((index, name) in english.withIndex()) {
            put(name, index + 1)
            put(name.take(SHORT_MONTH_LENGTH), index + 1)
        }
        put("sept", SEPTEMBER)
        // Tamil names of the ordinary (Gregorian) months, spec 7.7.
        val tamil = listOf("ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்", "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்")
        for ((index, name) in tamil.withIndex()) put(name, index + 1)
    }

    /** Day, month name, year, with spaces, commas, dots, hyphens or slashes between. */
    private val NAMED_MONTH_DATE: Regex = run {
        val names = MONTHS.keys.sortedByDescending { name -> name.length }.joinToString("|") { name -> Regex.escape(name) }
        Regex("(?<![0-9])([0-9]{1,2})[\\s,./-]*($names)(?![\\p{L}\\p{M}])[\\s,./-]*([0-9]{4})(?![0-9])")
    }

    /** Traditional Tamil calendar months: a day, the month, a year. Never converted. */
    private val TRADITIONAL_TAMIL_DATE = run {
        val months = listOf("சித்திரை", "வைகாசி", "ஆனி", "ஆடி", "ஆவணி", "புரட்டாசி", "ஐப்பசி", "கார்த்திகை", "மார்கழி", "தை", "மாசி", "பங்குனி")
        val names = months.joinToString("|") { name -> Regex.escape(name) }
        Regex("(?<![0-9])[0-9]{1,2}\\s+($names)(?![\\p{L}\\p{M}])\\s+[0-9]{4}")
    }

    private const val SHORT_MONTH_LENGTH = 3
    private const val SEPTEMBER = 9
}

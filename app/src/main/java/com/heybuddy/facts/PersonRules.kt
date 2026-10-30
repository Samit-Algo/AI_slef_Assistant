package com.heybuddy.facts

import com.heybuddy.util.NAME_MAX_WORDS
import com.heybuddy.util.NAME_SEARCH_LINES_ABOVE_DOB

/**
 * Labels the person rules look for (spec 7.7), in English, Hindi and Tamil. English labels must be
 * whole words; Hindi and Tamil labels are matched as written.
 */
internal object Labels {

    val NAME = listOf("name", "नाम", "பெயர்")
    val FATHER = listOf("father's name", "fathers name", "father name", "पिता का नाम", "தந்தை பெயர்", "தந்தையின் பெயர்")
    val HUSBAND = listOf("husband's name", "husbands name", "husband name", "पति का नाम", "கணவர் பெயர்")
    val ADDRESS = listOf("address", "पता", "முகவரி")
    val BIRTH = listOf("dob", "d.o.b", "date of birth", "birth date", "year of birth", "जन्म तिथि", "जन्म की तारीख", "जन्मतिथि", "பிறந்த தேதி", "பிறந்த நாள்")
    val GENDER = listOf("gender", "sex", "लिंग", "பாலினம்")
    val OTHER = listOf("valid", "issue", "dl no", "blood group", "signature", "வழங்கிய", "வட்டம்", "மாவட்டம்")

    /**
     * Where the label ends in the line, or -1 if it is not there.
     *
     * @param line A line (any case).
     * @param labels The labels to look for.
     * @return The end of the last label found.
     */
    fun endOf(line: String, labels: List<String>): Int {
        val lower = line.lowercase()
        var end = -1
        for (label in labels) {
            val match = patternFor(label).findAll(lower).lastOrNull() ?: continue
            end = maxOf(end, match.range.last + 1)
        }
        return end
    }

    /** True if the line has any of the labels. */
    fun has(line: String, labels: List<String>): Boolean = endOf(line, labels) >= 0

    /** True if the line has any label at all (an address stops at the next label). */
    fun hasAny(line: String): Boolean = listOf(NAME, FATHER, HUSBAND, ADDRESS, BIRTH, GENDER, OTHER).any { labels -> has(line, labels) }

    /**
     * What follows the LAST label of the group on the line, without separators. Because each group
     * holds all three languages, a bilingual label such as "नाम / Name" ends after "Name", and an
     * all-label line gives "" (the value is then on the next line).
     */
    fun valueAfter(line: String, labels: List<String>): String {
        val end = endOf(line, labels)
        if (end < 0) return ""
        val rest = line.substring(end)
        return rest.trimStart(*SEPARATORS).trim()
    }

    /** Between a label and its value: spaces, colon, slash, hyphen, bar, dot, and Hindi visarga (ः), which OCR gives for ":". */
    private val SEPARATORS = charArrayOf(' ', ':', '/', '-', '|', '.', 'ः')

    private fun patternFor(label: String): Regex {
        val isEnglish = label.all { character -> character.code < ASCII_LIMIT }
        val escaped = Regex.escape(label)
        return if (isEnglish) Regex("(?<![a-z])$escaped(?![a-z])") else Regex(escaped)
    }

    private const val ASCII_LIMIT = 128
}

/**
 * Checks whether a piece of text is a plausible name (spec 7.7, owner rules of Task 2.7): 1 to 5
 * words, letters, spaces and dots only, initials before or after the name kept exactly as printed
 * ("S. MURUGAN", "MURUGAN S", "R.K. SELVI"). Courtesy titles (Mr, Smt, திரு, செல்வி …) are not part
 * of the name. Label words ("MALE", "GOVERNMENT" …) are never a name.
 */
internal object NameShape {

    /**
     * @param raw Text that may be an English-letter name.
     * @return The name as printed (spaces tidied), or null.
     */
    fun latin(raw: String): String? {
        val words = wordsWithoutTitles(raw, LATIN_TITLES)
        if (words.isEmpty() || words.size > NAME_MAX_WORDS) return null
        if (!words.all { word -> LATIN_WORD.matches(word) }) return null
        if (words.none { word -> LETTER_RUN.containsMatchIn(word) }) return null
        if (words.any { word -> word.uppercase().trimEnd('.') in STOP_WORDS }) return null
        return words.joinToString(" ")
    }

    /**
     * @param raw Text that may be a Tamil-script name (Tamil words, Tamil or English initials).
     * @return The name as printed (spaces tidied), or null.
     */
    fun tamil(raw: String): String? {
        val words = wordsWithoutTitles(raw, TAMIL_TITLES)
        if (words.isEmpty() || words.size > NAME_MAX_WORDS) return null
        val allowed = words.all { word -> isTamilWord(word) || TAMIL_OR_LATIN_INITIAL.matches(word) }
        if (!allowed || words.none { word -> isTamilWord(word) && word.trimEnd('.').length >= MIN_TAMIL_WORD }) return null
        if (words.any { word -> word.trimEnd('.') in TAMIL_STOP_WORDS }) return null
        return words.joinToString(" ")
    }

    private fun wordsWithoutTitles(raw: String, titles: Set<String>): List<String> {
        val trimmed = raw.trim().trim(',', ':', ';', '-', '|')
        val words = trimmed.split(WHITESPACE).filter { word -> word.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first().uppercase().trimEnd('.') in titles) words.removeAt(0)
        return words
    }

    private fun isTamilWord(word: String): Boolean {
        val body = word.trimEnd('.')
        return body.isNotEmpty() && body.all { character -> FactText.isTamil(character) }
    }

    /** A word, or initials glued together or to the name: "MURUGAN", "S.", "R.K.", "S.MURUGAN". */
    private val LATIN_WORD = Regex("(?:[A-Za-z]+\\.)*[A-Za-z]+\\.?")
    private val LETTER_RUN = Regex("[A-Za-z]{2,}")
    private val TAMIL_OR_LATIN_INITIAL = Regex("(?:[A-Za-z]|[\\u0B80-\\u0BFF]{1,3})\\.")
    private val WHITESPACE = Regex("\\s+")
    private const val MIN_TAMIL_WORD = 2

    private val LATIN_TITLES = setOf("MR", "MRS", "MS", "SHRI", "SRI", "SMT", "KUM", "DR", "MISS")
    private val TAMIL_TITLES = setOf("திரு", "திருமதி", "செல்வி", "திருமிகு")

    /** Words that appear on cards next to names but are never part of one. */
    private val STOP_WORDS = setOf(
        "MALE", "FEMALE", "TRANSGENDER", "GOVERNMENT", "GOVT", "INDIA", "DEPARTMENT", "CARD", "DOCUMENT", "LICENCE",
        "LICENSE", "IDENTITY", "ADDRESS", "SIGNATURE", "NUMBER", "ACCOUNT", "INCOME", "TAX", "DATE", "BIRTH", "DOB",
        "NAME", "FATHER", "FATHERS", "HUSBAND", "ONLY", "TESTING", "REAL", "NOT", "VALID", "ISSUE", "AUTHORITY",
        "REPUBLIC", "UNION", "PERMANENT", "OF", "THE", "AND", "FOR", "TILL", "DRIVING", "PASSPORT", "CERTIFICATE",
    )
    private val TAMIL_STOP_WORDS = setOf("பெயர்", "தந்தை", "முகவரி", "பிறந்த", "தேதி", "ஆண்", "பெண்", "அரசு", "சான்றிதழ்", "பாலினம்")
}

/**
 * The holder's name (spec 7.7): after a name label (English, Hindi or Tamil; on the same line or the
 * next); otherwise, on an Aadhaar front, the English line just above the date of birth (the Hindi
 * line is skipped); otherwise, on an old PAN card, the first name line under "INCOME TAX
 * DEPARTMENT". Always "please check": names are hard to read for sure.
 */
object NameRule {

    /**
     * @param text Read text.
     * @return The name in English letters and/or in Tamil script, each once.
     */
    fun find(text: String): List<FoundFact> {
        val lines = FactText.usableLines(text)
        val facts = mutableListOf<FoundFact>()
        addLabelled(lines, facts)
        if (facts.none { fact -> fact.kind == FactKind.NAME }) addAboveBirthDate(lines, facts)
        if (facts.none { fact -> fact.kind == FactKind.NAME }) {
            OldPanCard.names(lines).firstOrNull()?.let { name -> add(facts, FactKind.NAME, name) }
        }
        return facts
    }

    private fun addLabelled(lines: List<String>, facts: MutableList<FoundFact>) {
        for ((index, line) in lines.withIndex()) {
            if (!Labels.has(line, Labels.NAME) || Labels.has(line, Labels.FATHER) || Labels.has(line, Labels.HUSBAND)) continue
            val sameLine = Labels.valueAfter(line, Labels.NAME)
            val value = sameLine.ifEmpty { lines.getOrNull(index + 1).orEmpty() }
            NameShape.latin(value)?.let { name -> add(facts, FactKind.NAME, name) }
            NameShape.tamil(value)?.let { name -> add(facts, FactKind.NAME_TAMIL, name) }
        }
    }

    private fun addAboveBirthDate(lines: List<String>, facts: MutableList<FoundFact>) {
        val birthLine = lines.indexOfFirst { line -> Labels.has(line, Labels.BIRTH) }
        if (birthLine <= 0) return
        val first = maxOf(0, birthLine - NAME_SEARCH_LINES_ABOVE_DOB)
        for (index in birthLine - 1 downTo first) {
            val line = lines[index]
            if (Labels.hasAny(line)) continue
            if (facts.none { fact -> fact.kind == FactKind.NAME }) NameShape.latin(line)?.let { name -> add(facts, FactKind.NAME, name) }
            if (facts.none { fact -> fact.kind == FactKind.NAME_TAMIL }) NameShape.tamil(line)?.let { name -> add(facts, FactKind.NAME_TAMIL, name) }
        }
    }
}

/**
 * Father's, husband's or another relative's name (spec 7.7, owner rule of Task 2.7): after a
 * father's or husband's name label; from "S/O", "D/O", "த/பெ" (father), "W/O", "க/பெ" (husband),
 * "C/O", "S/D/W of" (relative, exact relation not printed); or the second name line on an old PAN
 * card. Always "please check".
 */
object RelativeNameRule {

    /**
     * @param text Read text.
     * @return The names found, each once per kind.
     */
    fun find(text: String): List<FoundFact> {
        val lines = FactText.usableLines(text)
        val facts = mutableListOf<FoundFact>()
        for ((index, line) in lines.withIndex()) {
            addLabelled(lines, index, Labels.FATHER, FactKind.FATHER_NAME, facts)
            addLabelled(lines, index, Labels.HUSBAND, FactKind.HUSBAND_NAME, facts)
            addRelation(line, facts)
        }
        if (facts.none { fact -> fact.kind == FactKind.FATHER_NAME }) {
            OldPanCard.names(lines).getOrNull(1)?.let { name -> add(facts, FactKind.FATHER_NAME, name) }
        }
        return facts
    }

    private fun addLabelled(lines: List<String>, index: Int, labels: List<String>, kind: FactKind, facts: MutableList<FoundFact>) {
        val line = lines[index]
        if (!Labels.has(line, labels)) return
        val value = Labels.valueAfter(line, labels).ifEmpty { lines.getOrNull(index + 1).orEmpty() }
        val name = NameShape.latin(value) ?: NameShape.tamil(value) ?: return
        add(facts, kind, name)
    }

    /** "S/O: SURESH KUMAR, 123 CIVIL LINES": the name runs up to the first comma or the line's end. */
    private fun addRelation(line: String, facts: MutableList<FoundFact>) {
        val match = RELATION.find(line) ?: return
        val marker = match.groupValues[1].lowercase().replace(" ", "")
        val kind = RELATIONS[marker] ?: return
        val value = match.groupValues[2]
        val name = NameShape.latin(value) ?: NameShape.tamil(value) ?: return
        add(facts, kind, name)
    }

    private val RELATIONS = mapOf(
        "s/o" to FactKind.FATHER_NAME, "d/o" to FactKind.FATHER_NAME, "த/பெ" to FactKind.FATHER_NAME,
        "w/o" to FactKind.HUSBAND_NAME, "க/பெ" to FactKind.HUSBAND_NAME,
        "c/o" to FactKind.RELATIVE_NAME, "s/d/wof" to FactKind.RELATIVE_NAME, "s/d/w/o" to FactKind.RELATIVE_NAME,
    )

    /** A relation marker as a whole word, then the name up to a comma or the end of the line. */
    internal val RELATION = Regex("(?i)(?<![\\p{L}\\p{M}])(s/d/w of|s/d/w/o|s/o|d/o|w/o|c/o|த/பெ|க/பெ)\\s*[:.\\-]?\\s*([^,]+)")
}

/** Gender (spec 7.7): English, Hindi and Tamil words, or "Gender: M/F". Read cleanly when only one gender is printed. */
object GenderRule {

    /**
     * @param text Read text.
     * @return The gender, or nothing if none or two different ones are printed.
     */
    fun find(text: String): List<FoundFact> {
        val found = mutableSetOf<String>()
        for (line in FactText.usableLines(text)) {
            val lower = line.lowercase()
            for ((word, gender) in WORDS) {
                if (wholeWord(word).containsMatchIn(lower)) found.add(gender)
            }
            val short = SHORT_FORM.find(lower)
            if (short != null) found.add(if (short.groupValues[1] == "m") MALE else FEMALE)
        }
        if (found.size != 1) return emptyList()
        return listOf(FoundFact(FactKind.GENDER, found.single(), FactStatus.READ_CLEANLY))
    }

    private fun wholeWord(word: String): Regex = Regex("(?<![\\p{L}\\p{M}])" + Regex.escape(word) + "(?![\\p{L}\\p{M}])")

    private const val MALE = "MALE"
    private const val FEMALE = "FEMALE"
    private const val TRANSGENDER = "TRANSGENDER"

    private val WORDS = listOf(
        "male" to MALE, "female" to FEMALE, "transgender" to TRANSGENDER,
        "पुरुष" to MALE, "महिला" to FEMALE, "ट्रांसजेंडर" to TRANSGENDER,
        "ஆண்" to MALE, "பெண்" to FEMALE, "மூன்றாம் பாலினம்" to TRANSGENDER,
    )

    /** "Gender: M" or "Sex: F". */
    private val SHORT_FORM = Regex("(?:gender|sex)\\s*[:/-]\\s*([mf])(?![a-z])")
}

/** Old PAN cards have no labels: under "INCOME TAX DEPARTMENT" come the name, then the father's name. */
internal object OldPanCard {

    /**
     * @param lines Usable lines.
     * @return Up to two name lines after the heading (not counting "GOVT. OF INDIA").
     */
    fun names(lines: List<String>): List<String> {
        val heading = lines.indexOfFirst { line -> line.lowercase().contains("income tax department") }
        if (heading < 0) return emptyList()
        val names = mutableListOf<String>()
        for (line in lines.drop(heading + 1)) {
            if (names.size == 2) break
            val lower = line.lowercase()
            if (lower.contains("govt") || lower.contains("government") || Labels.hasAny(line)) continue
            val name = NameShape.latin(line)
            // Before the first name, other lines (Hindi heading, garbage) are skipped; after it,
            // anything that is not a name ends the names (the date of birth comes next).
            if (name != null) {
                names.add(name)
            } else if (names.isNotEmpty()) {
                break
            }
        }
        return names
    }
}

private fun add(facts: MutableList<FoundFact>, kind: FactKind, name: String) {
    if (facts.none { fact -> fact.kind == kind && fact.value == name }) facts.add(FoundFact(kind, name, FactStatus.PLEASE_CHECK))
}

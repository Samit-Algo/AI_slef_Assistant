package com.heybuddy.facts

import com.heybuddy.util.ADDRESS_MAX_LINES

/**
 * Address (spec 7.7): the text after "Address" / "पता" / "முகவரி", up to and including the PIN code,
 * lines joined. English and Tamil addresses are separate facts (a Hindi address is skipped). A
 * leading "C/O", "S/O", "D/O" or "W/O" with a name is kept OUT of the address (owner rule, Task 2.7;
 * RelativeNameRule uses it as a hint). Always "please check": where an address ends is a guess.
 */
object AddressRule {

    /**
     * @param text Read text.
     * @return Each different address found.
     */
    fun find(text: String): List<FoundFact> {
        val lines = FactText.usableLines(text)
        val facts = mutableListOf<FoundFact>()
        for ((index, line) in lines.withIndex()) {
            if (!Labels.has(line, Labels.ADDRESS)) continue
            val fact = addressFrom(lines, index) ?: continue
            if (facts.none { found -> found.kind == fact.kind && found.value == fact.value }) facts.add(fact)
        }
        return facts
    }

    private fun addressFrom(lines: List<String>, labelLine: Int): FoundFact? {
        val expected = expectedScript(lines[labelLine])
        val parts = mutableListOf<String>()
        val first = Labels.valueAfter(lines[labelLine], Labels.ADDRESS)
        if (first.isNotEmpty()) parts.add(first)
        var next = labelLine + 1
        while (parts.size < ADDRESS_MAX_LINES && !endsWithPin(parts) && next < lines.size) {
            val line = lines[next]
            val script = scriptOf(line)
            val wrongScript = (expected != null && script != expected) || (parts.isNotEmpty() && script != scriptOf(parts.first()))
            if (Labels.hasAny(line) || wrongScript) break
            parts.add(line)
            next++
        }
        if (expected != null && parts.isNotEmpty() && scriptOf(parts.first()) != expected) return null
        val kept = withoutRelation(parts)
        if (kept.isEmpty()) return null
        val kind = when (scriptOf(kept.first())) {
            Script.TAMIL -> FactKind.ADDRESS_TAMIL
            Script.LATIN -> FactKind.ADDRESS
            Script.OTHER -> return null
        }
        return FoundFact(kind, joined(kept), FactStatus.PLEASE_CHECK)
    }

    /** "S/O: SURESH KUMAR, 12 MAIN ROAD" → "12 MAIN ROAD"; a line that is only "S/O: SURESH KUMAR" goes. */
    private fun withoutRelation(parts: List<String>): List<String> {
        if (parts.isEmpty()) return parts
        val first = parts.first()
        val match = RelativeNameRule.RELATION.find(first)
        if (match == null || match.range.first > MAX_RELATION_START) return parts
        val afterName = first.substring(match.range.last + 1).trimStart(',', ' ')
        val rest = parts.drop(1)
        return if (afterName.isEmpty()) rest else listOf(afterName) + rest
    }

    /**
     * The script the address must be in: a Tamil label (முகவரி) is followed by a Tamil address, an
     * English label by an English one. "पता" alone may be followed by either (Aadhaar backs print
     * "पता:" above the English address). This also stops ML Kit's English-looking garbage under a
     * Tamil label (the wrong PIN of Task 2.2).
     */
    private fun expectedScript(labelLine: String): Script? {
        val lower = labelLine.lowercase()
        return when {
            lower.contains("முகவரி") -> Script.TAMIL
            Labels.has(lower, listOf("address")) -> Script.LATIN
            else -> null
        }
    }

    private fun endsWithPin(parts: List<String>): Boolean = parts.isNotEmpty() && PIN.containsMatchIn(parts.last())

    /** Lines joined with ", ", without doubled commas or spaces. */
    private fun joined(parts: List<String>): String {
        val text = parts.joinToString(", ") { part -> part.trim().trimEnd(',') }
        return text.replace(SPACES, " ").trim()
    }

    private enum class Script { LATIN, TAMIL, OTHER }

    private fun scriptOf(line: String): Script {
        val tamil = line.count { character -> FactText.isTamil(character) }
        val latin = line.count { character -> FactText.isLatin(character) }
        return when {
            tamil > 0 && tamil >= latin -> Script.TAMIL
            latin > 0 -> Script.LATIN
            else -> Script.OTHER
        }
    }

    /** A relation marker counts only at the very start of the address (a few characters of slack). */
    private const val MAX_RELATION_START = 2

    internal val PIN = Regex("(?<![0-9])[1-9][0-9]{5}(?![0-9])")
    private val SPACES = Regex("\\s+")
}

/** PIN code (spec 7.7): 6 digits, not starting with 0, at the end of an address found by [AddressRule]. */
object PinCodeRule {

    /**
     * @param text Read text.
     * @return Each different PIN code; "please check" when the addresses disagree.
     */
    fun find(text: String): List<FoundFact> {
        val pins = AddressRule.find(text).mapNotNull { address -> AddressRule.PIN.findAll(address.value).lastOrNull()?.value }.distinct()
        val status = if (pins.size == 1) FactStatus.READ_CLEANLY else FactStatus.PLEASE_CHECK
        return pins.map { pin -> FoundFact(FactKind.PIN_CODE, pin, status) }
    }
}

package com.heybuddy.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verhoeff and the strict number finding (Task 2.5). FAKE numbers only, made with the check digit. */
class IdNumbersTest {

    /** The fake number on the Aadhaar-style sample (Task 2.2); its last digit is a correct check digit. */
    private val fakeAadhaar = "999945671231"

    @Test
    fun verhoeffAcceptsCorrectCheckDigits() {
        assertTrue(Verhoeff.isValid("2363")) // the textbook example: 236 → check digit 3
        assertTrue(Verhoeff.isValid(fakeAadhaar))
        assertEquals(1, Verhoeff.checkDigit("99994567123"))
    }

    @Test
    fun verhoeffCatchesOneWrongDigit() {
        for (position in fakeAadhaar.indices) {
            val wrong = fakeAadhaar.replaceRange(position, position + 1, (((fakeAadhaar[position] - '0') + 1) % 10).toString())
            assertFalse("Digit ${position + 1} changed: $wrong", Verhoeff.isValid(wrong))
        }
    }

    @Test
    fun verhoeffCatchesSwappedNeighbours() {
        for (position in 0 until fakeAadhaar.length - 1) {
            val chars = fakeAadhaar.toCharArray()
            if (chars[position] == chars[position + 1]) continue
            val swapped = chars.clone().also { array ->
                array[position] = chars[position + 1]
                array[position + 1] = chars[position]
            }
            assertFalse("Swap at ${position + 1}", Verhoeff.isValid(String(swapped)))
        }
    }

    @Test
    fun findsAnAadhaarNumberWithSpacesOrHyphens() {
        assertEquals(listOf(fakeAadhaar), IdNumbers.aadhaarNumbers("9999 4567 1231"))
        assertEquals(listOf(fakeAadhaar), IdNumbers.aadhaarNumbers("No: 9999-4567-1231."))
        assertEquals(listOf(fakeAadhaar), IdNumbers.aadhaarNumbers(fakeAadhaar))
    }

    @Test
    fun aWrongCheckDigitIsNotAnAadhaarNumber() {
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("9999 4567 1232"))
    }

    @Test
    fun anAadhaarNumberNeverStartsWithZeroOrOne() {
        for (first in listOf("0", "1")) {
            val body = first + "9994567123"
            val valid = body + Verhoeff.checkDigit(body)
            assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers(valid))
        }
    }

    @Test
    fun aSixteenDigitVirtualIdIsNeverAnAadhaarNumber() {
        // Owner addition: a 16-digit VID, even one that ends in a valid 12-digit number.
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("VID: 9999 4567 1231 2468"))
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("VID: 2468 9999 4567 1231"))
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("2468999945671231"))
    }

    @Test
    fun twelveDigitsInsideALongerRunDoNotCount() {
        // Owner addition: a valid 12-digit number hidden inside a longer digit run.
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("55" + fakeAadhaar))
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers(fakeAadhaar + "7"))
        // A 14-digit enrolment-style run.
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("2026 9999 4567 1231"))
    }

    @Test
    fun aMaskedNumberIsNotAnAadhaarNumber() {
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("XXXX XXXX 1231"))
        assertEquals(emptyList<String>(), IdNumbers.aadhaarNumbers("xxxx-xxxx-1231"))
    }

    @Test
    fun numbersOnSeparateLinesStaySeparate() {
        assertEquals(listOf(fakeAadhaar), IdNumbers.aadhaarNumbers("600004\n9999 4567 1231\n14/08/1990"))
    }

    @Test
    fun indianLookAlikeDigitsAreRead() {
        // As the Devanagari reader once wrote it: Bengali zero and Devanagari digits mixed in.
        assertEquals(listOf(fakeAadhaar), IdNumbers.aadhaarNumbers("९९९९ 4567 123১"))
        assertEquals("600004", IdNumbers.withAsciiDigits("௬௦௦௦௦௪"))
    }

    @Test
    fun panNeedsTheExactShapeAndAValidHolderType() {
        assertEquals(listOf("ABCPD1234Z"), IdNumbers.panNumbers("PAN: ABCPD1234Z"))
        assertEquals(listOf("ABCPD1234Z"), IdNumbers.panNumbers("abcpd1234z"))
        assertEquals(emptyList<String>(), IdNumbers.panNumbers("ABCD1234Z"))
        assertEquals(emptyList<String>(), IdNumbers.panNumbers("ABCPD1234ZX"))
        // 4th letter "X" is no holder type.
        assertEquals(emptyList<String>(), IdNumbers.panNumbers("ABCXD1234Z"))
    }
}

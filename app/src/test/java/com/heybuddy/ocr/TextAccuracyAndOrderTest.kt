package com.heybuddy.ocr

import com.heybuddy.ocr.measure.TextAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Task 2.2 measuring maths and the reading-order rule. */
class TextAccuracyAndOrderTest {

    @Test
    fun editDistanceCountsSingleCharacterChanges() {
        assertEquals(0, TextAccuracy.editDistance("BUDDY", "BUDDY"))
        assertEquals(1, TextAccuracy.editDistance("BUDDY", "BUODY"))
        assertEquals(2, TextAccuracy.editDistance("BUDDY", "BUDY "))
        assertEquals(5, TextAccuracy.editDistance("", "BUDDY"))
    }

    @Test
    fun editDistanceCountsWholeLettersNotBytes() {
        assertEquals(1, TextAccuracy.editDistance("नमस्ते", "नमस्त"))
        assertEquals(0, TextAccuracy.editDistance("டெஸ்டா", "டெஸ்டா"))
    }

    @Test
    fun lineAccuracyUsesTheBestMatchingLine() {
        val read = listOf("Something else", "HEY BUODY 2026")

        assertEquals(1.0 - 1.0 / 14, TextAccuracy.lineAccuracy("HEY BUDDY 2026", read), 0.0001)
    }

    @Test
    fun lineAccuracyFindsALineThatOcrSplitInTwo() {
        assertEquals(1.0, TextAccuracy.lineAccuracy("DOB: 14/08/1990", listOf("DOB:", "14/08/1990")), 0.0001)
    }

    @Test
    fun extraGarbageLinesDoNotLowerAccuracyButAreCounted() {
        val expected = listOf("TESTA KUMARI")
        val read = listOf("TESTA KUMARI", "@#4 1lI|", "SAMPLE")

        assertEquals(1.0, TextAccuracy.pageAccuracy(expected, read), 0.0001)
        assertEquals(listOf("@#4 1lI|"), TextAccuracy.unexplainedLines(read, expected, ignored = listOf("SAMPLE")))
    }

    @Test
    fun numbersAreFoundWithOrWithoutTheirSpaces() {
        assertTrue(TextAccuracy.fieldFound("9999 4567 1231", "No: 999945671231", ignoreSpaces = true))
        assertFalse(TextAccuracy.fieldFound("9999 4567 1231", "No: 9999 4567 1281", ignoreSpaces = true))
        assertFalse(TextAccuracy.fieldFound("TESTA KUMARI", "TESTA KUMAR1", ignoreSpaces = false))
    }

    @Test
    fun garbageThatLooksLikeAFieldIsSpotted() {
        assertTrue(TextAccuracy.looksLikeField("1234 5678 9012"))
        assertTrue(TextAccuracy.looksLikeField("ABCPT1234Z"))
        assertTrue(TextAccuracy.looksLikeField("12/04/1999"))
        assertTrue(TextAccuracy.looksLikeField("GLUAI NUAL"))
        assertFalse(TextAccuracy.looksLikeField("@#4 1lI|"))
    }

    @Test
    fun linesAreOrderedTopToBottomAndLeftToRightWithinARow() {
        val value = RecognizedLine("TESTA", LineBox(left = 300, top = 102, right = 500, bottom = 140))
        val label = RecognizedLine("Name:", LineBox(left = 50, top = 100, right = 200, bottom = 138))
        val below = RecognizedLine("DOB", LineBox(left = 50, top = 200, right = 120, bottom = 238))
        val unplaced = RecognizedLine("?", null)

        val ordered = inReadingOrder(listOf(below, unplaced, value, label))

        assertEquals(listOf("Name:", "TESTA", "DOB", "?"), ordered.map { line -> line.text })
    }
}

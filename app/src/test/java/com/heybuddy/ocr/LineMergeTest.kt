package com.heybuddy.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The merge rule of spec 7.4a, with lines like the ones measured in Task 2.3 (fake data). The owner's
 * safety rule: when unsure, keep both lines; never drop a line that might be real.
 */
class LineMergeTest {

    private val nameSpot = LineBox(left = 400, top = 300, right = 1000, bottom = 350)
    private val pinSpot = LineBox(left = 400, top = 670, right = 900, bottom = 720)
    private val otherSpot = LineBox(left = 400, top = 900, right = 900, bottom = 950)

    @Test
    fun tesseractGarbageOverRealEnglishIsDropped() {
        val english = RecognizedLine("Name: DEMO ARUN TEST", nameSpot, confidence = 0.84f)
        val tesseractGarbage = RecognizedLine("14௮6: மட்மா௦ கப்பா", nameSpot, confidence = 0.05f)

        val merged = LineMerge.merge(listOf(english), listOf(tesseractGarbage))

        assertEquals(listOf(english), merged)
    }

    @Test
    fun tesseractDigitsOverRealDigitsAreDropped() {
        // No Tamil letters at all, so never "surely Tamil"; ML Kit already has the same spot.
        val number = RecognizedLine("9999 4567 1231", nameSpot, confidence = 0.90f)
        val sameDigits = RecognizedLine("9999 4567 1231", nameSpot, confidence = 0.94f)

        assertEquals(listOf(number), LineMerge.merge(listOf(number), listOf(sameDigits)))
    }

    @Test
    fun mlKitGarbageWithAWrongPinIsReplacedBySureTamil() {
        // The Task 2.2 danger: ML Kit read a Tamil address line as a WRONG PIN code.
        val mlKitGarbage = RecognizedLine("GTUILDL छ - 647001", pinSpot, confidence = 0.49f)
        val tamil = RecognizedLine("கோயம்புத்தூர் - 641001", pinSpot, confidence = 0.93f)

        val merged = LineMerge.merge(listOf(mlKitGarbage), listOf(tamil))

        assertEquals(listOf(tamil), merged)
    }

    @Test
    fun mlKitLineWithBengaliLettersIsGarbageWhateverItsConfidence() {
        val bengaliLookAlikes = RecognizedLine("০LUয: LGLDT অ(UH600", nameSpot, confidence = 0.75f)
        val tamil = RecognizedLine("பெயர்: டெமோ அருண் டெஸ்ட்", nameSpot, confidence = 0.96f)

        assertEquals(listOf(tamil), LineMerge.merge(listOf(bengaliLookAlikes), listOf(tamil)))
    }

    @Test
    fun unsureMlKitLineKeepsBothLines() {
        // 0.55 is between garbage and real: unsure, so nothing is dropped.
        val unsure = RecognizedLine("LD5J60T - 625020", pinSpot, confidence = 0.55f)
        val tamil = RecognizedLine("மதுரை - 625020", pinSpot, confidence = 0.95f)

        val merged = LineMerge.merge(listOf(unsure), listOf(tamil))

        assertEquals(listOf(unsure, tamil), merged)
    }

    @Test
    fun unsureTamilLineKeepsBothLines() {
        // Tesseract only 0.72 sure: not enough to replace ML Kit's garbage, so both stay.
        val mlKitGarbage = RecognizedLine("Ouwj: Qssu lL6ULIT", nameSpot, confidence = 0.35f)
        val unsureTamil = RecognizedLine("பெயர்: செல்வி", nameSpot, confidence = 0.72f)

        val merged = LineMerge.merge(listOf(mlKitGarbage), listOf(unsureTamil))

        assertEquals(listOf(mlKitGarbage, unsureTamil), merged)
    }

    @Test
    fun realHindiAndSureTamilInTheSameSpotAreBothKept() {
        // Neither is garbage: the rule cannot know which is right, so both stay.
        val hindi = RecognizedLine("टेस्टा कुमारी", nameSpot, confidence = 0.82f)
        val tamil = RecognizedLine("டெஸ்டா குமாரி", nameSpot, confidence = 0.94f)

        assertEquals(listOf(hindi, tamil), LineMerge.merge(listOf(hindi), listOf(tamil)))
    }

    @Test
    fun linesWithNothingOnTopOfThemAreAlwaysKept() {
        val english = RecognizedLine("Valid Till: 20-01-2042", otherSpot, confidence = 0.84f)
        val lowTamil = RecognizedLine("மதுரை", nameSpot, confidence = 0.30f)

        assertEquals(listOf(english, lowTamil), LineMerge.merge(listOf(english), listOf(lowTamil)))
    }

    @Test
    fun mlKitLineWithoutConfidenceCountsAsReal() {
        assertEquals(LineMerge.MainLineKind.REAL, LineMerge.kindOf(RecognizedLine("TESTA KUMARI", nameSpot)))
    }

    @Test
    fun tamilLineWithoutConfidenceIsNeverSure() {
        assertEquals(false, LineMerge.isSurelyTamil(RecognizedLine("டெஸ்டா குமாரி", nameSpot)))
    }
}

package com.heybuddy.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.ocr.fakedocs.FakeDocuments
import com.heybuddy.ocr.fakedocs.SampleStyle
import com.heybuddy.ocr.fakedocs.TamilFakeDocuments
import com.heybuddy.ocr.measure.TextAccuracy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's reader (ML Kit + Tesseract, merged; spec 7.4a) on every fake sample: adding Tamil must
 * not lose any English or Hindi, Tamil must be read at least as well as measured in Task 2.3, and the
 * merge must never drop a line ML Kit was sure of (owner's safety rule). Fake data only.
 */
@RunWith(AndroidJUnit4::class)
class MergedTextReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mlKit = MlKitTextReader()
    private val tesseract = TesseractTextReader(context)
    private val merged = MergedTextReader(mlKit, tesseract)

    @After
    fun closeReaders() {
        merged.close()
    }

    @Test
    fun englishAndHindiAreAsGoodAsWithMlKitAlone() = runBlocking {
        for (sample in FakeDocuments.all()) {
            val mlKitLines = mlKit.read(sample.picture).lines
            val lines = merged.read(sample.picture).lines.map { line -> line.text }
            sample.picture.recycle()
            val text = lines.joinToString("\n")
            val english = sample.answer.readableLines.filter { line -> !TextAccuracy.containsDevanagari(line) }
            val hindi = sample.answer.readableLines.filter { line -> TextAccuracy.containsDevanagari(line) }

            val englishAccuracy = TextAccuracy.pageAccuracy(english, lines)
            assertTrue("${sample.name}: English $englishAccuracy", englishAccuracy >= MIN_ENGLISH_ACCURACY)
            if (hindi.isNotEmpty()) {
                val hindiAccuracy = TextAccuracy.pageAccuracy(hindi, lines)
                assertTrue("${sample.name}: Hindi $hindiAccuracy", hindiAccuracy >= MIN_HINDI_ACCURACY)
            }
            for (field in sample.answer.fields) {
                // As in TextReaderTest: the licence "00" is read as "O0", fixed by the fact rules (2.6).
                if (sample.style == SampleStyle.LICENCE_STYLE && field.label == "DL no.") continue
                assertTrue("${sample.name}: ${field.label} lost", TextAccuracy.fieldFound(field.value, text, field.ignoreSpaces))
            }
            assertNoSureLineDropped(sample.name, mlKitLines, lines)
        }
    }

    @Test
    fun tamilIsReadAsWellAsMeasured() = runBlocking {
        var accuracySum = 0.0
        var namesFound = 0
        var samples = 0
        for (maker in TamilFakeDocuments.makers()) {
            val sample = maker()
            val mlKitLines = mlKit.read(sample.picture).lines
            val lines = merged.read(sample.picture).lines.map { line -> line.text }
            sample.picture.recycle()
            val text = lines.joinToString("\n")
            val accuracy = TextAccuracy.pageAccuracy(sample.tamilLines, lines)
            assertTrue("${sample.name}: Tamil $accuracy", accuracy >= MIN_TAMIL_ACCURACY_PER_PAGE)
            for (field in sample.fields) {
                val found = TextAccuracy.fieldFound(field.value, text, field.ignoreSpaces)
                if (field.label == "Tamil name") {
                    if (found) namesFound++
                } else {
                    assertTrue("${sample.name}: ${field.label} not read", found)
                }
            }
            // The Task 2.2 danger: ML Kit's garbage for a Tamil address once gave this WRONG PIN.
            assertFalse("${sample.name}: wrong PIN kept", text.contains(WRONG_PIN_FROM_TAMIL_GARBAGE))
            assertNoSureLineDropped(sample.name, mlKitLines, lines)
            accuracySum += accuracy
            samples++
        }
        val average = accuracySum / samples
        assertTrue("Tamil average $average", average >= MIN_TAMIL_ACCURACY_AVERAGE)
        assertTrue("Tamil names $namesFound/$samples", namesFound >= MIN_TAMIL_NAMES_FOUND)
    }

    /** Owner rule: a line ML Kit was sure of (REAL) must still be there after the merge. */
    private fun assertNoSureLineDropped(sampleName: String, mlKitLines: List<RecognizedLine>, mergedTexts: List<String>) {
        for (line in mlKitLines) {
            if (LineMerge.kindOf(line) != LineMerge.MainLineKind.REAL) continue
            assertTrue("$sampleName: dropped \"${line.text}\"", line.text in mergedTexts)
        }
    }

    private companion object {
        /** As in TextReaderTest (Task 2.2): adding Tamil must not make English or Hindi worse. */
        const val MIN_ENGLISH_ACCURACY = 0.93
        const val MIN_HINDI_ACCURACY = 0.75

        /** Task 2.3, Tesseract fast: 95.4 % on average, the weakest page (address block with shadow) 67 %. */
        const val MIN_TAMIL_ACCURACY_AVERAGE = 0.92
        const val MIN_TAMIL_ACCURACY_PER_PAGE = 0.60

        /** Task 2.3: 15 of 16 Tamil names read exactly. */
        const val MIN_TAMIL_NAMES_FOUND = 15

        const val WRONG_PIN_FROM_TAMIL_GARBAGE = "647001"
    }
}

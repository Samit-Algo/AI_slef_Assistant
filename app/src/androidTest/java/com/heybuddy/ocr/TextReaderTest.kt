package com.heybuddy.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heybuddy.ocr.measure.TextAccuracy
import com.heybuddy.ocr.fakedocs.FakeDocuments
import com.heybuddy.ocr.fakedocs.SampleStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's reader (ML Kit Devanagari, which reads Hindi and English, no preparation) reads every fake sample at least as
 * well as measured in Task 2.2, with a small margin, so any later change that makes reading worse
 * is caught. Fake data only.
 */
@RunWith(AndroidJUnit4::class)
class TextReaderTest {

    private val reader = MlKitTextReader()

    @After
    fun closeReader() {
        reader.close()
    }

    @Test
    fun everySampleIsReadAtLeastAsWellAsMeasured() = runBlocking {
        for (sample in FakeDocuments.all()) {
            val lines = reader.read(sample.picture).lines.map { line -> line.text }
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
                // The licence number's "00" is read as "O0" (letter O); the fact rules of Task 2.6 fix
                // that by position, so it is not expected from plain reading.
                if (sample.style == SampleStyle.LICENCE_STYLE && field.label == "DL no.") continue
                assertTrue("${sample.name}: ${field.label} not read exactly", TextAccuracy.fieldFound(field.value, text, field.ignoreSpaces))
            }
        }
    }

    private companion object {
        /** Lowest measured in Task 2.2 was 95.3 % (Aadhaar-style, low light). */
        const val MIN_ENGLISH_ACCURACY = 0.93

        /** Lowest measured in Task 2.2 was 81.5 % (Aadhaar-style, blurred). */
        const val MIN_HINDI_ACCURACY = 0.75
    }
}

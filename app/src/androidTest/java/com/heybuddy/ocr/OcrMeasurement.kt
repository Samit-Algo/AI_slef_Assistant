package com.heybuddy.ocr

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.ocr.measure.PagePreparation
import com.heybuddy.ocr.measure.TextAccuracy
import com.heybuddy.ocr.fakedocs.AnswerKey
import com.heybuddy.ocr.fakedocs.FakeDocument
import com.heybuddy.ocr.fakedocs.FakeDocuments
import com.heybuddy.ocr.fakedocs.PhotoLook
import com.heybuddy.ocr.fakedocs.SampleStyle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The Task 2.2 measurement: a TOOL, not a pass/fail test. It reads every fake sample (and the owner's
 * real photos of them, if present) with each recognizer and each image preparation, and reports the
 * results as tables in the test output ("ocrReport"). Fake data only.
 *
 * Real photos (owner addition A): put on the phone as /data/local/tmp/heybuddy_real/<style>.jpg
 * (for example aadhaar_style.jpg); they are read with the test's shell rights and never copied.
 */
@RunWith(AndroidJUnit4::class)
class OcrMeasurement {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Saves the 15 fake samples as PNG files in the app's test cache, for the owner to look at. */
    @Test
    fun exportSamplesForTheOwner() {
        val folder = File(instrumentation.targetContext.cacheDir, SAMPLES_FOLDER)
        folder.deleteRecursively()
        folder.mkdirs()
        for (sample in FakeDocuments.all()) {
            File(folder, "${sample.name}.png").outputStream().use { output -> sample.picture.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output) }
            sample.picture.recycle()
        }
    }

    @Test
    fun measureReaderAndPreparations() = runBlocking {
        val reader = MlKitTextReader()
        val results = mutableListOf<Measured>()
        try {
            for (sample in FakeDocuments.all()) {
                val group = if (sample.look == PhotoLook.CLEAN) Group.CLEAN else Group.PHOTO_STYLE
                results.addAll(measureOne(sample.name, group, sample.picture, sample.answer, reader))
                sample.picture.recycle()
            }
            for (style in SampleStyle.entries) {
                val photo = loadRealPhoto(style) ?: continue
                val answer = FakeDocuments.make(style, PhotoLook.CLEAN).also { clean -> clean.picture.recycle() }.answer
                results.addAll(measureOne("real_${style.fileName}", Group.REAL_PHOTO, photo, answer, reader))
                photo.recycle()
            }
        } finally {
            reader.close()
        }
        instrumentation.sendStatus(0, Bundle().apply { putString(REPORT_KEY, Report.build(results)) })
    }

    /** Reads one picture with every preparation. */
    private suspend fun measureOne(
        name: String,
        group: Group,
        picture: Bitmap,
        answer: AnswerKey,
        reader: MlKitTextReader,
    ): List<Measured> {
        val measured = mutableListOf<Measured>()
        for (preparation in Preparation.entries) {
            val prepared = preparation.applyTo(picture)
            val start = SystemClock.elapsedRealtime()
            val page = reader.read(prepared)
            val millis = SystemClock.elapsedRealtime() - start
            if (prepared !== picture) prepared.recycle()
            measured.add(Measured.of(name, group, Recognizer.ML_KIT, preparation, page.lines, millis, answer))
        }
        return measured
    }

    private fun loadRealPhoto(style: SampleStyle): Bitmap? {
        val path = "$REAL_PHOTOS_FOLDER/${style.fileName}.jpg"
        val output = instrumentation.uiAutomation.executeShellCommand("cat $path")
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(output).use { stream -> stream.readBytes() }
        if (bytes.size < MIN_PHOTO_BYTES) return null
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    }

    private companion object {
        const val SAMPLES_FOLDER = "ocr_samples"
        const val REAL_PHOTOS_FOLDER = "/data/local/tmp/heybuddy_real"
        const val REPORT_KEY = "ocrReport"
        const val PNG_QUALITY = 100

        /** Anything smaller is "cat: no such file", not a photo. */
        const val MIN_PHOTO_BYTES = 1024
    }
}

/** Which samples a result belongs to. */
enum class Group(val label: String) {
    CLEAN("Clean (3)"),
    PHOTO_STYLE("Photo-style (12)"),
    REAL_PHOTO("Real photo"),
}

/**
 * Which recognizer produced a result. Task 2.2 also measured ML Kit's English-only model and a
 * merge of both; the owner chose ML Kit Devanagari alone. Task 2.3 adds the Tamil engine here.
 */
enum class Recognizer(val label: String) {
    ML_KIT("ML Kit (Devanagari + English)"),
}

/** The image preparations being compared (spec: measured, not guessed). */
enum class Preparation(val label: String) {
    NONE("none"),
    SCALED("scaled"),
    CONTRAST("grey + contrast"),
    SCALED_CONTRAST("scaled + contrast"),
    ;

    /** @return The prepared picture (the same object for NONE). */
    fun applyTo(picture: Bitmap): Bitmap {
        return when (this) {
            NONE -> picture
            SCALED -> PagePreparation.scaledForReading(picture)
            CONTRAST -> PagePreparation.greyWithStretchedContrast(picture)
            SCALED_CONTRAST -> {
                val scaled = PagePreparation.scaledForReading(picture)
                val result = PagePreparation.greyWithStretchedContrast(scaled)
                scaled.recycle()
                result
            }
        }
    }
}

/** One reading of one picture, scored against its answer key. */
class Measured(
    val sample: String,
    val group: Group,
    val recognizer: Recognizer,
    val preparation: Preparation,
    val englishAccuracy: Double,
    val hindiAccuracy: Double?,
    val fieldsFound: Map<String, Boolean>,
    val garbageLines: List<String>,
    val fieldLikeGarbage: List<String>,
    val millis: Long,
    val readLines: List<String>,
) {
    companion object {
        fun of(
            sample: String,
            group: Group,
            recognizer: Recognizer,
            preparation: Preparation,
            lines: List<RecognizedLine>,
            millis: Long,
            answer: AnswerKey,
        ): Measured {
            val readLines = lines.map { line -> line.text }
            val readText = readLines.joinToString("\n")
            val english = answer.readableLines.filter { line -> !TextAccuracy.containsDevanagari(line) }
            val hindi = answer.readableLines.filter { line -> TextAccuracy.containsDevanagari(line) }
            val garbage = TextAccuracy.unexplainedLines(readLines, answer.readableLines, ignored = listOf(FakeDocuments.WATERMARK))
            return Measured(
                sample = sample,
                group = group,
                recognizer = recognizer,
                preparation = preparation,
                englishAccuracy = TextAccuracy.pageAccuracy(english, readLines),
                hindiAccuracy = if (hindi.isEmpty()) null else TextAccuracy.pageAccuracy(hindi, readLines),
                fieldsFound = answer.fields.associate { field ->
                    fieldKind(field.label) to TextAccuracy.fieldFound(field.value, readText, field.ignoreSpaces)
                },
                garbageLines = garbage,
                fieldLikeGarbage = garbage.filter { line -> TextAccuracy.looksLikeField(line) },
                millis = millis,
                readLines = readLines,
            )
        }

        /** Aadhaar, PAN and DL numbers are reported together as "Number". */
        private fun fieldKind(label: String): String = if (label == "Name" || label == "DOB") label else "Number"
    }
}

/** Turns the results into the tables for the owner. */
private object Report {

    fun build(results: List<Measured>): String {
        val text = StringBuilder()
        for (group in Group.entries) {
            val inGroup = results.filter { result -> result.group == group }
            if (inGroup.isEmpty()) continue
            text.appendLine("### ${group.label}")
            text.appendLine("| Recognizer | Preparation | English chars | Hindi chars | Name | DOB | Number | Garbage lines | Field-like garbage | ms/page |")
            text.appendLine("|---|---|---|---|---|---|---|---|---|---|")
            for (recognizer in Recognizer.entries) {
                for (preparation in Preparation.entries) {
                    val rows = inGroup.filter { result -> result.recognizer == recognizer && result.preparation == preparation }
                    text.appendLine(row(recognizer, preparation, rows))
                }
            }
            text.appendLine()
        }
        text.appendLine(tamilGarbage(results))
        text.appendLine(details(results))
        text.appendLine(rawLines(results))
        return text.toString()
    }

    private fun row(recognizer: Recognizer, preparation: Preparation, rows: List<Measured>): String {
        val english = percent(rows.map { result -> result.englishAccuracy }.average())
        val hindiValues = rows.mapNotNull { result -> result.hindiAccuracy }
        val hindi = if (hindiValues.isEmpty()) "–" else percent(hindiValues.average())
        val name = found(rows, "Name")
        val dob = found(rows, "DOB")
        val number = found(rows, "Number")
        val garbage = String.format(Locale.ROOT, "%.1f", rows.map { result -> result.garbageLines.size }.average())
        val fieldLike = rows.sumOf { result -> result.fieldLikeGarbage.size }
        val millis = rows.map { result -> result.millis }.average().toLong()
        return "| ${recognizer.label} | ${preparation.label} | $english | $hindi | $name | $dob | $number | $garbage | $fieldLike | $millis |"
    }

    /** Owner addition B: does ML Kit turn the Tamil lines into text that looks like a field? */
    private fun tamilGarbage(results: List<Measured>): String {
        val text = StringBuilder("### Tamil lines read by ML Kit (no preparation; samples with Tamil lines)\n")
        val withTamil = results.filter { result ->
            result.preparation == Preparation.NONE &&
                !result.sample.contains(SampleStyle.PAN_STYLE.fileName)
        }
        for (result in withTamil) {
            val examples = result.fieldLikeGarbage.joinToString(" / ").ifEmpty { "none" }
            text.appendLine("- ${result.sample}, ${result.recognizer.label}: ${result.garbageLines.size} garbage lines; field-like: $examples")
        }
        return text.toString()
    }

    private fun details(results: List<Measured>): String {
        val text = StringBuilder("### Per sample (no preparation): English / Hindi character accuracy, fields found\n")
        val plain = results.filter { result -> result.preparation == Preparation.NONE }
        for (result in plain) {
            val hindi = result.hindiAccuracy?.let { value -> percent(value) } ?: "–"
            val fields = result.fieldsFound.entries.joinToString(" ") { (kind, ok) -> "$kind:${if (ok) "✓" else "✗"}" }
            text.appendLine("- ${result.sample}, ${result.recognizer.label}: ${percent(result.englishAccuracy)} / $hindi; $fields; ${result.millis} ms")
        }
        return text.toString()
    }

    /** What was actually read on the clean samples and the real photos (fake data only), to explain misses. */
    private fun rawLines(results: List<Measured>): String {
        val text = StringBuilder()
        text.appendLine("### Raw lines read (clean samples and real photos, no preparation)")
        val shown = results.filter { result ->
            result.preparation == Preparation.NONE && result.group != Group.PHOTO_STYLE
        }
        for (result in shown) {
            text.appendLine("- ${result.sample}, ${result.recognizer.label}: ${result.readLines.joinToString(" | ")}")
        }
        return text.toString()
    }

    private fun found(rows: List<Measured>, kind: String): String {
        val relevant = rows.mapNotNull { result -> result.fieldsFound[kind] }
        val hits = relevant.count { ok -> ok }
        return "$hits/${relevant.size}"
    }

    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * PERCENT)

    private const val PERCENT = 100
}

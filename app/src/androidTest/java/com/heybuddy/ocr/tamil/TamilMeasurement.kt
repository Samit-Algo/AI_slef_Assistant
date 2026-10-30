package com.heybuddy.ocr.tamil

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.ocr.MergedTextReader
import com.heybuddy.ocr.MlKitTextReader
import com.heybuddy.ocr.TesseractTextReader
import com.heybuddy.ocr.TextReader
import com.heybuddy.ocr.fakedocs.TamilFakeDocuments
import com.heybuddy.ocr.measure.TextAccuracy
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The Task 2.3 measurement: a TOOL, not a pass/fail test. Reads every Tamil fake sample with one
 * engine and reports Tamil accuracy, fields found, time, first-load time and peak extra memory.
 * The owner chose Tesseract (fast model); PaddleOCR and the "best" model were measured and removed
 * (results in spec 7.4a and docs/measurements/).
 *
 * Run ONE engine per `am instrument` call (for example `-e class ...TamilMeasurement#paddleOcr`), so
 * each starts in a fresh process: that makes the first-load time and the memory numbers honest.
 */
@RunWith(AndroidJUnit4::class)
class TamilMeasurement {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Saves the 16 Tamil fake samples as PNG files in the app's test cache, for the owner to look at. */
    @Test
    fun exportSamplesForTheOwner() {
        val folder = File(instrumentation.targetContext.cacheDir, SAMPLES_FOLDER)
        folder.deleteRecursively()
        folder.mkdirs()
        for (maker in TamilFakeDocuments.makers()) {
            val sample = maker()
            File(folder, "${sample.name}.png").outputStream().use { output -> sample.picture.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output) }
            sample.picture.recycle()
        }
    }

    @Test
    fun mlKitWithoutTamil() = measure("ML Kit Devanagari (no Tamil; baseline)") { MlKitTextReader() }

    @Test
    fun tesseractFast() = measure("Tesseract 5.5.1 + tam (fast), the app's reader") { TesseractTextReader(instrumentation.targetContext) }

    @Test
    fun merged() = measure("ML Kit + Tesseract, merged (the app's reader)") {
        MergedTextReader(MlKitTextReader(), TesseractTextReader(instrumentation.targetContext))
    }

    private fun measure(engineName: String, createEngine: () -> TextReader) = runBlocking {
        val makers = TamilFakeDocuments.makers()
        val firstPage = makers.first().invoke()
        val memory = PeakMemory()
        memory.start()
        val loadStart = SystemClock.elapsedRealtime()
        val engine = createEngine()
        // First-time loading = making the engine (models into memory) + reading the first page.
        engine.read(firstPage.picture)
        val firstLoadMillis = SystemClock.elapsedRealtime() - loadStart
        firstPage.picture.recycle()
        val results = mutableListOf<SampleResult>()
        try {
            for (maker in makers) {
                val sample = maker()
                val start = SystemClock.elapsedRealtime()
                val lines = engine.read(sample.picture).lines.map { line -> line.text }
                val millis = SystemClock.elapsedRealtime() - start
                val text = lines.joinToString("\n")
                results.add(
                    SampleResult(
                        name = sample.name,
                        tamilAccuracy = TextAccuracy.pageAccuracy(sample.tamilLines, lines),
                        fields = sample.fields.associate { field -> field.label to TextAccuracy.fieldFound(field.value, text, field.ignoreSpaces) },
                        millis = millis,
                        lines = lines,
                    ),
                )
                sample.picture.recycle()
            }
        } finally {
            engine.close()
        }
        val peakExtraMegabytes = memory.stop()
        val report = Report.build(engineName, results, firstLoadMillis, peakExtraMegabytes)
        instrumentation.sendStatus(0, Bundle().apply { putString(REPORT_KEY, report) })
    }

    private class SampleResult(
        val name: String,
        val tamilAccuracy: Double,
        val fields: Map<String, Boolean>,
        val millis: Long,
        val lines: List<String>,
    )

    /**
     * Highest resident memory (RSS) of the whole app process while the engine loads and reads,
     * minus what it used just before (with one page already in memory): the extra RAM a budget
     * phone must have free. It includes the one page being read at a time, as in the app.
     */
    private class PeakMemory {
        @Volatile
        private var running = false
        private var baseline = 0L
        private var peak = 0L
        private var watcher: Thread? = null

        fun start() {
            System.gc()
            baseline = residentBytes()
            peak = baseline
            running = true
            watcher = thread(name = "peak-memory") {
                while (running) {
                    peak = maxOf(peak, residentBytes())
                    Thread.sleep(SAMPLE_EVERY_MILLIS)
                }
            }
        }

        fun stop(): Double {
            running = false
            watcher?.join()
            peak = maxOf(peak, residentBytes())
            return (peak - baseline) / BYTES_PER_MEGABYTE
        }

        /** Second number in /proc/self/statm = resident pages. */
        private fun residentBytes(): Long {
            val pages = File("/proc/self/statm").readText().trim().split(" ")[1].toLong()
            return pages * Os.sysconf(OsConstants._SC_PAGESIZE)
        }
    }

    private object Report {
        fun build(engine: String, results: List<SampleResult>, firstLoadMillis: Long, peakExtraMegabytes: Double): String {
            val text = StringBuilder()
            text.appendLine("## $engine")
            text.appendLine("first load ${firstLoadMillis} ms; peak extra RAM ${format(peakExtraMegabytes)} MB")
            for (group in GROUPS) {
                val rows = results.filter { result -> group.second(result.name) }
                val accuracy = rows.map { result -> result.tamilAccuracy }.average() * PERCENT
                val names = rows.count { result -> result.fields["Tamil name"] == true }
                val pins = rows.count { result -> result.fields["PIN"] == true }
                val millis = rows.map { result -> result.millis }.average().toLong()
                text.appendLine("| ${group.first} | ${format(accuracy)}% | $names/${rows.size} | $pins/${rows.size} | $millis |")
            }
            text.appendLine("### per sample")
            for (result in results) {
                val fields = result.fields.entries.joinToString(" ") { (label, ok) -> "$label:${if (ok) "Y" else "N"}" }
                text.appendLine("- ${result.name}: ${format(result.tamilAccuracy * PERCENT)}% $fields ${result.millis} ms")
            }
            text.appendLine("### lines read (fake data)")
            for (result in results.filter { result -> result.name.endsWith("_clean") || result.name.endsWith("_tilted") }) {
                text.appendLine("- ${result.name}: ${result.lines.joinToString(" | ")}")
            }
            return text.toString()
        }

        private fun format(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

        private val GROUPS = listOf<Pair<String, (String) -> Boolean>>(
            "Certificate form (3)" to { name -> name.startsWith("tn_certificate") },
            "Name and address block (3)" to { name -> name.startsWith("tamil_address") },
            "Cards, Tamil lines (10)" to { name -> name.startsWith("aadhaar") || name.startsWith("licence") },
            "Clean only (4)" to { name -> name.endsWith("_clean") },
            "Photo-style (12)" to { name -> !name.endsWith("_clean") },
            "All (16)" to { _ -> true },
        )
    }

    private companion object {
        const val REPORT_KEY = "tamilReport"
        const val SAMPLES_FOLDER = "tamil_samples"
        const val PNG_QUALITY = 100
        const val SAMPLE_EVERY_MILLIS = 5L
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val PERCENT = 100.0
    }
}

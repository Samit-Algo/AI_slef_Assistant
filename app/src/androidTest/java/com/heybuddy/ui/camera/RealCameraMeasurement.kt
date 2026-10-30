package com.heybuddy.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.MainActivity
import com.heybuddy.ocr.LineMerge
import com.heybuddy.ocr.MlKitTextReader
import com.heybuddy.ocr.RecognizedLine
import com.heybuddy.ocr.TesseractTextReader
import com.heybuddy.ocr.fakedocs.FakeDocuments
import com.heybuddy.ocr.fakedocs.PhotoLook
import com.heybuddy.ocr.fakedocs.SampleField
import com.heybuddy.ocr.fakedocs.SampleStyle
import com.heybuddy.ocr.fakedocs.TamilFakeDocuments
import com.heybuddy.ocr.measure.TextAccuracy
import com.heybuddy.security.VaultLockState
import com.heybuddy.ui.theme.HeyBuddyTheme
import com.heybuddy.util.READING_LONG_SIDE_PIXELS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The real phone-camera test (owner, deferred from Tasks 2.2 and 2.3), through the NEW in-app
 * camera. A TOOL, not a pass/fail test, and it waits for the owner, so it runs ONLY when asked:
 *
 *   adb shell am instrument -w -e realCamera yes -e class com.heybuddy.ui.camera.RealCameraMeasurement \
 *     com.heybuddy.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The screen says which PRINTED fake sample to photograph and how. Each kept photo is measured in
 * memory (reading accuracy, fields, every line's confidence, the merge cut-offs, brightness and
 * sharpness) and then wiped. Nothing is saved; only numbers and the fake text go to the report.
 */
@RunWith(AndroidJUnit4::class)
class RealCameraMeasurement {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** One photo the owner is asked for. */
    private class Prompt(val sample: String, val how: String, val answer: Answer?)

    /** What is printed on a sample (null answer = a deliberately bad photo, only brightness/sharpness count). */
    private class Answer(val readable: List<String>, val tamil: List<String>, val fields: List<SampleField>)

    @Test
    fun photographThePrintedSamples() {
        assumeTrue("Run only on purpose: -e realCamera yes", InstrumentationRegistry.getArguments().getString(ARGUMENT) == "yes")
        val prompts = buildPrompts()
        val report = StringBuilder()
        val done = CountDownLatch(1)
        val lockState = VaultLockState()
        instrumentation.runOnMainSync { lockState.markUnlocked() }
        val mlKit = MlKitTextReader()
        val tamil = TesseractTextReader(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    HeyBuddyTheme {
                        MeasuringFlow(prompts, lockState, onPhoto = { index, jpeg -> measure(prompts[index], index, jpeg, mlKit, tamil, report) }, onDone = { done.countDown() })
                    }
                }
            }
            done.await(OWNER_TIME_LIMIT_MINUTES, TimeUnit.MINUTES)
        }
        mlKit.close()
        tamil.close()
        send(report.toString())
    }

    @Composable
    private fun MeasuringFlow(
        prompts: List<Prompt>,
        lockState: VaultLockState,
        onPhoto: suspend (Int, ByteArray) -> Unit,
        onDone: () -> Unit,
    ) {
        var index by remember { mutableIntStateOf(0) }
        var measuring by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val next = {
            measuring = false
            index++
            if (index >= prompts.size) onDone()
        }
        when {
            index >= prompts.size -> Message("All done, thank you. You can put the phone down.")
            measuring -> Message("Measuring photo ${index + 1} of ${prompts.size}…")
            else -> Box(modifier = Modifier.fillMaxSize()) {
                // A fresh camera screen for every photo, exactly like the app's.
                key(index) {
                    CameraRoute(
                        lockState = lockState,
                        onBeforeExternalScreen = {},
                        onPhotoKept = { jpeg ->
                            measuring = true
                            scope.launch {
                                onPhoto(index, jpeg)
                                next()
                            }
                        },
                        onChooseInstead = { next() },
                        onClose = { next() },
                    )
                }
                PromptBanner(prompts[index], index, prompts.size)
            }
        }
    }

    @Composable
    private fun PromptBanner(prompt: Prompt, index: Int, total: Int) {
        Box(modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(top = BANNER_TOP.dp, start = 16.dp, end = 16.dp)) {
            Text(
                text = "Photo ${index + 1} of $total: ${prompt.sample}\n${prompt.how}\n(✕ skips this photo)",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Color(BANNER_COLOUR), MaterialTheme.shapes.medium).padding(12.dp),
            )
        }
    }

    @Composable
    private fun Message(text: String) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(text = text, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }

    // ---- Measuring one photo (memory only) ----

    private suspend fun measure(prompt: Prompt, index: Int, jpeg: ByteArray, mlKit: MlKitTextReader, tamil: TesseractTextReader, report: StringBuilder) {
        try {
            val decoded = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
            val quality = PhotoProcessor.measure(decoded)
            // The app reads pages at this size (Task 2.2).
            val page = scaledForReading(decoded)
            val mlKitLines = mlKit.read(page).lines
            val tamilLines = tamil.read(page).lines
            if (page !== decoded) page.recycle()
            decoded.recycle()
            val line = describe(prompt, index, jpeg.size, quality, mlKitLines, tamilLines)
            report.append(line)
            send(line)
        } finally {
            jpeg.fill(0)
        }
    }

    private fun describe(prompt: Prompt, index: Int, bytes: Int, quality: FrameQuality, mlKitLines: List<RecognizedLine>, tamilLines: List<RecognizedLine>): String {
        val text = StringBuilder("##### ${index + 1}. ${prompt.sample} — ${prompt.how}\n")
        text.append(String.format(Locale.ROOT, "page %d KB; brightness %.0f; sharpness %.0f; photo hint %s\n", bytes / BYTES_PER_KB, quality.brightness, quality.sharpness, PhotoCheck.hintFor(quality)))
        val answer = prompt.answer
        val merged = LineMerge.merge(mlKitLines, tamilLines)
        val mergedTexts = merged.map { line -> line.text }
        if (answer != null) {
            val english = answer.readable.filter { line -> !TextAccuracy.containsDevanagari(line) }
            val hindi = answer.readable.filter { line -> TextAccuracy.containsDevanagari(line) }
            text.append("merged: English ${percent(english, mergedTexts)}, Hindi ${percent(hindi, mergedTexts)}, Tamil ${percent(answer.tamil, mergedTexts)}\n")
            val joined = mergedTexts.joinToString("\n")
            text.append("fields: " + answer.fields.joinToString(" ") { field -> "${field.label}:${if (TextAccuracy.fieldFound(field.value, joined, field.ignoreSpaces)) "Y" else "N"}" } + "\n")
            text.append(mergeCheck(answer, mlKitLines, tamilLines, merged))
        }
        for (line in mlKitLines) text.append(lineRow("M", line))
        for (line in tamilLines) text.append(lineRow("T", line))
        return text.toString()
    }

    /** Merge cut-offs 0.5 / 0.7 / 0.8 on real photos: real lines dropped (must be none) and garbage kept. */
    private fun mergeCheck(answer: Answer, mlKitLines: List<RecognizedLine>, tamilLines: List<RecognizedLine>, merged: List<RecognizedLine>): String {
        val expected = answer.readable + answer.tamil
        val isReal = { line: RecognizedLine -> expected.any { wanted -> TextAccuracy.similarity(wanted, line.text) >= REAL_LINE_SIMILARITY } }
        val droppedReal = (mlKitLines + tamilLines).filter { line -> line !in merged && isReal(line) }
        val keptGarbage = merged.count { line -> !isReal(line) }
        val dropped = droppedReal.joinToString(" | ") { line -> line.text }.ifEmpty { "none" }
        return "merge check: real lines dropped: $dropped; garbage lines kept: $keptGarbage\n"
    }

    private fun lineRow(engine: String, line: RecognizedLine): String {
        val box = line.box
        return String.format(Locale.ROOT, "%s %.2f [%d,%d,%d,%d] %s\n", engine, line.confidence ?: -1f, box?.left, box?.top, box?.right, box?.bottom, line.text)
    }

    private fun percent(expected: List<String>, read: List<String>): String {
        if (expected.isEmpty()) return "–"
        return String.format(Locale.ROOT, "%.1f%%", TextAccuracy.pageAccuracy(expected, read) * PERCENT)
    }

    private fun scaledForReading(picture: Bitmap): Bitmap {
        val factor = READING_LONG_SIDE_PIXELS.toFloat() / max(picture.width, picture.height)
        if (factor >= 1f) return picture
        return picture.scale((picture.width * factor).roundToInt(), (picture.height * factor).roundToInt())
    }

    private fun send(text: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString(REPORT_KEY, text) })
    }

    // ---- The 12 photos ----

    private fun buildPrompts(): List<Prompt> {
        val prompts = mutableListOf<Prompt>()
        for (style in SampleStyle.entries) {
            val card = FakeDocuments.make(style, PhotoLook.CLEAN)
            card.picture.recycle()
            val answer = Answer(card.answer.readableLines, card.answer.tamilLines, card.answer.fields)
            prompts.add(Prompt("${style.fileName}_clean", GOOD, answer))
            prompts.add(Prompt("${style.fileName}_clean", HARDER, answer))
        }
        val certificate = TamilFakeDocuments.certificateStyle(PhotoLook.CLEAN)
        certificate.picture.recycle()
        val certificateAnswer = Answer(emptyList(), certificate.tamilLines, certificate.fields)
        prompts.add(Prompt("tn_certificate_style_clean", "$GOOD Tap \"Page\" first.", certificateAnswer))
        prompts.add(Prompt("tn_certificate_style_clean", "$HARDER Tap \"Page\" first.", certificateAnswer))
        val block = TamilFakeDocuments.nameAndAddressBlock(PhotoLook.CLEAN)
        block.picture.recycle()
        val blockAnswer = Answer(emptyList(), block.tamilLines, block.fields)
        prompts.add(Prompt("tamil_address_block_clean", GOOD, blockAnswer))
        prompts.add(Prompt("tamil_address_block_clean", HARDER, blockAnswer))
        prompts.add(Prompt("any sample", "BLURRY on purpose: move the phone while you press the button.", null))
        prompts.add(Prompt("any sample", "DARK on purpose: turn the room light off (no torch).", null))
        return prompts
    }

    private companion object {
        const val ARGUMENT = "realCamera"
        const val REPORT_KEY = "realCameraReport"
        const val GOOD = "GOOD: room light, phone straight above, document filling the frame."
        const val HARDER = "HARDER: hold the phone at a slight angle, with your hand's shadow on part of it."
        const val OWNER_TIME_LIMIT_MINUTES = 40L
        const val REAL_LINE_SIMILARITY = 0.8
        const val BYTES_PER_KB = 1024
        const val PERCENT = 100.0
        const val BANNER_TOP = 64
        const val BANNER_COLOUR = 0xCC1E3A8A
    }
}

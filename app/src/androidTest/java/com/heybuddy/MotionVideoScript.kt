package com.heybuddy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.DocumentSummary
import com.heybuddy.data.DocumentType
import com.heybuddy.settings.AutoLockChoice
import com.heybuddy.settings.ThemeChoice
import com.heybuddy.ui.VaultScreen
import com.heybuddy.ui.VaultScreenHost
import com.heybuddy.ui.adddocument.AddDocumentActions
import com.heybuddy.ui.adddocument.AddDocumentScreen
import com.heybuddy.ui.adddocument.AddDocumentUiState
import com.heybuddy.ui.adddocument.AddStage
import com.heybuddy.ui.growsFromCard
import com.heybuddy.ui.onboarding.OnboardingScreen
import com.heybuddy.ui.settings.SettingsActions
import com.heybuddy.ui.settings.SettingsScreen
import com.heybuddy.ui.settings.SettingsUiState
import com.heybuddy.ui.theme.HeyBuddyTheme
import com.heybuddy.ui.theme.LocalThemeCrossfade
import com.heybuddy.ui.theme.ThemeCrossfade
import com.heybuddy.ui.theme.ThemeCrossfadeHost
import com.heybuddy.ui.unlock.UnlockScreen
import com.heybuddy.ui.unlock.UnlockUiState
import com.heybuddy.ui.vault.VaultListScreen
import com.heybuddy.ui.viewer.ViewerActions
import com.heybuddy.ui.viewer.ViewerScreen
import com.heybuddy.ui.viewer.ViewerStage
import com.heybuddy.ui.viewer.ViewerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays the main motion flows (spec 5.14, Task 1.10m) in real time, for the owner's screen video
 * and the frame check. It only plays; the video is recorded separately while it runs. It reports the
 * app's frame statistics (`dumpsys gfxinfo`) for each flow in the test output ("frameStats").
 *
 * Optional: `-e startDelaySeconds 10` waits longer before starting, so a screen recorder can be
 * started on the phone first.
 *
 * TEST WINDOW ONLY and SAMPLE DATA ONLY: FLAG_SECURE is turned off here (otherwise the video would
 * be black); the real app is never affected, and no real document is ever shown.
 */
@RunWith(AndroidJUnit4::class)
class MotionVideoScript {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Everything the script changes, step by step. */
    private class DemoState {
        var scene by mutableStateOf(Scene.UNLOCK)
        var unlockState by mutableStateOf(UnlockUiState())
        var unlocked by mutableStateOf(false)
        var vaultScreen by mutableStateOf<VaultScreen>(VaultScreen.List)
        var dark by mutableStateOf(false)

        // Captured from the screen, so the script changes the theme exactly as Settings does.
        var themeCrossfade: ThemeCrossfade? = null
        var screenScope: CoroutineScope? = null
    }

    private enum class Scene { UNLOCK, VAULT, LOADING, ONBOARDING }

    @Test
    fun playTheMainFlows() {
        val state = DemoState()
        val samplePicture = drawSampleCard()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { DemoHost(state, samplePicture) }
            }
            pause(startDelayMillis())
            // A first, unmeasured pass, so each screen has been built once (as in real use); a
            // freshly installed app's very first frames say little about how it feels later.
            playEverything(state)
            resetForSecondPass(state)
            // Frame statistics per part, so any stutter can be traced to one flow. Read during the
            // test: the app's process (and its frame counters) ends when the test finishes.
            val report = StringBuilder()
            measure(report, "unlock") { playUnlock(state) }
            measure(report, "card to viewer and back") { playViewer(state) }
            measure(report, "saved check") { playSaved(state) }
            measure(report, "settings theme cross-fade") { playTheme(state) }
            measure(report, "loading") { playLoading(state) }
            measure(report, "onboarding") { playOnboarding(state) }
            // Reported in the test's output (the profiling build cannot be read with run-as).
            instrumentation.sendStatus(0, Bundle().apply { putString(FRAME_STATS_KEY, report.toString()) })
        }
    }

    private fun playEverything(state: DemoState) {
        playUnlock(state)
        playViewer(state)
        playSaved(state)
        playTheme(state)
        playLoading(state)
        playOnboarding(state)
    }

    private fun resetForSecondPass(state: DemoState) {
        change {
            state.scene = Scene.UNLOCK
            state.unlockState = UnlockUiState()
            state.unlocked = false
            state.vaultScreen = VaultScreen.List
        }
        pause(STEP_MILLIS)
    }

    private fun measure(report: StringBuilder, part: String, play: () -> Unit) {
        val packageName = instrumentation.targetContext.packageName
        runShell("dumpsys gfxinfo $packageName reset")
        play()
        report.appendLine("== $part ==")
        report.append(runShell("dumpsys gfxinfo $packageName"))
    }

    private fun startDelayMillis(): Long {
        val seconds = InstrumentationRegistry.getArguments().getString(START_DELAY_ARGUMENT)?.toLongOrNull()
        return if (seconds != null) seconds * MILLIS_PER_SECOND else START_MILLIS
    }

    /** Runs a shell command with the test's own shell rights and returns what it printed. */
    private fun runShell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }

    private fun playUnlock(state: DemoState) {
        // A real failure: the lock shakes (with a reject haptic).
        change { state.unlockState = UnlockUiState(errorMessage = R.string.unlock_error_too_many_tries, realFailureCount = 1) }
        pause(STEP_MILLIS)
        // A cancel: only the calm message, no shake.
        change { state.unlockState = UnlockUiState(errorMessage = R.string.unlock_error_cancelled, realFailureCount = 1) }
        pause(STEP_MILLIS)
        // Success: the lock springs open, then the vault fades in.
        change { state.unlocked = true }
        pause(SHORT_MILLIS)
        change { state.scene = Scene.VAULT }
        pause(STEP_MILLIS)
    }

    private fun playViewer(state: DemoState) {
        // The card grows into the viewer, then shrinks back into its card.
        change { state.vaultScreen = VaultScreen.Viewer(SAMPLE_DOCUMENT_ID) }
        pause(STEP_MILLIS)
        change { state.vaultScreen = VaultScreen.List }
        pause(STEP_MILLIS)
    }

    private fun playSaved(state: DemoState) {
        // The success check after saving.
        change { state.vaultScreen = VaultScreen.AddDocument }
        pause(STEP_MILLIS)
    }

    private fun playTheme(state: DemoState) {
        // Settings: the theme cross-fades to dark and back.
        change { state.vaultScreen = VaultScreen.Settings }
        pause(SHORT_MILLIS)
        changeTheme(state, dark = true)
        pause(STEP_MILLIS)
        changeTheme(state, dark = false)
        pause(STEP_MILLIS)
    }

    private fun playLoading(state: DemoState) {
        // The loading outline: two sweeps with a rest in between.
        change { state.scene = Scene.LOADING }
        pause(LOADING_MILLIS)
    }

    private fun playOnboarding(state: DemoState) {
        change { state.scene = Scene.ONBOARDING }
        pause(STEP_MILLIS)
        swipeLeft()
        pause(STEP_MILLIS)
        swipeLeft()
        pause(STEP_MILLIS)
    }

    @Composable
    private fun DemoHost(state: DemoState, samplePicture: Bitmap) {
        HeyBuddyTheme(darkTheme = state.dark) {
            when (state.scene) {
                Scene.UNLOCK -> UnlockScreen(state.unlockState, unlockSucceeded = state.unlocked, onUnlockClick = {})
                Scene.VAULT -> ThemeCrossfadeHost {
                    CaptureThemeCrossfade(state)
                    VaultScreenHost(screen = state.vaultScreen) { shown -> VaultSample(shown, samplePicture) }
                }
                Scene.LOADING -> ViewerScreen(ViewerUiState(title = "Sample certificate"), noViewerActions())
                Scene.ONBOARDING -> OnboardingScreen(onFinished = {})
            }
        }
    }

    @Composable
    private fun CaptureThemeCrossfade(state: DemoState) {
        val crossfade = LocalThemeCrossfade.current
        val scope = rememberCoroutineScope()
        SideEffect {
            state.themeCrossfade = crossfade
            state.screenScope = scope
        }
    }

    /** Changes the theme the way Settings does: through the cross-fade. */
    private fun changeTheme(state: DemoState, dark: Boolean) {
        change {
            val crossfade = checkNotNull(state.themeCrossfade)
            checkNotNull(state.screenScope).launch { crossfade.change { state.dark = dark } }
        }
    }

    @Composable
    private fun VaultSample(screen: VaultScreen, samplePicture: Bitmap) {
        when (screen) {
            VaultScreen.List -> VaultListScreen(documents = sampleDocuments(), onOpenDocument = {}, onAddDocument = {})
            is VaultScreen.Viewer -> ViewerScreen(
                uiState = ViewerUiState(
                    stage = ViewerStage.READY,
                    title = "Sample Aadhaar card",
                    documentPageCount = 2,
                    picture = samplePicture,
                ),
                actions = noViewerActions(),
                modifier = Modifier.growsFromCard(screen.documentId),
            )
            VaultScreen.AddDocument -> AddDocumentScreen(
                uiState = AddDocumentUiState(stage = AddStage.SAVED),
                actions = AddDocumentActions({}, {}, {}, {}, {}, {}, {}, {}),
            )
            VaultScreen.Settings -> SettingsScreen(
                theme = ThemeChoice.FOLLOW_PHONE,
                autoLock = AutoLockChoice.TWO_MINUTES,
                uiState = SettingsUiState(),
                versionText = "Version 0.1.0 (1)",
                actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}),
            )
        }
    }

    private fun noViewerActions(): ViewerActions = ViewerActions({}, {}, {}, {}, {}, {}, {}, {})

    /** Fake documents only. */
    private fun sampleDocuments(): List<DocumentSummary> {
        val added = SAMPLE_DATE_MILLIS
        return listOf(
            DocumentSummary(SAMPLE_DOCUMENT_ID, "Sample Aadhaar card", DocumentType.AADHAAR, pageCount = 2, createdAt = added, updatedAt = added),
            DocumentSummary("sample-2", "Sample certificate", DocumentType.CERTIFICATE, pageCount = 1, createdAt = added, updatedAt = added),
            DocumentSummary("sample-3", "Sample photo", DocumentType.PHOTO, pageCount = 1, createdAt = added, updatedAt = added),
        )
    }

    /** A fake card picture with "SAMPLE FRONT", nothing personal. */
    private fun drawSampleCard(): Bitmap {
        val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(30, 90, 160))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(255, 200, 0)
        canvas.drawCircle(350f, 400f, 150f, paint)
        paint.color = Color.WHITE
        paint.textSize = 64f
        canvas.drawText("SAMPLE FRONT", 560f, 420f, paint)
        return bitmap
    }

    private fun change(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun pause(millis: Long) {
        Thread.sleep(millis)
    }

    /** A finger swipe from right to left across the middle of the screen, like a person would do. */
    private fun swipeLeft() {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val y = metrics.heightPixels / 2f
        val startX = metrics.widthPixels * SWIPE_START
        val endX = metrics.widthPixels * SWIPE_END
        val downTime = SystemClock.uptimeMillis()
        sendTouch(downTime, downTime, MotionEvent.ACTION_DOWN, startX, y)
        for (step in 1..SWIPE_STEPS) {
            val x = startX + (endX - startX) * step / SWIPE_STEPS
            sendTouch(downTime, downTime + step * SWIPE_STEP_MILLIS, MotionEvent.ACTION_MOVE, x, y)
        }
        sendTouch(downTime, downTime + (SWIPE_STEPS + 1) * SWIPE_STEP_MILLIS, MotionEvent.ACTION_UP, endX, y)
    }

    private fun sendTouch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private companion object {
        const val START_MILLIS = 1_500L
        const val START_DELAY_ARGUMENT = "startDelaySeconds"
        const val MILLIS_PER_SECOND = 1_000L
        const val FRAME_STATS_KEY = "frameStats"
        const val STEP_MILLIS = 2_200L
        const val SHORT_MILLIS = 900L
        const val LOADING_MILLIS = 3_600L
        const val SAMPLE_DOCUMENT_ID = "sample-1"
        const val SAMPLE_DATE_MILLIS = 1_793_120_400_000L
        const val SAMPLE_WIDTH = 1200
        const val SAMPLE_HEIGHT = 800
        const val SWIPE_START = 0.8f
        const val SWIPE_END = 0.2f
        const val SWIPE_STEPS = 12
        const val SWIPE_STEP_MILLIS = 16L
    }
}

package com.heybuddy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.data.DisplayNameProblem
import com.heybuddy.data.DocumentType
import com.heybuddy.ui.adddocument.AddDocumentActions
import com.heybuddy.ui.adddocument.AddDocumentFailure
import com.heybuddy.ui.adddocument.AddDocumentScreen
import com.heybuddy.ui.adddocument.AddDocumentUiState
import com.heybuddy.ui.adddocument.AddStage
import com.heybuddy.ui.adddocument.DraftPage
import com.heybuddy.ui.adddocument.labelFor
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.DocumentCard
import com.heybuddy.ui.components.EmptyState
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.QuietButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.components.SecondaryButton
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.HeyBuddyTheme
import com.heybuddy.ui.unlock.NoScreenLockScreen
import com.heybuddy.ui.unlock.UnlockScreen
import com.heybuddy.ui.unlock.UnlockUiState
import com.heybuddy.data.DocumentSummary
import com.heybuddy.data.TestVault
import com.heybuddy.settings.AutoLockChoice
import com.heybuddy.settings.ThemeChoice
import com.heybuddy.ui.onboarding.OnboardingScreen
import com.heybuddy.ui.settings.DeleteAllDialogState
import com.heybuddy.ui.settings.LicenceEntry
import com.heybuddy.ui.settings.LicenceTextScreen
import com.heybuddy.ui.settings.SettingsActions
import com.heybuddy.ui.settings.SettingsScreen
import com.heybuddy.ui.settings.SettingsUiState
import com.heybuddy.ui.vault.VaultListScreen
import com.heybuddy.ui.viewer.PdfPageRenderer
import com.heybuddy.ui.viewer.RenameDialogState
import com.heybuddy.ui.viewer.ViewerActions
import com.heybuddy.ui.viewer.ViewerScreen
import com.heybuddy.ui.viewer.ViewerStage
import com.heybuddy.ui.viewer.ViewerUiState
import com.heybuddy.util.MAX_PAGES_PER_DOCUMENT
import com.heybuddy.util.THUMBNAIL_MAX_PIXELS
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.ui.res.stringResource

/**
 * NOT a normal test: takes full-screen screenshots of every screen in light and dark, for the
 * owner's design review (Task 1.8a). Run it on its own; it is skipped from the regular test run.
 *
 * Every screen uses FAKE sample data only. The theme is forced inside the app, so no phone setting
 * (dark mode, screen lock) is changed. Screenshots go to the app's private cache, then `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class DesignScreenshots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val outputDir = File(instrumentation.targetContext.cacheDir, "design_screenshots")

    @Test
    fun captureEveryScreenInLightAndDark() {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val samplePreview = drawSamplePreview()
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "01_unlock" to { UnlockScreen(UnlockUiState(showNoFingerprintNote = true), unlockSucceeded = false, onUnlockClick = {}) },
            "02_unlock_error" to { UnlockScreen(UnlockUiState(errorMessage = com.heybuddy.R.string.unlock_error_cancelled), unlockSucceeded = false, onUnlockClick = {}) },
            "03_unlock_success" to { UnlockScreen(UnlockUiState(), unlockSucceeded = true, onUnlockClick = {}) },
            "04_no_screen_lock" to { NoScreenLockScreen() },
            "06_add_choose" to { AddScreenSample(AddDocumentUiState()) },
            "07_add_ready" to {
                AddScreenSample(AddDocumentUiState(stage = AddStage.READY, pages = samplePages(samplePreview, 1), documentType = DocumentType.PHOTO, name = "Sample photo"))
            },
            "08_add_ready_name_error" to {
                AddScreenSample(AddDocumentUiState(stage = AddStage.READY, pages = samplePages(samplePreview, 1), documentType = DocumentType.PHOTO, name = "", nameProblem = DisplayNameProblem.EMPTY))
            },
            "09_add_wrong_type" to { AddScreenSample(AddDocumentUiState(errorMessage = AddDocumentFailure.WRONG_TYPE.message)) },
            "10_add_saving" to { AddScreenSample(AddDocumentUiState(stage = AddStage.SAVING)) },
            "11_add_saved" to { AddScreenSample(AddDocumentUiState(stage = AddStage.SAVED)) },
            "12_components" to { ComponentSheet() },
        )
        captureAll(outputDir, screens)
    }

    /** Task 1.8b: the vault list and the viewer, with fake sample documents only. */
    @Test
    fun captureVaultListAndViewer() {
        val folder = File(instrumentation.targetContext.cacheDir, "design_screenshots_1_8b")
        folder.deleteRecursively()
        folder.mkdirs()
        val samplePhoto = drawSamplePreview()
        val samplePdfPage = renderSamplePdfPage(pageIndex = 1)
        val viewingPhoto = ViewerUiState(stage = ViewerStage.READY, title = "Sample photo", documentPageCount = 1, picture = samplePhoto)
        val viewingPdf = ViewerUiState(
            stage = ViewerStage.READY,
            title = "Sample certificate",
            documentPageCount = 1,
            isPdf = true,
            picture = samplePdfPage,
            pageIndex = 1,
            pageCount = 2,
        )
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "01_list_3_documents" to { VaultListScreen(documents = sampleDocuments(), onOpenDocument = {}, onAddDocument = {}) },
            "02_list_empty" to { VaultListScreen(documents = emptyList(), onOpenDocument = {}, onAddDocument = {}) },
            "03_viewer_opening" to { ViewerScreen(ViewerUiState(title = "Sample photo"), noViewerActions()) },
            "04_viewer_photo" to { ViewerScreen(viewingPhoto, noViewerActions()) },
            "05_viewer_pdf_page_2" to { ViewerScreen(viewingPdf, noViewerActions()) },
            "06_viewer_rename_dialog" to {
                ViewerScreen(viewingPhoto.copy(rename = RenameDialogState(text = "Sample photo")), noViewerActions())
            },
            "07_viewer_rename_error" to {
                ViewerScreen(viewingPhoto.copy(rename = RenameDialogState(text = "", problem = DisplayNameProblem.EMPTY)), noViewerActions())
            },
            "08_viewer_cannot_open" to {
                ViewerScreen(
                    ViewerUiState(stage = ViewerStage.FAILED, title = "Sample photo", errorMessage = com.heybuddy.R.string.viewer_error_unreadable),
                    noViewerActions(),
                )
            },
            "09_viewer_confirm_needed" to {
                ViewerScreen(
                    ViewerUiState(stage = ViewerStage.FAILED, title = "Sample photo", errorMessage = com.heybuddy.R.string.viewer_error_confirm, canRetry = true),
                    noViewerActions(),
                )
            },
        )
        captureAll(folder, screens)
    }

    @Test
    fun captureSettingsOnboardingAndDeleteDialogs() {
        val folder = File(instrumentation.targetContext.cacheDir, "design_screenshots_1_10")
        folder.deleteRecursively()
        folder.mkdirs()
        val viewingPhoto = ViewerUiState(stage = ViewerStage.READY, title = "Sample photo", documentPageCount = 1, picture = drawSamplePreview())
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "01_onboarding_card1" to { OnboardingScreen(onFinished = {}) },
            "02_onboarding_card2" to { OnboardingScreen(onFinished = {}, initialPage = 1) },
            "03_onboarding_card3_warning" to { OnboardingScreen(onFinished = {}, initialPage = 2) },
            "04_list_with_settings_button" to { VaultListScreen(documents = sampleDocuments(), onOpenDocument = {}, onAddDocument = {}) },
            "12_list_empty_header_at_top" to { VaultListScreen(documents = emptyList(), onOpenDocument = {}, onAddDocument = {}) },
            "05_settings" to { SettingsSample(SettingsUiState()) },
            "06_settings_nothing_deleted" to { SettingsSample(SettingsUiState(notConfirmedNotice = true)) },
            "07_delete_all_dialog_empty" to { SettingsSample(SettingsUiState(deleteDialog = DeleteAllDialogState())) },
            "08_delete_all_dialog_typed" to { SettingsSample(SettingsUiState(deleteDialog = DeleteAllDialogState(typed = "DELETE"))) },
            "09_licence_text" to { LicenceTextScreen(entry = LicenceEntry.SQLCIPHER, onBack = {}) },
            "10_viewer_delete_dialog" to { ViewerScreen(viewingPhoto.copy(deleteConfirmOpen = true), noViewerActions()) },
            "11_viewer_deleting" to { ViewerScreen(ViewerUiState(stage = ViewerStage.READY, title = "Sample photo", deleting = true), noViewerActions()) },
        )
        captureAll(folder, screens)
    }

    /** Task 1.10p: documents with several pages, with fake sample pages only. */
    @Test
    fun captureMultiPageScreens() {
        val folder = File(instrumentation.targetContext.cacheDir, "design_screenshots_1_10p")
        folder.deleteRecursively()
        folder.mkdirs()
        val front = drawSampleCard(Color.rgb(30, 90, 160), "SAMPLE FRONT")
        val back = drawSampleCard(Color.rgb(90, 100, 110), "SAMPLE BACK")
        val threePages = listOf(
            DraftPage("page-1", thumbnailOf(front), isPdf = false),
            DraftPage("page-2", thumbnailOf(back), isPdf = false),
            DraftPage("page-3", thumbnail = null, isPdf = true),
        )
        val tenPages = List(MAX_PAGES_PER_DOCUMENT) { index -> DraftPage("page-$index", thumbnailOf(front), isPdf = false) }
        val viewingBack = ViewerUiState(
            stage = ViewerStage.READY,
            title = "Aadhaar card",
            documentPageCount = 2,
            documentPageIndex = 1,
            picture = back,
        )
        val viewingPdfPage = ViewerUiState(
            stage = ViewerStage.READY,
            title = "Sample certificate",
            documentPageCount = 3,
            documentPageIndex = 2,
            isPdf = true,
            picture = renderSamplePdfPage(pageIndex = 1),
            pageIndex = 1,
            pageCount = 2,
        )
        val ready = AddDocumentUiState(stage = AddStage.READY, pages = threePages, documentType = DocumentType.AADHAAR, name = "Aadhaar card")
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "01_add_choose" to { AddScreenSample(AddDocumentUiState()) },
            "02_add_three_pages" to { AddScreenSample(ready) },
            "03_add_ten_pages_limit" to {
                AddScreenSample(ready.copy(pages = tenPages, errorMessage = AddDocumentFailure.TOO_MANY_PAGES.message))
            },
            "04_add_reading" to { AddScreenSample(AddDocumentUiState(stage = AddStage.READING)) },
            "05_list_with_page_count" to { VaultListScreen(documents = sampleDocuments(), onOpenDocument = {}, onAddDocument = {}) },
            "06_viewer_page_2_of_2" to { ViewerScreen(viewingBack, noViewerActions()) },
            "07_viewer_pdf_inside_3_pages" to { ViewerScreen(viewingPdfPage, noViewerActions()) },
            "08_viewer_add_page_choice" to { ViewerScreen(viewingBack.copy(addPageChoiceOpen = true), noViewerActions()) },
            "09_viewer_remove_page_dialog" to { ViewerScreen(viewingBack.copy(removePageConfirmOpen = true), noViewerActions()) },
            "10_viewer_adding_pages" to { ViewerScreen(viewingBack.copy(picture = null, addingPages = true), noViewerActions()) },
            "11_viewer_limit_notice" to {
                ViewerScreen(viewingBack.copy(documentPageCount = MAX_PAGES_PER_DOCUMENT, notice = AddDocumentFailure.TOO_MANY_PAGES.message), noViewerActions())
            },
        )
        captureAll(folder, screens)
    }

    /** Task 1.10m: the motion states that can be seen in a still picture (fake sample data only). */
    @Test
    fun captureMotionScreens() {
        val folder = File(instrumentation.targetContext.cacheDir, "design_screenshots_1_10m")
        folder.deleteRecursively()
        folder.mkdirs()
        val renamed = ViewerUiState(
            stage = ViewerStage.READY,
            title = "Sample photo",
            documentPageCount = 1,
            picture = drawSamplePreview(),
            successNotice = com.heybuddy.R.string.viewer_renamed,
        )
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "01_unlock_real_failure" to {
                UnlockScreen(UnlockUiState(errorMessage = com.heybuddy.R.string.unlock_error_too_many_tries, realFailureCount = 1), unlockSucceeded = false, onUnlockClick = {})
            },
            "02_unlock_cancelled_calm" to {
                UnlockScreen(UnlockUiState(errorMessage = com.heybuddy.R.string.unlock_error_cancelled), unlockSucceeded = false, onUnlockClick = {})
            },
            "03_loading_outline" to { AddScreenSample(AddDocumentUiState(stage = AddStage.READING)) },
            "04_viewer_opening_outline" to { ViewerScreen(ViewerUiState(title = "Sample photo"), noViewerActions()) },
            "05_saved_check" to { AddScreenSample(AddDocumentUiState(stage = AddStage.SAVED)) },
            "06_viewer_name_changed" to { ViewerScreen(renamed, noViewerActions()) },
            "07_onboarding_dots_card2" to { OnboardingScreen(onFinished = {}, initialPage = 1) },
            "08_settings_licences_closed" to { SettingsSample(SettingsUiState()) },
        )
        captureAll(folder, screens)
    }

    @Composable
    private fun SettingsSample(state: SettingsUiState) {
        SettingsScreen(
            theme = ThemeChoice.FOLLOW_PHONE,
            autoLock = AutoLockChoice.TWO_MINUTES,
            uiState = state,
            versionText = "Version 0.1.0 (1)",
            actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}),
        )
    }

    private fun captureAll(folder: File, screens: List<Pair<String, @Composable () -> Unit>>) {
        for (dark in listOf(false, true)) {
            for ((name, screen) in screens) {
                val themeName = if (dark) "dark" else "light"
                capture(folder, "${name}_$themeName", dark, screen)
            }
        }
    }

    /** Three fake documents, newest first (sample names only). */
    private fun sampleDocuments(): List<DocumentSummary> {
        val added = ZonedDateTime.of(2026, 10, 27, 17, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
        return listOf(
            DocumentSummary("a", "Sample Aadhaar card", DocumentType.AADHAAR, pageCount = 2, createdAt = added, updatedAt = added),
            DocumentSummary("b", "Sample certificate", DocumentType.CERTIFICATE, pageCount = 1, createdAt = added, updatedAt = added),
            DocumentSummary("c", "Sample photo", DocumentType.PHOTO, pageCount = 1, createdAt = added, updatedAt = added),
        )
    }

    private fun noViewerActions(): ViewerActions = ViewerActions({}, {}, {}, {}, {}, {}, {}, {})

    /** Page of a fake 2-page PDF ("Sample page 2"), drawn by the app's own page renderer. */
    private fun renderSamplePdfPage(pageIndex: Int): Bitmap {
        val pdfFile = File(instrumentation.targetContext.cacheDir, "screenshot_sample.pdf")
        pdfFile.writeBytes(TestVault.sampleTwoPagePdfBytes())
        val renderer = PdfPageRenderer(pdfFile)
        val page = runBlocking { renderer.renderPage(pageIndex, SCREEN_WIDTH_PIXELS) }
        renderer.close()
        pdfFile.delete()
        return checkNotNull(page)
    }

    @Composable
    private fun AddScreenSample(state: AddDocumentUiState) {
        AddDocumentScreen(uiState = state, actions = AddDocumentActions({}, {}, {}, {}, {}, {}, {}, {}))
    }

    private fun capture(folder: File, fileName: String, dark: Boolean, screen: @Composable () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // TEST WINDOW ONLY: the app sets FLAG_SECURE (rule 8), which would make every design
                // screenshot black. The real app is never affected by this line.
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { HeyBuddyTheme(darkTheme = dark) { screen() } }
                // Match the system bar icons to the forced theme, as the real app does for the phone's theme.
                val barController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                barController.isAppearanceLightStatusBars = !dark
                barController.isAppearanceLightNavigationBars = !dark
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MILLIS)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(folder, "$fileName.png").outputStream().use { output ->
                screenshot.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
            }
        }
    }

    /** A fake photo for the preview: plain shapes and "HEY BUDDY SAMPLE", nothing personal. */
    private fun drawSamplePreview(): Bitmap = drawSampleCard(Color.rgb(30, 90, 160), "HEY BUDDY SAMPLE")

    /** A fake card-shaped picture with a label, nothing personal. */
    private fun drawSampleCard(background: Int, label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(255, 200, 0)
        canvas.drawCircle(350f, 400f, 150f, paint)
        paint.color = Color.WHITE
        paint.textSize = 64f
        canvas.drawText(label, 560f, 420f, paint)
        return bitmap
    }

    /** A thumbnail the size the Add screen really uses. */
    private fun thumbnailOf(picture: Bitmap): Bitmap {
        val scale = THUMBNAIL_MAX_PIXELS.toFloat() / maxOf(picture.width, picture.height)
        return Bitmap.createScaledBitmap(picture, (picture.width * scale).toInt(), (picture.height * scale).toInt(), true)
    }

    private fun samplePages(picture: Bitmap, count: Int): List<DraftPage> {
        return List(count) { index -> DraftPage("page-$index", thumbnailOf(picture), isPdf = false) }
    }

    private companion object {
        /** Lets fonts, images and any first animation finish before the picture is taken. */
        const val SETTLE_MILLIS = 900L
        const val PNG_QUALITY = 100
        const val SCREEN_WIDTH_PIXELS = 1080
    }
}

/** Every reusable component on one page (fake data), so the design can be reviewed in one look. */
@Composable
private fun ComponentSheet() {
    ScreenScaffold {
        Text(text = "Components (sample data)", style = MaterialTheme.typography.headlineSmall)
        for (type in DocumentType.entries) {
            val label = stringResource(labelFor(type))
            DocumentCard(name = "Sample $label", type = type, typeLabel = label, addedText = "Added 27 Oct 2026", onClick = {})
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            MessageBanner(text = "No fingerprint is set up. You can unlock with your PIN.", kind = BannerKind.INFO)
            MessageBanner(text = "Saved to your vault.", kind = BannerKind.SUCCESS)
            MessageBanner(text = "This file is larger than 20 MB. Please choose a smaller file.", kind = BannerKind.ERROR)
        }
        PrimaryButton(text = "Primary button", onClick = {}, icon = com.heybuddy.R.drawable.ic_lock)
        SecondaryButton(text = "Secondary button", onClick = {})
        QuietButton(text = "Quiet button", onClick = {})
        LoadingState(text = "Opening…")
        EmptyState(
            icon = com.heybuddy.R.drawable.ic_shield,
            title = "Your vault is empty",
            body = "Tap + to add your first document.",
        )
    }
}

package com.heybuddy.ui.camera

import androidx.annotation.StringRes
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.HeroIcon
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.QuietButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.components.SecondaryButton
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.util.A4_ASPECT_RATIO
import com.heybuddy.util.CARD_ASPECT_RATIO

// The capture screen is always dark, like every camera, so the document stands out. These are its
// own fixed colours (not theme colours): they must look the same in light and dark mode.
private val CameraBackground = Color(0xFF000000)
private val CameraOnBackground = Color(0xFFFFFFFF)
private val OutsideFrameShade = Color(0x8C000000)
private val ControlBackground = Color(0x66000000)

/** Sizes of the capture screen. */
private val ShutterSize = 76.dp
private val ShutterRing = 4.dp
private val ShutterGap = 6.dp
private val FrameStroke = 3.dp
private val FrameCorner = 16.dp

/** How much of the preview's width the guide frame uses (card) and its height limit (page). */
private const val CARD_FRAME_WIDTH_SHARE = 0.9f
private const val PAGE_FRAME_WIDTH_SHARE = 0.8f
private const val PAGE_FRAME_HEIGHT_SHARE = 0.72f

/**
 * Everything the camera screen can ask for.
 *
 * @property onClose Close the camera (also Back).
 * @property onContinueToAsk Show Android's camera question.
 * @property onOpenSettings Open the phone's settings page for Hey Buddy.
 * @property onChooseInstead Close the camera and open the photo picker instead.
 * @property onTorchClick Torch on or off.
 * @property onFrameShapeClick Switch the guide frame between card and page.
 * @property onShutterClick Take the photo.
 * @property onRetakeClick Throw the photo away and aim again.
 * @property onUsePhotoClick Keep the photo as a page.
 */
class CameraActions(
    val onClose: () -> Unit,
    val onContinueToAsk: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onChooseInstead: () -> Unit,
    val onTorchClick: () -> Unit,
    val onFrameShapeClick: () -> Unit,
    val onShutterClick: () -> Unit,
    val onRetakeClick: () -> Unit,
    val onUsePhotoClick: () -> Unit,
)

/**
 * The in-app camera (Task 2.4): permission explanation, live preview with a guide frame and
 * hints, and "Retake or keep". The preview is drawn inside Hey Buddy's own window, so FLAG_SECURE
 * covers it too.
 *
 * @param uiState What to show.
 * @param surfaceRequest Where the live preview goes, or null while the camera is closed.
 * @param actions What happens on each tap.
 */
@Composable
fun CameraScreen(uiState: CameraUiState, surfaceRequest: SurfaceRequest?, actions: CameraActions) {
    when (uiState.stage) {
        CameraStage.CHECKING -> Box(modifier = Modifier.fillMaxSize().background(CameraBackground))
        CameraStage.NEEDS_ACCESS -> AccessScreen(uiState.access, actions)
        CameraStage.LIVE, CameraStage.TAKING -> CaptureView(uiState, surfaceRequest, actions)
        CameraStage.REVIEW -> ReviewView(uiState, actions)
    }
}

// ---- Permission ----

@Composable
private fun AccessScreen(access: CameraAccess, actions: CameraActions) {
    ScreenScaffold(centerContent = true) {
        HeroIcon(icon = R.drawable.ic_photo_camera, modifier = Modifier.align(Alignment.CenterHorizontally))
        val (title, body) = accessTexts(access)
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        AccessButtons(access, actions)
    }
}

private fun accessTexts(access: CameraAccess): Pair<Int, Int> {
    return when (access) {
        CameraAccess.DENIED -> R.string.camera_denied_title to R.string.camera_denied_body
        CameraAccess.DENIED_FOR_GOOD -> R.string.camera_denied_for_good_title to R.string.camera_denied_for_good_body
        else -> R.string.camera_access_title to R.string.camera_access_body
    }
}

@Composable
private fun AccessButtons(access: CameraAccess, actions: CameraActions) {
    when (access) {
        CameraAccess.DENIED -> {
            PrimaryButton(text = stringResource(R.string.camera_choose_instead), onClick = actions.onChooseInstead, icon = R.drawable.ic_image)
            SecondaryButton(text = stringResource(R.string.camera_ask_again), onClick = actions.onContinueToAsk)
        }
        CameraAccess.DENIED_FOR_GOOD -> {
            PrimaryButton(text = stringResource(R.string.camera_choose_instead), onClick = actions.onChooseInstead, icon = R.drawable.ic_image)
            SecondaryButton(text = stringResource(R.string.camera_open_settings), onClick = actions.onOpenSettings, icon = R.drawable.ic_settings)
        }
        else -> PrimaryButton(text = stringResource(R.string.camera_access_continue), onClick = actions.onContinueToAsk)
    }
    QuietButton(
        text = stringResource(R.string.camera_access_not_now),
        onClick = actions.onClose,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- Live capture ----

@Composable
private fun CaptureView(uiState: CameraUiState, surfaceRequest: SurfaceRequest?, actions: CameraActions) {
    Box(modifier = Modifier.fillMaxSize().background(CameraBackground)) {
        if (surfaceRequest != null) {
            // EMBEDDED draws the preview inside our window (not a separate surface), so the
            // window's FLAG_SECURE surely covers it: no screenshots, blank in Recent apps.
            CameraXViewfinder(
                surfaceRequest = surfaceRequest,
                implementationMode = ImplementationMode.EMBEDDED,
                modifier = Modifier.fillMaxSize(),
            )
        }
        GuideFrame(uiState.frameShape)
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.SpaceL)) {
            TopControls(uiState, actions)
            Spacer(modifier = Modifier.weight(1f))
            BottomControls(uiState, actions)
        }
    }
}

@Composable
private fun TopControls(uiState: CameraUiState, actions: CameraActions) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIconButton(icon = R.drawable.ic_close, description = R.string.camera_close, onClick = actions.onClose)
        Spacer(modifier = Modifier.weight(1f))
        if (uiState.hasTorch) {
            val icon = if (uiState.torchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off
            val description = if (uiState.torchOn) R.string.camera_torch_off else R.string.camera_torch_on
            RoundIconButton(icon = icon, description = description, onClick = actions.onTorchClick)
        }
    }
}

@Composable
private fun BottomControls(uiState: CameraUiState, actions: CameraActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        val errorMessage = uiState.errorMessage
        if (errorMessage != null) MessageBanner(text = stringResource(errorMessage), kind = BannerKind.ERROR)
        HintPill(text = liveHintText(uiState))
        FrameShapeSwitch(selected = uiState.frameShape, onClick = actions.onFrameShapeClick)
        if (uiState.stage == CameraStage.TAKING) {
            Box(modifier = Modifier.size(ShutterSize), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CameraOnBackground)
            }
        } else {
            ShutterButton(onClick = actions.onShutterClick)
        }
    }
}

@StringRes
private fun liveHintText(uiState: CameraUiState): Int {
    if (uiState.stage == CameraStage.TAKING) return R.string.camera_taking
    return when (uiState.liveHint) {
        CaptureHint.MORE_LIGHT -> R.string.camera_hint_more_light
        CaptureHint.HOLD_STEADY -> R.string.camera_hint_hold_steady
        CaptureHint.TOO_BLURRY -> R.string.camera_hint_too_blurry
        null -> R.string.camera_aim
    }
}

/** A short hint on a dark pill. TalkBack reads it when it changes. */
@Composable
private fun HintPill(@StringRes text: Int) {
    Surface(color = ControlBackground, contentColor = CameraOnBackground, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** "Card | Page": two choices, the chosen one filled. */
@Composable
private fun FrameShapeSwitch(selected: FrameShape, onClick: () -> Unit) {
    Row(
        modifier = Modifier.background(ControlBackground, MaterialTheme.shapes.extraLarge).padding(Dimens.SpaceXs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        ShapeChoice(text = R.string.camera_frame_card, chosen = selected == FrameShape.CARD, onClick = onClick)
        ShapeChoice(text = R.string.camera_frame_page, chosen = selected == FrameShape.PAGE, onClick = onClick)
    }
}

@Composable
private fun ShapeChoice(@StringRes text: Int, chosen: Boolean, onClick: () -> Unit) {
    val background = if (chosen) CameraOnBackground else Color.Transparent
    val foreground = if (chosen) CameraBackground else CameraOnBackground
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = foreground,
        modifier = Modifier
            .background(background, MaterialTheme.shapes.extraLarge)
            .clickable(enabled = !chosen, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS),
    )
}

/** The big round shutter: a white ring around a white disc, like every camera. */
@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val description = stringResource(R.string.camera_shutter)
    Box(
        modifier = Modifier
            .size(ShutterSize)
            .border(BorderStroke(ShutterRing, CameraOnBackground), CircleShape)
            .padding(ShutterGap)
            .background(CameraOnBackground, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun RoundIconButton(icon: Int, @StringRes description: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.background(ControlBackground, CircleShape)) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(description), tint = CameraOnBackground)
    }
}

/**
 * The document-shaped guide: the area outside it is shaded, so the eye goes to the frame. Only a
 * guide: the photo itself is NOT cropped to it (owner decision, Task 2.4).
 */
@Composable
private fun BoxScope.GuideFrame(shape: FrameShape) {
    Canvas(modifier = Modifier.matchParentSize()) {
        val frame = frameRect(size, shape)
        val corner = CornerRadius(FrameCorner.toPx())
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(frame, corner))
        }
        drawPath(shade, OutsideFrameShade)
        drawRoundRect(
            color = CameraOnBackground,
            topLeft = frame.topLeft,
            size = frame.size,
            cornerRadius = corner,
            style = Stroke(width = FrameStroke.toPx()),
        )
    }
}

/** A card lies across the screen (wide); a page stands up (tall). Both are centred. */
private fun frameRect(area: Size, shape: FrameShape): Rect {
    val frameSize = when (shape) {
        FrameShape.CARD -> {
            val width = area.width * CARD_FRAME_WIDTH_SHARE
            Size(width, width / CARD_ASPECT_RATIO)
        }
        FrameShape.PAGE -> {
            val widest = area.width * PAGE_FRAME_WIDTH_SHARE
            val height = minOf(widest * A4_ASPECT_RATIO, area.height * PAGE_FRAME_HEIGHT_SHARE)
            Size(height / A4_ASPECT_RATIO, height)
        }
    }
    val topLeft = Offset((area.width - frameSize.width) / 2f, (area.height - frameSize.height) / 2f)
    return Rect(topLeft, frameSize)
}

// ---- Retake or keep ----

@Composable
private fun ReviewView(uiState: CameraUiState, actions: CameraActions) {
    Box(modifier = Modifier.fillMaxSize().background(CameraBackground)) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.SpaceL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
        ) {
            val review = uiState.review
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (review != null) {
                    Image(
                        bitmap = review.asImageBitmap(),
                        contentDescription = stringResource(R.string.camera_review_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ReviewHint(uiState.reviewHint)
            Column(
                modifier = Modifier.widthIn(max = Dimens.MaxContentWidth),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
            ) {
                PrimaryButton(text = stringResource(R.string.camera_use_photo), onClick = actions.onUsePhotoClick, icon = R.drawable.ic_check_circle)
                ReviewSecondaryButton(onClick = actions.onRetakeClick)
            }
        }
    }
}

@Composable
private fun ReviewHint(hint: CaptureHint?) {
    if (hint == null) {
        Text(
            text = stringResource(R.string.camera_review_looks_good),
            style = MaterialTheme.typography.bodyMedium,
            color = CameraOnBackground,
            textAlign = TextAlign.Center,
        )
        return
    }
    val text = if (hint == CaptureHint.MORE_LIGHT) R.string.camera_hint_more_light else R.string.camera_hint_too_blurry
    MessageBanner(text = stringResource(text), kind = BannerKind.ERROR)
}

/** "Retake": outlined in white, so it shows on the dark screen. */
@Composable
private fun ReviewSecondaryButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.ButtonHeight)
            .border(BorderStroke(Dimens.BorderWidth, CameraOnBackground), MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = stringResource(R.string.camera_retake), style = MaterialTheme.typography.labelLarge, color = CameraOnBackground)
    }
}

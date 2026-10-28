package com.heybuddy.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.motionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Screens slide in from 1/24 of their height below: enough to feel smooth, small enough to stay calm. */
private const val SLIDE_FRACTION = 24

/**
 * Where each document card was last seen on screen, so the viewer can grow out of it
 * (spec 5.14, item 1). Memory only; window coordinates.
 */
class CardBounds {
    private val bounds = mutableMapOf<String, Rect>()

    /**
     * Remembers a card's place.
     *
     * @param documentId The card's document.
     * @param rect Where it is, in window coordinates.
     */
    fun remember(documentId: String, rect: Rect) {
        bounds[documentId] = rect
    }

    /**
     * Where a card was last seen.
     *
     * @param documentId The document.
     * @return Its card's place, or null if it has not been shown.
     */
    fun of(documentId: String): Rect? = bounds[documentId]
}

/** Set by [VaultScreenHost]; null elsewhere (for example in screenshots), where nothing grows. */
val LocalCardBounds = staticCompositionLocalOf<CardBounds?> { null }

/** Set by [VaultScreenHost]: the showing screen's own enter/exit animation. */
val LocalScreenVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Set by [VaultScreenHost]; lets a screen's Back gesture preview the list behind it. */
val LocalBackPreview = staticCompositionLocalOf<BackPreview?> { null }

/**
 * Drives the predictive back preview (spec 5.14, item 4): while the user slowly swipes back, the
 * screen change towards the list follows the finger; letting go finishes it, cancelling returns.
 *
 * @param state The vault screens' seekable transition.
 * @param scope Runs the "return" animation when a swipe is cancelled.
 * @param currentScreen The screen the navigator shows right now.
 */
class BackPreview internal constructor(
    private val state: SeekableTransitionState<VaultScreen>,
    private val scope: CoroutineScope,
    private val currentScreen: () -> VaultScreen,
) {

    /**
     * Moves the preview to [fraction] of the way back to the list.
     *
     * @param fraction 0 = not moved, 1 = fully back.
     */
    suspend fun seekBack(fraction: Float) {
        state.seekTo(fraction, targetState = VaultScreen.List)
    }

    /** Settles on whatever screen the navigator shows now (after a finished or cancelled swipe). */
    fun settle() {
        scope.launch { state.animateTo(currentScreen()) }
    }
}

/**
 * Shows the vault screens with their motion (spec 5.14): fade and slide between screens, the
 * viewer growing out of its document card and back, and the back-gesture preview on Android 14+.
 *
 * Used by the app and by the video script, so both show exactly the same motion. Locking never runs
 * through here: the whole host disappears at once (see [TopScreenHost]).
 *
 * @param screen The screen to show.
 * @param currentScreen Reads the navigator's screen at the moment it is needed (after a Back).
 * @param content Draws one screen.
 */
@Composable
fun VaultScreenHost(
    screen: VaultScreen,
    currentScreen: () -> VaultScreen = { screen },
    content: @Composable (VaultScreen) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val transitionState = remember { SeekableTransitionState(screen) }
    val latestScreen by rememberUpdatedState(currentScreen)
    val scope = rememberCoroutineScope()
    val backPreview = remember(transitionState) { BackPreview(transitionState, scope) { latestScreen() } }
    val cardBounds = remember { CardBounds() }
    LaunchedEffect(screen, reduceMotion) {
        // "Remove animations": jump straight to the new screen; even an empty animation takes frames.
        if (reduceMotion) transitionState.snapTo(screen) else transitionState.animateTo(screen)
    }
    val transition = rememberTransition(transitionState, label = "vaultScreen")
    transition.AnimatedContent(
        transitionSpec = { vaultScreenTransition(initialState, targetState, reduceMotion) },
    ) { shown ->
        CompositionLocalProvider(
            LocalScreenVisibility provides this,
            LocalBackPreview provides backPreview,
            LocalCardBounds provides cardBounds,
        ) {
            content(shown)
        }
    }
}

/**
 * Put on a document's list card: remembers where the card is, so the viewer can grow out of it.
 *
 * @param documentId The card's document.
 * @return The modifier.
 */
@Composable
fun Modifier.documentCard(documentId: String): Modifier {
    val cardBounds = LocalCardBounds.current ?: return this
    return this.onGloballyPositioned { coordinates -> cardBounds.remember(documentId, coordinates.boundsInWindow()) }
}

/**
 * Put on the viewer: it is revealed through a rounded window that grows from the document's card to
 * the full screen while it fades in, and shrinks back into the card on Back (spec 5.14, item 1; a
 * "container transform"). Only the clip and the fade change per frame, never the layout, so it stays
 * smooth. Does nothing outside [VaultScreenHost], when the card was never shown, or when "Remove
 * animations" is on.
 *
 * @param documentId The document being viewed.
 * @return The modifier.
 */
@Composable
fun Modifier.growsFromCard(documentId: String): Modifier {
    val visibility = LocalScreenVisibility.current ?: return this
    val cardRect = LocalCardBounds.current?.of(documentId) ?: return this
    if (LocalReduceMotion.current) return this
    val grown by visibility.transition.animateFloat(
        transitionSpec = { tween(MotionDurations.SHARED_ELEMENT_MILLIS) },
        label = "growFromCard",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    val viewerPlace = remember { ViewerPlace() }
    return this
        .onGloballyPositioned { coordinates -> viewerPlace.topLeft = coordinates.positionInWindow() }
        .graphicsLayer {
            val fullRect = Rect(Offset.Zero, size)
            val cardInViewer = cardRect.translate(-viewerPlace.topLeft)
            clip = true
            shape = RevealShape(lerp(cardInViewer, fullRect, grown), lerp(CardCorner.toPx(), 0f, grown))
            // The viewer's content fades in over the first half, so the card's shape leads the way.
            alpha = (grown * 2f).coerceAtMost(1f)
        }
}

/** Where the viewer sits in the window (it fills the window, so normally the top-left corner). */
private class ViewerPlace {
    var topLeft: Offset = Offset.Zero
}

/** A rounded rectangle at a fixed place: the growing "window" the viewer is seen through. */
private class RevealShape(private val rect: Rect, private val cornerPixels: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        path.addRoundRect(RoundRect(rect, CornerRadius(cornerPixels)))
        return Outline.Generic(path)
    }
}

/** The cards' corner (the "large" shape, spec 5.14); it shrinks to 0 as the viewer grows. */
private val CardCorner = 16.dp

/**
 * Back for a vault screen. While [previewEnabled] (no dialog open, nothing running), a slow back
 * swipe on Android 14+ previews the list behind; otherwise Back is handled at once, as before.
 * On Android 13 and older there is no preview: Back simply runs [onBack].
 *
 * @param previewEnabled True when this Back would leave the screen.
 * @param onBack What Back does (close a dialog, or tidy up and go back to the list).
 */
@Composable
fun VaultBackHandler(previewEnabled: Boolean, onBack: () -> Unit) {
    val preview = LocalBackPreview.current
    val currentOnBack by rememberUpdatedState(onBack)
    if (preview == null || !previewEnabled || LocalReduceMotion.current) {
        BackHandler(onBack = { currentOnBack() })
        return
    }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent -> preview.seekBack(backEvent.progress) }
            currentOnBack()
        } catch (cancelled: CancellationException) {
            // The user changed their mind: return to this screen, then let the cancel continue.
            preview.settle()
            throw cancelled
        }
        // If Back did not actually leave (for example something was still running), go back to
        // where the navigator is instead of staying half-way.
        preview.settle()
    }
}

/**
 * Moving between vault screens (spec 5.14): list ↔ viewer cross-fades while the viewer grows out of
 * its card ([growsFromCard]); other screens fade with a slight slide. No size animation: screens
 * always fill the window.
 */
private fun vaultScreenTransition(initial: VaultScreen, target: VaultScreen, reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return withoutSizeAnimation(EnterTransition.None, ExitTransition.None)
    val involvesViewer = initial is VaultScreen.Viewer || target is VaultScreen.Viewer
    if (involvesViewer) {
        return withoutSizeAnimation(
            fadeIn(motionSpec(MotionDurations.SHARED_ELEMENT_MILLIS, false)),
            fadeOut(motionSpec(MotionDurations.SHARED_ELEMENT_MILLIS, false)),
        )
    }
    val enter = fadeIn(motionSpec(MotionDurations.SCREEN_MILLIS, false)) +
        slideInVertically(motionSpec(MotionDurations.SCREEN_MILLIS, false)) { height -> height / SLIDE_FRACTION }
    return withoutSizeAnimation(enter, fadeOut(motionSpec(MotionDurations.SCREEN_MILLIS, false)))
}

// Compose adds a size spring by default; it would keep the OLD screen alive for about 400 ms, even
// with "Remove animations". Screens always fill the window, so it is never needed.
private fun withoutSizeAnimation(enter: EnterTransition, exit: ExitTransition): ContentTransform {
    return ContentTransform(targetContentEnter = enter, initialContentExit = exit, sizeTransform = null)
}

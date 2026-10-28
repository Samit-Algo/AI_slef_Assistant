package com.heybuddy.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heybuddy.R
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.HeroIcon
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.QuietButton
import com.heybuddy.ui.components.gentleNudgeLoop
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.LocalReduceMotion
import com.heybuddy.ui.theme.MotionDurations
import com.heybuddy.ui.theme.motionSpec
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One onboarding card (spec 5.6).
 *
 * @property icon The hero icon.
 * @property title One short line.
 * @property body A few plain sentences.
 * @property showNoBackupWarning True on the card that also carries the honest "no backup" warning.
 */
private class OnboardingCard(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val showNoBackupWarning: Boolean = false,
)

private val Cards: List<OnboardingCard> = listOf(
    OnboardingCard(R.drawable.ic_shield, R.string.onboarding_card1_title, R.string.onboarding_card1_body),
    OnboardingCard(R.drawable.ic_privacy_tip, R.string.onboarding_card2_title, R.string.onboarding_card2_body),
    // Owner request: the warning sits on the "Only you can unlock" card, where it belongs.
    OnboardingCard(R.drawable.ic_fingerprint, R.string.onboarding_card3_title, R.string.onboarding_card3_body, showNoBackupWarning = true),
)

private val DotSize = 8.dp

/** The current page's dot stretches into a short pill (spec 5.14, item 7). */
private val SelectedDotWidth = 20.dp

/**
 * The first-start screen: three cards to swipe through, then "Set up my vault".
 * Shown only on a first start, and again after "Delete everything".
 *
 * @param onFinished Called when the user taps "Set up my vault".
 * @param initialPage Which card to start on; always 0 in the app (other values are for screenshots).
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, initialPage: Int = 0) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { Cards.size })
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = { OnboardingControls(pagerState, onFinished) },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) { page ->
            CardPage(Cards[page], modifier = Modifier.slideDepth(pagerState, page))
        }
    }
}

/**
 * A slight depth effect while swiping (spec 5.14, item 7): the card's content moves a little slower
 * than the page and fades a little, so it feels layered rather than flat. Off with "Remove animations".
 */
@Composable
private fun Modifier.slideDepth(pagerState: PagerState, page: Int): Modifier {
    if (LocalReduceMotion.current) return this
    return this.graphicsLayer {
        val pagesAway = pagerState.getOffsetDistanceInPages(page).coerceIn(-1f, 1f)
        translationX = pagesAway * size.width * DEPTH_LAG
        alpha = 1f - abs(pagesAway) * DEPTH_FADE
    }
}

/** The content lags a quarter of the page's movement behind. */
private const val DEPTH_LAG = 0.25f

/** A card one page away is 40 % faded. */
private const val DEPTH_FADE = 0.4f

@Composable
private fun CardPage(card: OnboardingCard, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.MaxContentWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
        ) {
            HeroIcon(icon = card.icon, modifier = Modifier.gentleNudgeLoop())
            Spacer(modifier = Modifier.height(Dimens.SpaceS))
            Text(
                text = stringResource(card.title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(card.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (card.showNoBackupWarning) {
                MessageBanner(text = stringResource(R.string.no_backup_warning), kind = BannerKind.INFO)
            }
        }
    }
}

@Composable
private fun OnboardingControls(pagerState: PagerState, onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val page = pagerState.currentPage
    // Next/Back slide in 300 ms (spec 5.14, item 7), or jump at once with "Remove animations".
    val goToPage = { target: Int ->
        scope.launch {
            if (reduceMotion) pagerState.scrollToPage(target) else pagerState.animateScrollToPage(target, animationSpec = tween(MotionDurations.ONBOARDING_SLIDE_MILLIS))
        }
    }
    val isLast = page == Cards.lastIndex
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        PageDots(currentPage = page, pageCount = Cards.size)
        if (isLast) {
            PrimaryButton(text = stringResource(R.string.onboarding_start), onClick = onFinished)
        } else {
            PrimaryButton(
                text = stringResource(R.string.onboarding_next),
                onClick = { goToPage(page + 1) },
            )
        }
        // Keeps the buttons in the same place on every card; on the first card there is no Back.
        if (page > 0) {
            QuietButton(
                text = stringResource(R.string.onboarding_back),
                onClick = { goToPage(page - 1) },
            )
        } else {
            Spacer(modifier = Modifier.height(Dimens.MinTouchTarget))
        }
    }
}

/** Small dots under the cards; TalkBack reads "Card 2 of 3" instead of each dot. */
@Composable
private fun PageDots(currentPage: Int, pageCount: Int) {
    val description = stringResource(R.string.onboarding_page_of, currentPage + 1, pageCount)
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        for (index in 0 until pageCount) {
            PageDot(selected = index == currentPage)
        }
    }
}

/** One dot; the selected one stretches and takes the brand colour, sliding smoothly between pages. */
@Composable
private fun PageDot(selected: Boolean) {
    val width by animateDpAsState(
        targetValue = if (selected) SelectedDotWidth else DotSize,
        animationSpec = motionSpec(MotionDurations.PAGE_DOTS_MILLIS),
        label = "dotWidth",
    )
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = motionSpec(MotionDurations.PAGE_DOTS_MILLIS),
        label = "dotColor",
    )
    Box(modifier = Modifier.size(width = width, height = DotSize).background(color = color, shape = CircleShape))
}

package com.heybuddy.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.heybuddy.ui.theme.Dimens
import kotlinx.coroutines.launch

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
            CardPage(Cards[page])
        }
    }
}

@Composable
private fun CardPage(card: OnboardingCard) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Dimens.MaxContentWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
        ) {
            HeroIcon(icon = card.icon)
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
    val page = pagerState.currentPage
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
                onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
            )
        }
        // Keeps the buttons in the same place on every card; on the first card there is no Back.
        if (page > 0) {
            QuietButton(
                text = stringResource(R.string.onboarding_back),
                onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
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
            val color = if (index == currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(modifier = Modifier.size(DotSize).background(color = color, shape = CircleShape))
        }
    }
}

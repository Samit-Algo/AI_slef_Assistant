package com.heybuddy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.heybuddy.ui.theme.Dimens

/**
 * The frame every screen sits in. Handles edge-to-edge in ONE place: content never goes behind the
 * status bar, the navigation/gesture bar, a camera cutout or the keyboard ("safe drawing" insets).
 *
 * The content scrolls, so nothing is cut off at large font sizes or on small screens, and it is
 * limited to a readable width on tablets.
 *
 * @param centerContent True to centre the content vertically (Unlock, empty states).
 * @param topBar Optional bar at the top (for example a back button and title).
 * @param content The screen's content.
 */
@Composable
fun ScreenScaffold(
    centerContent: Boolean = false,
    topBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = topBar,
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Inside a scroll the height is unlimited, so "centre" needs a minimum height: the
            // visible area. Content then centres when it fits and scrolls when it does not.
            val visibleHeight = maxHeight
            Column(
                modifier = Modifier
                    .widthIn(max = Dimens.MaxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = visibleHeight)
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXl),
                verticalArrangement = verticalArrangementFor(centerContent),
                horizontalAlignment = if (centerContent) Alignment.CenterHorizontally else Alignment.Start,
                content = content,
            )
        }
    }
}

private fun verticalArrangementFor(centerContent: Boolean): Arrangement.Vertical {
    if (centerContent) return Arrangement.spacedBy(Dimens.SpaceL, Alignment.CenterVertically)
    return Arrangement.spacedBy(Dimens.SpaceL)
}

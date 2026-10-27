package com.heybuddy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.heybuddy.data.DocumentType
import com.heybuddy.ui.theme.Dimens
import com.heybuddy.ui.theme.containerColor
import com.heybuddy.ui.theme.iconColor
import com.heybuddy.ui.theme.styleFor

/**
 * The document type's icon on a soft circle of its own colour (spec 5.14).
 *
 * Decorative for TalkBack: wherever it appears, the type name is also written in text, so colour
 * and icon are never the only signal.
 *
 * @param type The document type.
 * @param modifier Layout changes from the caller.
 */
@Composable
fun TypeBadge(type: DocumentType, modifier: Modifier = Modifier) {
    val style = styleFor(type)
    val container = style.containerColor(MaterialTheme.colorScheme.surface)
    Box(
        modifier = modifier
            .size(Dimens.BadgeSize)
            .background(color = container, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(style.icon),
            contentDescription = null,
            tint = style.iconColor(),
            modifier = Modifier.size(Dimens.BadgeIconSize),
        )
    }
}

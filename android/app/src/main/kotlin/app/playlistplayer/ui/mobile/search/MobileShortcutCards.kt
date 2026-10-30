package app.playlistplayer.ui.mobile.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.vod.Poster
import coil3.compose.AsyncImage

// Touch cards shared by the phone search and home screens.

internal val MOBILE_CHANNEL_CARD_WIDTH = 200.dp

@Composable
internal fun MobileRowHeading(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(
        text = text,
        style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        color = UiTokens.OnBackground,
        modifier = modifier.padding(horizontal = MobileDimens.ScreenPadding).semantics { heading() },
    )
}

@Composable
internal fun MobileCardRow(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = MobileDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
        content = content,
    )
}

/** A channel card (min 48 dp touch target): logo, name and group/playlist line. */
@Composable
internal fun MobileChannelCard(name: String, subtitle: String, logo: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(MOBILE_CHANNEL_CARD_WIDTH)
            .sizeIn(minHeight = MobileDimens.MinTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $subtitle" },
        colors = CardDefaults.cardColors(containerColor = UiTokens.Surface),
    ) {
        Row(
            modifier = Modifier.padding(UiTokens.SpaceS),
            horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp).background(UiTokens.SurfaceVariant, MaterialTheme.shapes.small)) {
                if (!logo.isNullOrBlank()) {
                    AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Column {
                Text(text = name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A poster card with title, optional secondary line and optional progress bar (0..1). */
@Composable
internal fun MobilePosterCard(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    description: String = listOfNotNull(title, subtitle).joinToString(", "),
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(MobileDimens.PosterWidth).semantics(mergeDescendants = true) { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = UiTokens.Surface),
    ) {
        Column {
            Box {
                Poster(url = posterUrl, width = MobileDimens.PosterWidth)
                if (progress != null) {
                    Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(UiTokens.Surface)) {
                        Box(modifier = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(UiTokens.Accent))
                    }
                }
            }
            Column(modifier = Modifier.padding(UiTokens.SpaceS)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

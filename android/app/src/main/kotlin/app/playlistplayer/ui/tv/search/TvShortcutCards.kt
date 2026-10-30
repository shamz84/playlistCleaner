package app.playlistplayer.ui.tv.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.theme.TvDimens
import coil3.compose.AsyncImage

// Focusable cards shared by the TV search and home screens.

internal val TV_CHANNEL_CARD_WIDTH = 240.dp
internal val TV_POSTER_CARD_WIDTH = 150.dp

/** A section or row heading, inset for overscan. */
@Composable
internal fun TvRowHeading(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(
        text = text,
        style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        color = UiTokens.OnBackground,
        modifier = modifier.padding(horizontal = UiTokens.OverscanHorizontal).semantics { heading() },
    )
}

/** A horizontal row of cards with overscan-safe padding. */
@Composable
internal fun TvCardRow(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = UiTokens.OverscanHorizontal, vertical = UiTokens.SpaceS),
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        content = content,
    )
}

/** A channel: logo (when any), name and a secondary line (group or playlist). */
@Composable
internal fun TvChannelCard(name: String, subtitle: String, logo: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier
            .width(TV_CHANNEL_CARD_WIDTH)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $subtitle" },
        scale = CardDefaults.scale(focusedScale = UiTokens.FocusScale),
        border = CardDefaults.border(focusedBorder = TvDimens.FocusBorder),
    ) {
        Row(
            modifier = Modifier.padding(UiTokens.SpaceM),
            horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(48.dp).background(UiTokens.SurfaceVariant, TvDimens.Shape)) {
                if (!logo.isNullOrBlank()) {
                    AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
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

/**
 * A movie / series poster with title, an optional secondary line and an optional progress bar
 * ([progress] in 0..1, Continue watching).
 */
@Composable
internal fun TvPosterCard(
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
        modifier = modifier.width(TV_POSTER_CARD_WIDTH).semantics(mergeDescendants = true) { contentDescription = description },
        scale = CardDefaults.scale(focusedScale = UiTokens.FocusScale),
        border = CardDefaults.border(focusedBorder = TvDimens.FocusBorder),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(UiTokens.SurfaceVariant)) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
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

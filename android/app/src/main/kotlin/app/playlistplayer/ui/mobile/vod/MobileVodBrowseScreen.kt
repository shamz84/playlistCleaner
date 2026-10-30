package app.playlistplayer.ui.mobile.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.vod.RailState
import app.playlistplayer.feature.vod.RailStatus
import app.playlistplayer.feature.vod.VodBrowseViewModel
import app.playlistplayer.feature.vod.VodCard
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileError
import app.playlistplayer.ui.mobile.MobileUiState
import app.playlistplayer.ui.common.badge

object MobileVodTags {
    const val BROWSE = "mobile.vod.browse"
    fun rail(playlistId: Long, categoryId: String) = "mobile.vod.rail.$playlistId.$categoryId"
}

/**
 * Movies / Series browse on phone (R12, R25): a section per playlist name, then category rails
 * (LazyRow of posters), one card per merged title. Rails load as they scroll into view.
 */
@Composable
fun MobileVodBrowseScreen(viewModel: VodBrowseViewModel, onOpen: (VodCard) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val empty = stringResource(if (viewModel.kind == VodKind.MOVIE) R.string.vod_empty_movies else R.string.vod_empty_series)
    MobileUiState(state = MobileVodStates.browse(state, empty), modifier = modifier) { sections ->
        LazyColumn(
            modifier = modifier.fillMaxSize().testTag(MobileVodTags.BROWSE),
            contentPadding = PaddingValues(vertical = UiTokens.SpaceM),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            for (section in sections) {
                item(key = "header:${section.playlist.id}") {
                    Text(
                        text = section.playlist.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = UiTokens.Accent,
                        modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding).semantics { heading() },
                    )
                }
                if (section.failed) {
                    item(key = "failed:${section.playlist.id}") {
                        MobileError(message = stringResource(R.string.vod_section_failed), onRetry = viewModel::refresh, modifier = Modifier.height(200.dp))
                    }
                }
                itemsIndexed(section.rails, key = { _, rail -> "rail:${section.playlist.id}:${rail.category.id}" }) { index, rail ->
                    LaunchedEffect(rail.category.id, rail.status) {
                        if (rail.status == RailStatus.NOT_LOADED) viewModel.onRailVisible(section.playlist.id, index)
                    }
                    Rail(
                        rail = rail,
                        onOpen = onOpen,
                        onRetry = { viewModel.retryRail(section.playlist.id, rail.category.id) },
                        modifier = Modifier.testTag(MobileVodTags.rail(section.playlist.id, rail.category.id)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Rail(rail: RailState, onOpen: (VodCard) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
        Text(
            text = rail.category.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding).semantics { heading() },
        )
        when (rail.status) {
            RailStatus.FAILED -> Column(
                modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
            ) {
                Text(stringResource(R.string.vod_rail_failed), color = UiTokens.OnSurfaceMuted)
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.vod_retry)) }
            }
            RailStatus.LOADED -> LazyRow(
                contentPadding = PaddingValues(horizontal = MobileDimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
            ) {
                items(rail.cards, key = { it.titleKey }) { card -> PosterCard(card, onClick = { onOpen(card) }) }
            }
            else -> {
                val loading = stringResource(R.string.vod_loading)
                Box(
                    modifier = Modifier.fillMaxWidth().height(RAIL_PLACEHOLDER_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp).semantics { contentDescription = loading })
                }
            }
        }
    }
}

@Composable
private fun PosterCard(card: VodCard, onClick: () -> Unit) {
    val description = buildString {
        append(card.title)
        card.year?.let { append(", ").append(it) }
        card.bestQuality?.badge()?.let { append(", ").append(it) }
    }
    val versions = if (card.versionCount > 1) stringResource(R.string.vod_versions_count, card.versionCount) else null
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = UiTokens.Surface),
        modifier = Modifier
            .width(MobileDimens.PosterWidth)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(description, versions).joinToString(", ")
            },
    ) {
        Poster(url = card.posterUrl, width = MobileDimens.PosterWidth, badge = card.bestQuality?.badge())
        Column(modifier = Modifier.padding(UiTokens.SpaceS)) {
            Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            card.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted) }
        }
    }
}

private val RAIL_PLACEHOLDER_HEIGHT = 168.dp

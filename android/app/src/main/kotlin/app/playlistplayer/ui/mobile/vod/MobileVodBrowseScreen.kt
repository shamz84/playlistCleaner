package app.playlistplayer.ui.mobile.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import app.playlistplayer.feature.vod.ContinueCard
import app.playlistplayer.feature.vod.VodBrowseViewModel
import app.playlistplayer.feature.vod.VodCard
import app.playlistplayer.feature.vod.VodSort
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.badge
import app.playlistplayer.ui.common.continueLabel
import app.playlistplayer.ui.common.sortLabel
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileError
import app.playlistplayer.ui.mobile.MobileUiState
import app.playlistplayer.feature.vod.PlaylistSection as VodSection

object MobileVodTags {
    const val BROWSE = "mobile.vod.browse"
    fun trending(playlistId: Long) = "mobile.vod.trending.$playlistId"
    fun continueWatching(playlistId: Long) = "mobile.vod.continue.$playlistId"
    fun sort(playlistId: Long, sort: VodSort) = "mobile.vod.sort.$playlistId.${sort.name}"
}

/**
 * Movies / TV Shows browse on phone (U18): a section per playlist name with Trending (plus the
 * TMDB credit), Continue watching, then every title in a poster grid with a sort switch. The
 * grid pages in as its last row comes into view.
 */
@Composable
fun MobileVodBrowseScreen(viewModel: VodBrowseViewModel, onOpen: (VodCard) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val empty = stringResource(if (viewModel.kind == VodKind.MOVIE) R.string.vod_empty_movies else R.string.vod_empty_series)
    MobileUiState(state = MobileVodStates.browse(state, empty), modifier = modifier) { sections ->
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val gap = UiTokens.SpaceS
            val columns = ((maxWidth - MobileDimens.ScreenPadding * 2 + gap) / (MobileDimens.PosterWidth + gap)).toInt().coerceAtLeast(2)
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(MobileVodTags.BROWSE),
                contentPadding = PaddingValues(vertical = UiTokens.SpaceM),
                verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
            ) {
                for (section in sections) section(section, viewModel, columns, onOpen)
            }
        }
    }
}

private fun LazyListScope.section(section: VodSection, viewModel: VodBrowseViewModel, columns: Int, onOpen: (VodCard) -> Unit) {
    val id = section.playlist.id
    item(key = "header:$id") {
        Text(
            text = section.playlist.name,
            style = MaterialTheme.typography.titleLarge,
            color = UiTokens.Accent,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding).semantics { heading() },
        )
    }
    when {
        section.failed -> item(key = "failed:$id") {
            MobileError(message = stringResource(R.string.vod_section_failed), onRetry = viewModel::refresh, modifier = Modifier.height(200.dp))
        }
        section.loading -> item(key = "loading:$id") {
            val loading = stringResource(R.string.vod_loading)
            Box(modifier = Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp).semantics { contentDescription = loading })
            }
        }
        else -> {
            if (section.trending.isNotEmpty()) {
                item(key = "trending:$id") {
                    Column(modifier = Modifier.testTag(MobileVodTags.trending(id)), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                        RowHeader(stringResource(R.string.vod_trending))
                        LazyRow(contentPadding = PaddingValues(horizontal = MobileDimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                            items(section.trending, key = { it.titleKey }) { card -> PosterCard(card, onClick = { onOpen(card) }) }
                        }
                        Text(
                            text = stringResource(R.string.vod_tmdb_credit),
                            style = MaterialTheme.typography.labelSmall,
                            color = UiTokens.OnSurfaceMuted,
                            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding),
                        )
                    }
                }
            }
            if (section.continueWatching.isNotEmpty()) {
                item(key = "continue:$id") {
                    Column(modifier = Modifier.testTag(MobileVodTags.continueWatching(id)), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                        RowHeader(stringResource(R.string.vod_continue_watching))
                        LazyRow(contentPadding = PaddingValues(horizontal = MobileDimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                            items(section.continueWatching, key = { it.card.titleKey }) { c -> ContinuePoster(c, onClick = { onOpen(c.card) }) }
                        }
                    }
                }
            }
            item(key = "all:$id") {
                Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                    RowHeader(stringResource(if (viewModel.kind == VodKind.MOVIE) R.string.vod_all_movies else R.string.vod_all_tv_shows))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = MobileDimens.ScreenPadding),
                        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
                    ) {
                        VodSort.entries.forEach { sort ->
                            FilterChip(
                                selected = section.sort == sort,
                                onClick = { viewModel.setSort(id, sort) },
                                label = { Text(sortLabel(sort)) },
                                modifier = Modifier.testTag(MobileVodTags.sort(id, sort)),
                            )
                        }
                    }
                }
            }
            val rows = section.titles.chunked(columns)
            rows.forEachIndexed { index, row ->
                item(key = "grid:$id:${section.sort}:$index") {
                    if (index == rows.lastIndex && section.hasMore) {
                        LaunchedEffect(section.titles.size, section.sort) { viewModel.loadMore(id) }
                    }
                    Row(modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                        row.forEach { card -> PosterCard(card, onClick = { onOpen(card) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding).semantics { heading() },
    )
}

@Composable
private fun ContinuePoster(item: ContinueCard, onClick: () -> Unit) {
    val label = continueLabel(item)
    Column {
        PosterCard(item.card, onClick = onClick, subtitle = label)
        item.fraction?.let { fraction ->
            Box(modifier = Modifier.width(MobileDimens.PosterWidth).height(3.dp).background(UiTokens.SurfaceVariant)) {
                Box(modifier = Modifier.fillMaxWidth(fraction).height(3.dp).background(UiTokens.Accent))
            }
        }
    }
}

@Composable
private fun PosterCard(card: VodCard, onClick: () -> Unit, subtitle: String? = null) {
    val description = buildString {
        append(card.title)
        subtitle?.let { append(", ").append(it) }
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
            (subtitle ?: card.year?.toString())?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val PLACEHOLDER_HEIGHT = 168.dp

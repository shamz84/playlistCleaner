package app.playlistplayer.ui.tv.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.vod.PlaylistSection
import app.playlistplayer.feature.vod.VodBrowseViewModel
import app.playlistplayer.feature.vod.VodCard
import app.playlistplayer.feature.vod.VodSort
import app.playlistplayer.ui.common.badge
import app.playlistplayer.ui.common.continueLabel
import app.playlistplayer.ui.common.sortLabel
import coil3.compose.AsyncImage

/**
 * Movies / TV Shows browse (U18): a section per playlist name with Trending (plus the TMDB
 * credit), Continue watching, then every title in a grid with a sort switch. The grid pages in
 * as its last row comes into view.
 */
@Composable
fun VodBrowseScreen(viewModel: VodBrowseViewModel, onOpen: (VodCard) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.loading -> CenteredMessage(stringResource(R.string.vod_loading), modifier)
        state.isEmpty && state.sections.none { it.failed } -> CenteredMessage(
            stringResource(if (viewModel.kind == VodKind.MOVIE) R.string.vod_empty_movies else R.string.vod_empty_series),
            modifier,
        )
        else -> BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val columns = ((maxWidth - SIDE_PADDING * 2 + GAP) / (CARD_WIDTH + GAP)).toInt().coerceAtLeast(1)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                for (section in state.sections) {
                    section(section, viewModel, columns, onOpen)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
private fun LazyListScope.section(section: PlaylistSection, viewModel: VodBrowseViewModel, columns: Int, onOpen: (VodCard) -> Unit) {
    val id = section.playlist.id
    item(key = "header:$id") {
        Text(text = section.playlist.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = SIDE_PADDING))
    }
    when {
        section.failed -> item(key = "failed:$id") {
            CenteredMessage(stringResource(R.string.vod_section_failed)) {
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.vod_retry)) }
            }
        }
        section.loading -> item(key = "loading:$id") {
            Box(modifier = Modifier.height(CARD_HEIGHT).padding(horizontal = SIDE_PADDING), contentAlignment = Alignment.CenterStart) {
                Text(stringResource(R.string.vod_loading))
            }
        }
        else -> {
            if (section.trending.isNotEmpty()) {
                item(key = "trending:$id") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RowHeader(stringResource(R.string.vod_trending))
                        CardRow(section.trending, onOpen)
                        Text(
                            text = stringResource(R.string.vod_tmdb_credit),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = SIDE_PADDING),
                        )
                    }
                }
            }
            if (section.continueWatching.isNotEmpty()) {
                item(key = "continue:$id") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RowHeader(stringResource(R.string.vod_continue_watching))
                        LazyRow(contentPadding = PaddingValues(horizontal = SIDE_PADDING), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            items(section.continueWatching, key = { it.card.titleKey }) { c ->
                                TitleCardView(c.card, subtitle = continueLabel(c), progress = c.fraction, onClick = { onOpen(c.card) })
                            }
                        }
                    }
                }
            }
            item(key = "all:$id") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RowHeader(stringResource(if (viewModel.kind == VodKind.MOVIE) R.string.vod_all_movies else R.string.vod_all_tv_shows))
                    Row(modifier = Modifier.padding(horizontal = SIDE_PADDING), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VodSort.entries.forEach { sort ->
                            FilterChip(selected = section.sort == sort, onClick = { viewModel.setSort(id, sort) }) { Text(sortLabel(sort)) }
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
                    Row(modifier = Modifier.padding(horizontal = SIDE_PADDING), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        row.forEach { card -> TitleCardView(card, onClick = { onOpen(card) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = SIDE_PADDING))
}

@Composable
private fun CardRow(cards: List<VodCard>, onOpen: (VodCard) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = SIDE_PADDING), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        items(cards, key = { it.titleKey }) { card -> TitleCardView(card, onClick = { onOpen(card) }) }
    }
}

@Composable
private fun TitleCardView(card: VodCard, onClick: () -> Unit, subtitle: String? = null, progress: Float? = null) {
    Card(onClick = onClick, modifier = Modifier.width(CARD_WIDTH)) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color(0xFF202020))) {
                if (card.posterUrl != null) {
                    AsyncImage(
                        model = card.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                card.bestQuality?.badge()?.let { badge ->
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xCC000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (progress != null) {
                    Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                        Box(modifier = Modifier.fillMaxWidth(progress).height(4.dp).background(Color(0xFFE50914)))
                    }
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(text = card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = subtitle ?: card.year?.toString()
                detail?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private val CARD_WIDTH = 150.dp
private val CARD_HEIGHT = 225.dp
private val GAP = 16.dp
private val SIDE_PADDING = 48.dp

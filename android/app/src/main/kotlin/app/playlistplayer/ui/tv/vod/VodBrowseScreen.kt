package app.playlistplayer.ui.tv.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.vod.PlaylistSection
import app.playlistplayer.feature.vod.RailState
import app.playlistplayer.feature.vod.RailStatus
import app.playlistplayer.feature.vod.VodBrowseViewModel
import app.playlistplayer.feature.vod.VodCard
import coil3.compose.AsyncImage
import app.playlistplayer.ui.common.badge

/**
 * Movies / Series browse: a section per playlist name, then category rails of title cards.
 * Rails load as they scroll into view.
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
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            for (section in state.sections) {
                item(key = "header:${section.playlist.id}") { SectionHeader(section) }
                if (section.failed) {
                    item(key = "failed:${section.playlist.id}") {
                        CenteredMessage(stringResource(R.string.vod_section_failed)) {
                            Button(onClick = viewModel::refresh) { Text(stringResource(R.string.vod_retry)) }
                        }
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
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: PlaylistSection) {
    Text(
        text = section.playlist.name,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(horizontal = 48.dp),
    )
}

@Composable
private fun Rail(rail: RailState, onOpen: (VodCard) -> Unit, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = rail.category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 48.dp))
        when (rail.status) {
            RailStatus.FAILED -> Button(onClick = onRetry, modifier = Modifier.padding(horizontal = 48.dp)) {
                Text(stringResource(R.string.vod_retry))
            }
            RailStatus.LOADED -> LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(rail.cards, key = { it.titleKey }) { card -> TitleCardView(card, onClick = { onOpen(card) }) }
            }
            else -> Box(modifier = Modifier.height(CARD_HEIGHT).padding(horizontal = 48.dp), contentAlignment = Alignment.CenterStart) {
                Text(stringResource(R.string.vod_loading))
            }
        }
    }
}

@Composable
private fun TitleCardView(card: VodCard, onClick: () -> Unit) {
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
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(text = card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                card.year?.let { Text(text = it.toString(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private val CARD_WIDTH = 150.dp
private val CARD_HEIGHT = 225.dp

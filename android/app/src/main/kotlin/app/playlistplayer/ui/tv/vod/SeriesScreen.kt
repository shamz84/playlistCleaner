package app.playlistplayer.ui.tv.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.vod.EpisodeRow
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.feature.vod.SeriesViewModel
import kotlinx.coroutines.launch

/** Series detail: season tabs, then the episode list; versions per series source. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesScreen(viewModel: SeriesViewModel, onPlay: (PlaybackRequest) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (state.notFound) {
        CenteredMessage(stringResource(R.string.vod_title_not_found), modifier)
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        Backdrop(state.backdropUrl ?: state.posterUrl)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = state.title, style = MaterialTheme.typography.displaySmall)
                    Text(text = listOfNotNull(state.year?.toString(), state.genre).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
                    state.plot?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(onClick = viewModel::toggleFavourite) {
                        Text(stringResource(if (state.isFavourite) R.string.vod_favourite_remove else R.string.vod_favourite_add))
                    }
                }
            }
            state.playError?.let { error -> item { Text(text = playErrorText(error), color = MaterialTheme.colorScheme.error) } }
            item { ChipRow(stringResource(R.string.vod_filter_language), state.languageChips, { languageLabel(it) }, viewModel::selectLanguage) }
            item { ChipRow(stringResource(R.string.vod_filter_quality), state.qualityChips, { qualityLabel(it) }, viewModel::selectQuality) }
            if (state.versions.size > 1) {
                item { Text(text = stringResource(R.string.vod_versions), style = MaterialTheme.typography.titleMedium) }
                items(state.versions, key = { "v:${it.id}" }) { row -> VersionListItem(row, onClick = { viewModel.selectVersion(row.id) }) }
            }
            when {
                state.loading && state.episodes.isEmpty() -> item { Text(stringResource(R.string.vod_loading)) }
                state.episodesFailed -> item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.vod_episodes_failed))
                        if (state.episodesRetryable) Button(onClick = viewModel::retry) { Text(stringResource(R.string.vod_retry)) }
                    }
                }
                else -> {
                    if (state.failedSources > 0) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.vod_sources_failed, state.failedSources))
                                OutlinedButton(onClick = viewModel::retry) { Text(stringResource(R.string.vod_retry)) }
                            }
                        }
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.seasons, key = { it.number }) { season ->
                                FilterChip(selected = season.selected, onClick = { viewModel.selectSeason(season.number) }) {
                                    Text(
                                        season.name ?: if (season.number == 0) {
                                            stringResource(R.string.vod_specials)
                                        } else {
                                            stringResource(R.string.vod_season, season.number)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    items(state.episodes, key = { "e:${it.key.season}:${it.key.episode}" }) { row ->
                        EpisodeListItem(row, onClick = {
                            scope.launch { viewModel.playbackRequest(row.key)?.let(onPlay) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeListItem(row: EpisodeRow, onClick: () -> Unit) {
    val version = row.versions.firstOrNull { it.id == row.selectedVersionId }
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        headlineContent = {
            Text(
                text = stringResource(R.string.vod_episode, row.key.episode) + (row.title?.let { "  ·  $it" } ?: ""),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = listOfNotNull(
                    version?.let { languageLabel(it.language) },
                    version?.quality?.badge(),
                    row.durationSecs?.let { stringResource(R.string.vod_minutes, (it / 60).toInt()) },
                    when {
                        row.watched -> stringResource(R.string.vod_watched)
                        row.progressFraction != null -> "${(row.progressFraction * 100).toInt()}%"
                        else -> null
                    },
                ).joinToString("  ·  "),
                maxLines = 1,
            )
        },
    )
}

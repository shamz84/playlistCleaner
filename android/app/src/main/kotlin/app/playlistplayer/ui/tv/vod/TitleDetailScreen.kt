package app.playlistplayer.ui.tv.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.vod.PlayError
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.feature.vod.TitleDetailViewModel
import app.playlistplayer.feature.vod.VersionRow
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Movie detail: backdrop, plot, filter chips, version list, Play / Resume, favourite. */
@Composable
fun TitleDetailScreen(viewModel: TitleDetailViewModel, onPlay: (PlaybackRequest) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when {
        state.loading -> CenteredMessage(stringResource(R.string.vod_loading), modifier)
        state.notFound -> CenteredMessage(stringResource(R.string.vod_title_not_found), modifier)
        else -> Box(modifier = modifier.fillMaxSize()) {
            Backdrop(state.backdropUrl ?: state.posterUrl)
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = state.title, style = MaterialTheme.typography.displaySmall)
                        Text(
                            text = listOfNotNull(
                                state.year?.toString(),
                                state.genre,
                                state.durationSecs?.let { stringResource(R.string.vod_minutes, (it / 60).toInt()) },
                                state.rating?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
                                if (state.watched) stringResource(R.string.vod_watched) else null,
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        when {
                            state.plot != null -> Text(text = state.plot.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            state.detailsFailed -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.vod_details_failed))
                                OutlinedButton(onClick = viewModel::retryDetails) { Text(stringResource(R.string.vod_retry)) }
                            }
                        }
                        state.cast?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.resumePositionMs != null) {
                            Button(onClick = { scope.launch { viewModel.playbackRequest(resume = true)?.let(onPlay) } }) {
                                Text(stringResource(R.string.vod_resume) + "  " + formatClock(state.resumePositionMs ?: 0))
                            }
                            OutlinedButton(onClick = { scope.launch { viewModel.playbackRequest(resume = false)?.let(onPlay) } }) {
                                Text(stringResource(R.string.vod_start_over))
                            }
                        } else {
                            Button(onClick = { scope.launch { viewModel.playbackRequest(resume = false)?.let(onPlay) } }) {
                                Text(stringResource(R.string.vod_play))
                            }
                        }
                        OutlinedButton(onClick = viewModel::toggleFavourite) {
                            Text(stringResource(if (state.isFavourite) R.string.vod_favourite_remove else R.string.vod_favourite_add))
                        }
                    }
                }
                state.playError?.let { error ->
                    item { Text(text = playErrorText(error), color = MaterialTheme.colorScheme.error) }
                }
                item {
                    ChipRow(stringResource(R.string.vod_filter_language), state.languageChips, { languageLabel(it) }, viewModel::selectLanguage)
                }
                item {
                    ChipRow(stringResource(R.string.vod_filter_quality), state.qualityChips, { qualityLabel(it) }, viewModel::selectQuality)
                }
                item { Text(text = stringResource(R.string.vod_versions), style = MaterialTheme.typography.titleMedium) }
                items(state.versions, key = { it.id }) { row -> VersionListItem(row, onClick = { viewModel.selectVersion(row.id) }) }
            }
        }
    }
}

@Composable
internal fun Backdrop(url: String?) {
    if (url == null) return
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xF0000000), Color(0xC0000000), Color(0x40000000))),
            ),
        )
    }
}

@Composable
internal fun VersionListItem(row: VersionRow, onClick: () -> Unit) {
    ListItem(
        selected = row.isSelected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(text = row.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = listOfNotNull(
                    languageLabel(row.language),
                    row.quality.badge(),
                    if (row.hdr) "HDR" else null,
                    if (row.isDefault) stringResource(R.string.vod_version_default) else null,
                ).joinToString("  ·  "),
            )
        },
    )
}

@Composable
internal fun playErrorText(error: PlayError): String = when (error) {
    PlayError.NO_ACCOUNT -> stringResource(R.string.vod_no_account)
    PlayError.INVALID_STREAM -> stringResource(R.string.vod_invalid_stream)
}

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
import app.playlistplayer.feature.vod.TitleDetailUiState
import app.playlistplayer.feature.vod.TitleDetailViewModel
import app.playlistplayer.feature.vod.VersionRow
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import app.playlistplayer.ui.common.badge
import app.playlistplayer.ui.common.formatClock
import app.playlistplayer.ui.common.languageLabel

/** Movie detail: backdrop, plot, Play / Resume, favourite and a versions panel. */
@Composable
fun TitleDetailScreen(viewModel: TitleDetailViewModel, onPlay: (PlaybackRequest) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    TitleDetailContent(
        state = state,
        onPlay = { resume -> scope.launch { viewModel.playbackRequest(resume)?.let(onPlay) } },
        onFavourite = viewModel::toggleFavourite,
        onRetryDetails = viewModel::retryDetails,
        onLanguage = viewModel::selectLanguage,
        onQuality = viewModel::selectQuality,
        onVersion = viewModel::selectVersion,
        modifier = modifier,
    )
}

@Composable
internal fun TitleDetailContent(
    state: TitleDetailUiState,
    onPlay: (Boolean) -> Unit,
    onFavourite: () -> Unit,
    onRetryDetails: () -> Unit,
    onLanguage: (String) -> Unit,
    onQuality: (String) -> Unit,
    onVersion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                        Text(text = state.title, color = DetailTextColor, style = MaterialTheme.typography.displaySmall)
                        Text(
                            text = listOfNotNull(
                                state.year?.toString(),
                                state.genre,
                                state.durationSecs?.let { stringResource(R.string.vod_minutes, (it / 60).toInt()) },
                                state.rating?.let { String.format(java.util.Locale.ROOT, "%.1f", it) },
                                if (state.watched) stringResource(R.string.vod_watched) else null,
                            ).joinToString("  ·  "),
                            color = DetailTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        when {
                            state.plot != null -> Text(text = state.plot.orEmpty(), color = DetailTextColor, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            state.detailsFailed -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.vod_details_failed), color = DetailTextColor)
                                OutlinedButton(onClick = onRetryDetails) { Text(stringResource(R.string.vod_retry)) }
                            }
                        }
                        state.cast?.let { Text(text = it, color = DetailTextColor, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.resumePositionMs != null) {
                            Button(onClick = { onPlay(true) }) {
                                Text(stringResource(R.string.vod_resume) + "  " + formatClock(state.resumePositionMs))
                            }
                            OutlinedButton(onClick = { onPlay(false) }) {
                                Text(stringResource(R.string.vod_start_over))
                            }
                        } else {
                            Button(onClick = { onPlay(false) }) {
                                Text(stringResource(R.string.vod_play))
                            }
                        }
                        OutlinedButton(onClick = onFavourite) {
                            Text(stringResource(if (state.isFavourite) R.string.vod_favourite_remove else R.string.vod_favourite_add))
                        }
                        VersionsAndFilters(
                            state.languageChips, state.qualityChips, state.versions, state.loading,
                            onLanguage, onQuality, onVersion,
                        )
                    }
                }
                state.versions.firstOrNull { it.id == state.selectedVersionId }?.let { selected ->
                    item {
                        Text(
                            text = stringResource(R.string.vod_selected_version, selected.label),
                            color = DetailTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                state.playError?.let { error ->
                    item { Text(text = playErrorText(error), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

internal val DetailTextColor = Color.White
internal val DetailBackdropScrim = listOf(Color(0xF0000000), Color(0xE0000000), Color(0xD4000000))

@Composable
internal fun Backdrop(url: String?) {
    if (url == null) return
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(DetailBackdropScrim),
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

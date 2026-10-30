package app.playlistplayer.ui.mobile.vod

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.feature.vod.EpisodeKey
import app.playlistplayer.feature.vod.EpisodeRow
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.feature.vod.SeriesUiState
import app.playlistplayer.feature.vod.SeriesViewModel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileError
import app.playlistplayer.ui.mobile.MobileLoading
import app.playlistplayer.ui.mobile.MobileUiState
import kotlinx.coroutines.launch

object MobileSeriesTags {
    const val SEASONS = "mobile.series.seasons"
    const val EPISODES = "mobile.series.episodes"
    fun episode(key: EpisodeKey) = "mobile.series.episode.${key.season}.${key.episode}"
}

/**
 * Series on phone (R16) over [SeriesViewModel]: header, favourite, filter chips, the source
 * "Versions" sheet, season tabs and the episode list. Tapping an episode plays it with the sticky
 * version; an episode with several sources offers its own versions sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSeriesScreen(viewModel: SeriesViewModel, onPlay: (PlaybackRequest) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var versionsOpen by remember { mutableStateOf(false) }
    var episodeVersions by remember { mutableStateOf<EpisodeKey?>(null) }

    MobileUiState(state = MobileVodStates.series(state, stringResource(R.string.vod_title_not_found)), modifier = modifier) { s ->
        LazyColumn(
            modifier = modifier.fillMaxSize().testTag(MobileSeriesTags.EPISODES),
            contentPadding = PaddingValues(vertical = MobileDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
        ) {
            item { SeriesHeader(s) }
            item {
                Row(modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                    FavouriteButton(s.isFavourite, viewModel::toggleFavourite)
                    if (s.versions.size > 1) {
                        OutlinedButton(onClick = { versionsOpen = true }) { Text(stringResource(R.string.mobile_versions_button, s.versions.size)) }
                    }
                }
            }
            s.playError?.let { error -> item { PlayErrorText(error) } }
            item { MobileChipRow(stringResource(R.string.vod_filter_language), s.languageChips, { languageText(it) }, viewModel::selectLanguage) }
            item { MobileChipRow(stringResource(R.string.vod_filter_quality), s.qualityChips, { qualityText(it) }, viewModel::selectQuality) }
            when {
                s.loading && s.episodes.isEmpty() -> item { MobileLoading(Modifier.fillMaxWidth().sizeIn(minHeight = MobileDimens.MinTouchTarget * 3)) }
                s.episodesFailed -> item {
                    MobileError(
                        message = stringResource(R.string.vod_episodes_failed),
                        onRetry = if (s.episodesRetryable) viewModel::retry else null,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MobileDimens.MinTouchTarget * 4),
                    )
                }
                else -> {
                    if (s.failedSources > 0) {
                        item {
                            Row(modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                                Text(stringResource(R.string.vod_sources_failed, s.failedSources), color = UiTokens.Warning, modifier = Modifier.weight(1f))
                                TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.vod_retry)) }
                            }
                        }
                    }
                    if (s.seasons.isNotEmpty()) {
                        item {
                            val selectedIndex = s.seasons.indexOfFirst { it.selected }.coerceAtLeast(0)
                            PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, modifier = Modifier.testTag(MobileSeriesTags.SEASONS)) {
                                s.seasons.forEach { season ->
                                    Tab(
                                        selected = season.selected,
                                        onClick = { viewModel.selectSeason(season.number) },
                                        text = { Text(seasonLabel(season.name, season.number)) },
                                    )
                                }
                            }
                        }
                    }
                    items(s.episodes, key = { "e:${it.key.season}:${it.key.episode}" }) { row ->
                        EpisodeItem(
                            row = row,
                            onPlay = { scope.launch { viewModel.playbackRequest(row.key)?.let(onPlay) } },
                            onVersions = { episodeVersions = row.key },
                        )
                    }
                }
            }
        }
        if (versionsOpen) {
            OptionsSheet(
                title = stringResource(R.string.vod_versions),
                options = s.versions.map { it.toSheetOption() },
                onPick = viewModel::selectVersion,
                onDismiss = { versionsOpen = false },
            )
        }
        episodeVersions?.let { key ->
            // The row can vanish after a season switch or refresh; the sheet then simply closes.
            val row = s.episodes.firstOrNull { it.key == key }
            if (row != null) {
                OptionsSheet(
                    title = stringResource(R.string.mobile_episode_versions, key.episode),
                    options = row.versions.map { it.toSheetOption() },
                    onPick = { id -> scope.launch { viewModel.playbackRequest(key, versionId = id)?.let(onPlay) } },
                    onDismiss = { episodeVersions = null },
                )
            }
        }
    }
}

@Composable
private fun seasonLabel(name: String?, number: Int): String {
    val specials = stringResource(R.string.vod_specials)
    val season = stringResource(R.string.vod_season, number)
    return MobileVodStates.seasonLabel(name, number, specials) { season }
}

@Composable
private fun SeriesHeader(s: SeriesUiState) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = MobileDimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
        Poster(url = s.posterUrl, width = MobileDimens.DetailPosterWidth)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            Text(s.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            val meta = listOfNotNull(s.year?.toString(), s.genre).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = UiTokens.OnSurfaceMuted)
            s.plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun EpisodeItem(row: EpisodeRow, onPlay: () -> Unit, onVersions: () -> Unit) {
    val version = row.versions.firstOrNull { it.id == row.selectedVersionId }
    val headline = stringResource(R.string.vod_episode, row.key.episode) + (row.title?.let { "  ·  $it" } ?: "")
    val supporting = listOfNotNull(
        version?.let { languageText(it.language) },
        version?.quality?.mobileBadge(),
        row.durationSecs?.let { stringResource(R.string.vod_minutes, (it / 60).toInt()) },
        when {
            row.watched -> stringResource(R.string.vod_watched)
            row.progressFraction != null -> "${(row.progressFraction * 100).toInt()}%"
            else -> null
        },
    ).joinToString("  ·  ")
    val versionsDescription = stringResource(R.string.mobile_episode_versions, row.key.episode)
    ListItem(
        headlineContent = { Text(headline, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = row.progressFraction
                if (fraction != null && !row.watched) {
                    LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = UiTokens.SpaceXs))
                }
            }
        },
        trailingContent = if (row.versions.size > 1) {
            {
                TextButton(
                    onClick = onVersions,
                    modifier = Modifier
                        .sizeIn(minWidth = MobileDimens.MinTouchTarget, minHeight = MobileDimens.MinTouchTarget)
                        .semantics { contentDescription = versionsDescription },
                ) { Text(row.versions.size.toString() + " ▾") }
            }
        } else {
            null
        },
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.vod_play), onClick = onPlay)
            .testTag(MobileSeriesTags.episode(row.key)),
    )
}

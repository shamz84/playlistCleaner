package app.playlistplayer.ui.mobile.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.feature.vod.PlayError
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.feature.vod.TitleDetailUiState
import app.playlistplayer.feature.vod.TitleDetailViewModel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileUiState
import kotlinx.coroutines.launch
import java.util.Locale

object MobileTitleDetailTags {
    const val PLAY = "mobile.detail.play"
    const val VERSIONS = "mobile.detail.versions"
    const val FAVOURITE = "mobile.detail.favourite"
}

/**
 * Movie detail on phone (R12–R15) over [TitleDetailViewModel]: poster and details, Play / Resume /
 * Start over, favourite, language and quality filter chips, and the version list in a "Versions"
 * bottom sheet (picking one stores it as this title's default, as on TV).
 */
@Composable
fun MobileTitleDetailScreen(viewModel: TitleDetailViewModel, onPlay: (PlaybackRequest) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var versionsOpen by remember { mutableStateOf(false) }
    val play: (Boolean) -> Unit = { resume -> scope.launch { viewModel.playbackRequest(resume)?.let(onPlay) } }

    MobileUiState(state = MobileVodStates.movie(state, stringResource(R.string.vod_title_not_found)), modifier = modifier) { s ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = MobileDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            item { Header(s) }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
                ) {
                    if (s.resumePositionMs != null) {
                        Button(onClick = { play(true) }, modifier = Modifier.testTag(MobileTitleDetailTags.PLAY)) {
                            Text(stringResource(R.string.vod_resume) + "  " + clockText(s.resumePositionMs))
                        }
                        OutlinedButton(onClick = { play(false) }) { Text(stringResource(R.string.vod_start_over)) }
                    } else {
                        Button(onClick = { play(false) }, modifier = Modifier.testTag(MobileTitleDetailTags.PLAY)) {
                            Text(stringResource(R.string.vod_play))
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
                ) {
                    FavouriteButton(s.isFavourite, viewModel::toggleFavourite)
                    if (s.versions.size > 1) {
                        OutlinedButton(onClick = { versionsOpen = true }, modifier = Modifier.testTag(MobileTitleDetailTags.VERSIONS)) {
                            Text(stringResource(R.string.mobile_versions_button, s.versions.size))
                        }
                    }
                }
            }
            s.playError?.let { error -> item { PlayErrorText(error) } }
            item { Details(s, onRetry = viewModel::retryDetails) }
            item { MobileChipRow(stringResource(R.string.vod_filter_language), s.languageChips, { languageText(it) }, viewModel::selectLanguage) }
            item { MobileChipRow(stringResource(R.string.vod_filter_quality), s.qualityChips, { qualityText(it) }, viewModel::selectQuality) }
            s.versions.firstOrNull { it.isSelected }?.let { selected ->
                item {
                    Column(modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding)) {
                        Text(stringResource(R.string.vod_versions), style = MaterialTheme.typography.labelLarge, color = UiTokens.OnSurfaceMuted)
                        Text(selected.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(versionSupporting(selected), style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
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
    }
}

@Composable
private fun Header(s: TitleDetailUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MobileDimens.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        Poster(url = s.posterUrl, width = MobileDimens.DetailPosterWidth)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            Text(s.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            val meta = listOfNotNull(
                s.year?.toString(),
                s.genre,
                s.durationSecs?.let { stringResource(R.string.vod_minutes, (it / 60).toInt()) },
                s.rating?.let { String.format(Locale.ROOT, "%.1f", it) },
                if (s.watched) stringResource(R.string.vod_watched) else null,
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = UiTokens.OnSurfaceMuted)
        }
    }
}

@Composable
private fun Details(s: TitleDetailUiState, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
        when {
            s.plot != null -> Text(s.plot.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            s.detailsFailed -> Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                Text(stringResource(R.string.vod_details_failed), color = UiTokens.OnSurfaceMuted, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.vod_retry)) }
            }
        }
        s.director?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted) }
        s.cast?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun FavouriteButton(isFavourite: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (isFavourite) R.string.mobile_favourite_on else R.string.mobile_favourite_off)
    OutlinedButton(
        onClick = onToggle,
        modifier = Modifier
            .sizeIn(minHeight = MobileDimens.MinTouchTarget)
            .testTag(MobileTitleDetailTags.FAVOURITE)
            .semantics { stateDescription = state },
    ) {
        Text((if (isFavourite) "★ " else "☆ ") + stringResource(if (isFavourite) R.string.vod_favourite_remove else R.string.vod_favourite_add))
    }
}

@Composable
internal fun PlayErrorText(error: PlayError) {
    Text(
        text = stringResource(
            when (error) {
                PlayError.NO_ACCOUNT -> R.string.vod_no_account
                PlayError.INVALID_STREAM -> R.string.vod_invalid_stream
            },
        ),
        color = UiTokens.Error,
        modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding).semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

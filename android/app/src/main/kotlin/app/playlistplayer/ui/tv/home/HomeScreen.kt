package app.playlistplayer.ui.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.home.ContinueItem
import app.playlistplayer.feature.home.HomeRow
import app.playlistplayer.feature.home.HomeViewModel
import app.playlistplayer.feature.library.SavedChannel
import app.playlistplayer.feature.live.SavedTitle
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvEmptyState
import app.playlistplayer.ui.tv.components.TvLoadingState
import app.playlistplayer.ui.tv.search.TvCardRow
import app.playlistplayer.ui.tv.search.TvChannelCard
import app.playlistplayer.ui.tv.search.TvPosterCard
import app.playlistplayer.ui.tv.search.TvRowHeading
import app.playlistplayer.ui.tv.theme.TvDimens

/**
 * TV Home (U11, cold-start landing): Continue watching, Favourite channels, Favourite titles,
 * Recently watched, then each playlist's live groups. Empty rows are not shown; with no
 * personal rows yet a first-run hint explains how Home fills up. Continue watching reloads each
 * time Home is shown.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onResume: (ContinueItem) -> Unit,
    onPlayChannel: (SavedChannel) -> Unit,
    onOpenTitle: (SavedTitle) -> Unit,
    onOpenGroup: (GroupItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    when {
        state.loading -> TvLoadingState(modifier.fillMaxSize())
        state.rows.isEmpty() -> TvEmptyState(
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            modifier = modifier.fillMaxSize(),
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = UiTokens.OverscanVertical),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceL),
        ) {
            if (state.showFirstRunHint) {
                item(key = "hint") {
                    TvEmptyState(
                        title = stringResource(R.string.home_first_run_title),
                        message = stringResource(R.string.home_first_run_message),
                        focusable = false,
                        modifier = Modifier.padding(horizontal = UiTokens.OverscanHorizontal),
                    )
                }
            }
            items(state.rows, key = { it.rowKey() }) { row -> HomeRowView(row, onResume, onPlayChannel, onOpenTitle, onOpenGroup) }
        }
    }
}

private fun HomeRow.rowKey(): String = when (this) {
    is HomeRow.ContinueWatching -> "continue"
    is HomeRow.FavouriteChannels -> "fav-channels"
    is HomeRow.FavouriteTitles -> "fav-titles"
    is HomeRow.RecentChannels -> "recent"
    is HomeRow.LiveGroups -> "live:${section.playlistId}"
}

@Composable
private fun HomeRowView(
    row: HomeRow,
    onResume: (ContinueItem) -> Unit,
    onPlayChannel: (SavedChannel) -> Unit,
    onOpenTitle: (SavedTitle) -> Unit,
    onOpenGroup: (GroupItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        when (row) {
            is HomeRow.ContinueWatching -> {
                TvRowHeading(stringResource(R.string.home_continue_watching))
                TvCardRow {
                    items(row.items, key = { "${it.progress.key}" }) { item ->
                        val percent = (item.fraction * 100).toInt()
                        TvPosterCard(
                            title = item.title.title,
                            subtitle = item.progress.label.takeIf { it != item.title.title },
                            posterUrl = item.title.posterUrl,
                            progress = item.fraction,
                            description = listOfNotNull(
                                item.title.title,
                                item.progress.label.takeIf { it != item.title.title },
                                stringResource(R.string.home_progress_description, percent),
                            ).joinToString(", "),
                            onClick = { onResume(item) },
                        )
                    }
                }
            }
            is HomeRow.FavouriteChannels -> ChannelRow(stringResource(R.string.home_favourite_channels), row.channels, onPlayChannel)
            is HomeRow.RecentChannels -> ChannelRow(stringResource(R.string.home_recent_channels), row.channels, onPlayChannel)
            is HomeRow.FavouriteTitles -> {
                TvRowHeading(stringResource(R.string.home_favourite_titles))
                TvCardRow {
                    items(row.titles, key = { it.titleKey }) { title ->
                        TvPosterCard(title = title.title, subtitle = title.year?.toString(), posterUrl = title.posterUrl, onClick = { onOpenTitle(title) })
                    }
                }
            }
            is HomeRow.LiveGroups -> {
                TvRowHeading(stringResource(R.string.home_live_groups, row.section.name))
                TvCardRow {
                    items(row.section.groups, key = { "${it.playlistId}:${it.ordinal}" }) { group -> GroupCard(group, onClick = { onOpenGroup(group) }) }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(title: String, channels: List<SavedChannel>, onPlayChannel: (SavedChannel) -> Unit) {
    TvRowHeading(title)
    TvCardRow {
        items(channels, key = { "${it.playlistId}:${it.streamKey}" }) { channel ->
            TvChannelCard(name = channel.name, subtitle = channel.groupTitle, logo = channel.logo, onClick = { onPlayChannel(channel) })
        }
    }
}

@Composable
private fun GroupCard(group: GroupItem, onClick: () -> Unit) {
    val count = stringResource(R.string.home_group_channels, group.channelCount)
    Card(
        onClick = onClick,
        modifier = Modifier.width(220.dp).semantics(mergeDescendants = true) { contentDescription = "${group.name}, $count" },
        scale = CardDefaults.scale(focusedScale = UiTokens.FocusScale),
        border = CardDefaults.border(focusedBorder = TvDimens.FocusBorder),
    ) {
        Column(modifier = Modifier.padding(UiTokens.SpaceM), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            Text(text = group.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(text = count, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
        }
    }
}

package app.playlistplayer.ui.mobile.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.feature.home.ContinueItem
import app.playlistplayer.feature.home.HomeRow
import app.playlistplayer.feature.home.HomeViewModel
import app.playlistplayer.feature.library.SavedChannel
import app.playlistplayer.feature.live.SavedTitle
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileEmpty
import app.playlistplayer.ui.mobile.MobileLoading
import app.playlistplayer.ui.mobile.search.MobileCardRow
import app.playlistplayer.ui.mobile.search.MobileChannelCard
import app.playlistplayer.ui.mobile.search.MobilePosterCard
import app.playlistplayer.ui.mobile.search.MobileRowHeading

object MobileHomeTags {
    const val LIST = "mobile.home.list"
    const val HINT = "mobile.home.hint"
}

/**
 * Phone Home (U11) over the same [HomeViewModel] as TV: Continue watching, Favourite channels,
 * Favourite titles, Recently watched, then each playlist's live groups. Empty rows are hidden;
 * a first-run hint shows until something is watched or favourited.
 */
@Composable
fun MobileHomeScreen(
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
        state.loading -> MobileLoading(modifier)
        state.rows.isEmpty() -> MobileEmpty(
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            modifier = modifier.fillMaxSize(),
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize().testTag(MobileHomeTags.LIST),
            contentPadding = PaddingValues(vertical = MobileDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceL),
        ) {
            if (state.showFirstRunHint) {
                item(key = "hint") {
                    MobileEmpty(
                        title = stringResource(R.string.home_first_run_title),
                        message = stringResource(R.string.home_first_run_message),
                        modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding).testTag(MobileHomeTags.HINT),
                    )
                }
            }
            items(state.rows, key = { it.key() }) { row -> RowView(row, onResume, onPlayChannel, onOpenTitle, onOpenGroup) }
        }
    }
}

private fun HomeRow.key(): String = when (this) {
    is HomeRow.ContinueWatching -> "continue"
    is HomeRow.FavouriteChannels -> "fav-channels"
    is HomeRow.FavouriteTitles -> "fav-titles"
    is HomeRow.RecentChannels -> "recent"
    is HomeRow.LiveGroups -> "live:${section.playlistId}"
}

@Composable
private fun RowView(
    row: HomeRow,
    onResume: (ContinueItem) -> Unit,
    onPlayChannel: (SavedChannel) -> Unit,
    onOpenTitle: (SavedTitle) -> Unit,
    onOpenGroup: (GroupItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        when (row) {
            is HomeRow.ContinueWatching -> {
                MobileRowHeading(stringResource(R.string.home_continue_watching))
                MobileCardRow {
                    items(row.items, key = { "${it.progress.key}" }) { item ->
                        val episode = item.progress.label.takeIf { it != item.title.title }
                        MobilePosterCard(
                            title = item.title.title,
                            subtitle = episode,
                            posterUrl = item.title.posterUrl,
                            progress = item.fraction,
                            description = listOfNotNull(
                                item.title.title,
                                episode,
                                stringResource(R.string.home_progress_description, (item.fraction * 100).toInt()),
                            ).joinToString(", "),
                            onClick = { onResume(item) },
                        )
                    }
                }
            }
            is HomeRow.FavouriteChannels -> Channels(stringResource(R.string.home_favourite_channels), row.channels, onPlayChannel)
            is HomeRow.RecentChannels -> Channels(stringResource(R.string.home_recent_channels), row.channels, onPlayChannel)
            is HomeRow.FavouriteTitles -> {
                MobileRowHeading(stringResource(R.string.home_favourite_titles))
                MobileCardRow {
                    items(row.titles, key = { it.titleKey }) { title ->
                        MobilePosterCard(title = title.title, subtitle = title.year?.toString(), posterUrl = title.posterUrl, onClick = { onOpenTitle(title) })
                    }
                }
            }
            is HomeRow.LiveGroups -> {
                MobileRowHeading(stringResource(R.string.home_live_groups, row.section.name))
                MobileCardRow {
                    items(row.section.groups, key = { "${it.playlistId}:${it.ordinal}" }) { group -> GroupCard(group) { onOpenGroup(group) } }
                }
            }
        }
    }
}

@Composable
private fun Channels(title: String, channels: List<SavedChannel>, onPlayChannel: (SavedChannel) -> Unit) {
    MobileRowHeading(title)
    MobileCardRow {
        items(channels, key = { "${it.playlistId}:${it.streamKey}" }) { channel ->
            MobileChannelCard(name = channel.name, subtitle = channel.groupTitle, logo = channel.logo, onClick = { onPlayChannel(channel) })
        }
    }
}

@Composable
private fun GroupCard(group: GroupItem, onClick: () -> Unit) {
    val count = stringResource(R.string.home_group_channels, group.channelCount)
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(180.dp)
            .sizeIn(minHeight = MobileDimens.MinTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = "${group.name}, $count" },
        colors = CardDefaults.cardColors(containerColor = UiTokens.Surface),
    ) {
        Column(modifier = Modifier.padding(UiTokens.SpaceS)) {
            Text(text = group.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(text = count, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
        }
    }
}

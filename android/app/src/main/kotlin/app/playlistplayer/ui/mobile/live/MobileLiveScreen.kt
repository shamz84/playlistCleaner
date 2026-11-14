package app.playlistplayer.ui.mobile.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseState
import app.playlistplayer.ui.common.live.LiveBrowseViewModel
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileUiState

object MobileLiveTags {
    const val GROUPS = "mobile.live.groups"
    const val CHANNELS = "mobile.live.channels"
    fun section(playlistId: Long) = "mobile.live.section.$playlistId"
}

/**
 * Live browse on phone (R25): playlist-name sections (collapsible) → groups → channels, over the
 * shared [LiveBrowseViewModel]. Compact width shows groups, then the tapped group's channels
 * (Back returns to the groups); wide windows show both panes side by side. Tapping a channel
 * plays it with the group as context, so previous / next channel stays within the group.
 */
@Composable
fun MobileLiveScreen(
    viewModel: LiveBrowseViewModel,
    wide: Boolean,
    onPlay: (channels: List<ChannelRow>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showChannels by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<Long>()) }

    BackHandler(enabled = !wide && showChannels) { showChannels = false }

    MobileUiState(state = state.sections, modifier = modifier) { sections ->
        val groups = @Composable { m: Modifier ->
            GroupList(
                sections = sections,
                selected = state.selectedGroup.takeIf { wide },
                collapsed = collapsed.toSet(),
                onToggleSection = { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id },
                onOpenGroup = { key ->
                    viewModel.selectGroup(key)
                    showChannels = true
                },
                modifier = m,
            )
        }
        when {
            wide -> Row(modifier = modifier.fillMaxSize()) {
                groups(Modifier.weight(0.42f).fillMaxHeight())
                VerticalDivider()
                ChannelPane(state, sections, showHeader = false, onPlay = onPlay, modifier = Modifier.weight(0.58f).fillMaxHeight())
            }
            showChannels -> ChannelPane(
                state,
                sections,
                showHeader = true,
                onPlay = onPlay,
                onBack = { showChannels = false },
                modifier = modifier.fillMaxSize(),
            )
            else -> groups(modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun GroupList(
    sections: List<PlaylistSection>,
    selected: GroupKey?,
    collapsed: Set<Long>,
    onToggleSection: (Long) -> Unit,
    onOpenGroup: (GroupKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.live_groups_description)
    LazyColumn(modifier = modifier.testTag(MobileLiveTags.GROUPS).semantics { contentDescription = description }) {
        sections.forEach { section ->
            val isCollapsed = section.playlistId in collapsed
            item(key = "section-${section.playlistId}", contentType = "header") {
                SectionHeader(section, isCollapsed, onToggle = { onToggleSection(section.playlistId) })
            }
            if (!isCollapsed) {
                section.groups.forEach { group ->
                    item(key = "group-${group.playlistId}-${group.ordinal}", contentType = "group") {
                        GroupRow(group, selected = group.key == selected, onOpen = { onOpenGroup(group.key) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: PlaylistSection, collapsed: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (collapsed) R.string.mobile_section_collapsed else R.string.mobile_section_expanded)
    val description = stringResource(R.string.mobile_section_description, section.name, section.groups.size)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MobileDimens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onToggle)
            .background(UiTokens.Surface)
            .padding(horizontal = MobileDimens.ScreenPadding)
            .testTag(MobileLiveTags.section(section.playlistId))
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = description
                stateDescription = state
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
    ) {
        Text(
            text = section.name,
            style = MaterialTheme.typography.titleMedium,
            color = UiTokens.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = if (collapsed) "▸" else "▾", style = MaterialTheme.typography.titleMedium, color = UiTokens.OnSurfaceMuted)
    }
}

@Composable
internal fun GroupRow(group: GroupItem, selected: Boolean, onOpen: () -> Unit) {
    val description = stringResource(R.string.live_group_item_description, group.name, group.channelCount)
    ListItem(
        headlineContent = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ListItemDefaults.colors(containerColor = if (selected) UiTokens.SurfaceFocused else UiTokens.Background),
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = description },
    )
}

@Composable
private fun ChannelPane(
    state: LiveBrowseState,
    sections: List<PlaylistSection>,
    showHeader: Boolean,
    onPlay: (List<ChannelRow>, Int) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        if (showHeader) {
            val key = state.selectedGroup
            val section = sections.firstOrNull { it.playlistId == key?.playlistId }
            val group = section?.groups?.firstOrNull { it.key == key }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    TextButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = MobileDimens.MinTouchTarget, minHeight = MobileDimens.MinTouchTarget)) {
                        Text("‹ " + stringResource(R.string.live_groups_description))
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = UiTokens.SpaceS)) {
                    section?.let { Text(it.name, style = MaterialTheme.typography.labelMedium, color = UiTokens.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    group?.let {
                        Text(
                            it.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            MobileUiState(state = state.channels) { rows -> ChannelList(rows, onPlay) }
        }
    }
}

@Composable
private fun ChannelList(rows: List<ChannelRow>, onPlay: (List<ChannelRow>, Int) -> Unit) {
    val description = stringResource(R.string.live_channels_description)
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(MobileLiveTags.CHANNELS).semantics { contentDescription = description }) {
        itemsIndexed(rows, key = { _, row -> "${row.playlistId}-${row.ordinal}" }, contentType = { _, row -> row.isHeader }) { index, row ->
            if (row.isHeader) {
                // Separator entries are listed (R11) but are not playable.
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = UiTokens.OnSurfaceMuted,
                    modifier = Modifier
                        .padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS)
                        .semantics { heading() },
                )
            } else {
                ChannelItem(row, onClick = { onPlay(rows, index) })
            }
        }
    }
}

@Composable
private fun ChannelItem(row: ChannelRow, onClick: () -> Unit) {
    val number = row.number ?: 0
    val description = if (row.hasCatchup) {
        stringResource(R.string.live_channel_catchup_description, number, row.name)
    } else {
        stringResource(R.string.live_channel_item_description, number, row.name)
    }
    ListItem(
        leadingContent = {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = UiTokens.OnSurfaceMuted, modifier = Modifier.width(44.dp))
        },
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (row.hasCatchup) {
            {
                Text(
                    text = stringResource(R.string.live_catchup_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.OnAccent,
                    modifier = Modifier.background(UiTokens.Accent, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else {
            null
        },
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    )
}

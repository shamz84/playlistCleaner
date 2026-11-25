package app.playlistplayer.ui.mobile.livetv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as storedViewModel
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.live.LiveTvViewModel
import app.playlistplayer.ui.mobile.MobileUiState
import app.playlistplayer.ui.mobile.epg.GroupButton
import app.playlistplayer.ui.mobile.epg.GuideGeometry
import app.playlistplayer.ui.mobile.epg.MobileGuideDependencies
import app.playlistplayer.ui.mobile.epg.MobileGuideGrid
import app.playlistplayer.ui.mobile.epg.guideGroupLabel
import app.playlistplayer.ui.mobile.live.GroupList

object MobileLiveTvTags {
    const val ROOT = "mobile.livetv"
    const val GRID_PANE = "mobile.livetv.grid"
}

/**
 * Phone Live TV (the former Live and Guide tabs): the group list sectioned by playlist name
 * (collapsible, R25) and the touch guide grid of the selected group ([MobileGuideGrid] over the
 * shared [EpgGridViewModel], one per group kept while the app runs).
 *
 * Compact width shows the grid of the selected group with a group button above it; the button
 * opens the group list, and tapping a group shows its grid (Back returns to the grid). Wide windows
 * show the group list beside the grid. Tapping a channel name (or a "No guide data" row) plays the
 * channel live via [onPlayChannel]; programme taps keep the guide's routing. On first open the
 * group of the last channel played is selected.
 */
@Composable
fun MobileLiveTvScreen(
    viewModel: LiveTvViewModel,
    guide: MobileGuideDependencies,
    wide: Boolean,
    onPlayChannel: (LiveChannel) -> Unit,
    onPlayCatchup: (CatchupRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showGroups by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var entryReturnPending by remember { mutableStateOf(true) }
    val onGridReady: (EpgGridViewModel) -> Unit = { grid ->
        if (entryReturnPending) {
            entryReturnPending = false
            if (grid.state.value.rows.isNotEmpty()) grid.onReturn()
        }
    }
    val group = state.selectedGroup

    BackHandler(enabled = !wide && showGroups && group != null) { showGroups = false }

    MobileUiState(state = state.sections, modifier = modifier.testTag(MobileLiveTvTags.ROOT)) { sections ->
        val groups = @Composable { m: Modifier ->
            GroupList(
                sections = sections,
                selected = group,
                collapsed = collapsed.toSet(),
                onToggleSection = { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id },
                onOpenGroup = { key ->
                    viewModel.selectGroup(key)
                    showGroups = false
                },
                modifier = m,
            )
        }
        when {
            wide -> Row(modifier = Modifier.fillMaxSize()) {
                groups(Modifier.weight(0.35f).fillMaxHeight())
                VerticalDivider()
                GridPane(viewModel, guide, sections, group, state.restoreChannel, onPlayChannel, onPlayCatchup, onGridReady, onChangeGroup = null, Modifier.weight(0.65f).fillMaxHeight())
            }
            showGroups || group == null -> groups(Modifier.fillMaxSize())
            else -> GridPane(viewModel, guide, sections, group, state.restoreChannel, onPlayChannel, onPlayCatchup, onGridReady, onChangeGroup = { showGroups = true }, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GridPane(
    viewModel: LiveTvViewModel,
    guide: MobileGuideDependencies,
    sections: List<PlaylistSection>,
    group: GroupKey?,
    restoreChannel: Int?,
    onPlayChannel: (LiveChannel) -> Unit,
    onPlayCatchup: (CatchupRequest) -> Unit,
    onGridReady: (EpgGridViewModel) -> Unit,
    onChangeGroup: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier = modifier.testTag(MobileLiveTvTags.GRID_PANE)) {
        if (group == null) return@Column
        if (onChangeGroup != null) GroupButton(guideGroupLabel(sections, group), onClick = onChangeGroup)
        val grid = storedViewModel(key = "mobile-livetv-${group.playlistId}-${group.groupOrdinal}") {
            EpgGridViewModel(
                group = group,
                channels = guide.channels,
                grid = guide.grid,
                otherVersions = guide.coordinator,
                clock = guide.clock,
                zone = guide.zone,
                windowSlots = GuideGeometry.PHONE_WINDOW_SLOTS,
            )
        }
        LaunchedEffect(grid) { onGridReady(grid) }
        LaunchedEffect(grid, restoreChannel) {
            if (restoreChannel == null) return@LaunchedEffect
            grid.onFocus(restoreChannel, null)
            viewModel.onRestoreHandled()
        }
        // Scroll positions belong to the group: a new group opens at its top and at now.
        key(group) {
            MobileGuideGrid(grid, onPlayCatchup = onPlayCatchup, onWatchLive = onPlayChannel, modifier = Modifier.weight(1f), resetOnEntry = false)
        }
    }
}

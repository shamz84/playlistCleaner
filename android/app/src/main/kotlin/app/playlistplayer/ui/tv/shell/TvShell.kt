package app.playlistplayer.ui.tv.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.feature.shell.ShellViewModel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.NavigationIcon
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.theme.TvDebugColors
import app.playlistplayer.ui.tv.theme.TvDimens
import app.playlistplayer.ui.tv.theme.debugTint

private val RAIL_WIDTH = 52.dp

object ShellTags {
    const val RAIL = "shell.rail"
    const val CONTENT = "shell.content"
    const val EXIT_DIALOG = "shell.exitDialog"
    fun railItem(destination: Destination) = "shell.rail.${destination.name}"
}

@StringRes
fun Destination.labelRes(): Int = when (this) {
    Destination.LiveTv -> R.string.nav_live
    Destination.Home -> R.string.nav_home
    Destination.Movies -> R.string.nav_movies
    Destination.Series -> R.string.nav_series
    Destination.Search -> R.string.nav_search
    Destination.Settings -> R.string.nav_settings
}

/**
 * TV shell (U9): collapsible side navigation rail (tv-material [NavigationDrawer]; it expands while
 * it holds focus) and the current destination's content.
 *
 * Back handling: a destination's own BackHandlers run first (composed later, so they win); the
 * shell's handler then moves focus from content back to the rail item focused last, and on the
 * rail asks "Exit app?". [onExit] runs when the user confirms.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvShell(
    viewModel: ShellViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Destination) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val railRequesters = remember { Destination.entries.associateWith { FocusRequester() } }
    val contentRequester = remember { FocusRequester() }

    BackHandler(enabled = !state.showExitConfirmation) { viewModel.onBack() }

    // Cold start: focus enters Live TV's content; if it has nothing focusable, fall back to the rail.
    LaunchedEffect(Unit) {
        val entered = runCatching { contentRequester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        if (!entered) runCatching { railRequesters.getValue(state.lastRailFocus).requestFocus() }
    }
    // Back from a destination: restore focus to the rail item focused last.
    LaunchedEffect(state.railFocusRequest) {
        if (state.railFocusRequest > 0) runCatching { railRequesters.getValue(state.lastRailFocus).requestFocus() }
    }
    LaunchedEffect(state.exitRequested) {
        if (state.exitRequested) onExit()
    }

    val railDescription = stringResource(R.string.nav_rail_description)
    Row(modifier = modifier.fillMaxSize().background(UiTokens.Background)) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(RAIL_WIDTH)
                .debugTint(TvDebugColors.Rail)
                .padding(horizontal = 4.dp)
                .testTag(ShellTags.RAIL)
                .semantics { contentDescription = railDescription }
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Destination.entries.forEach { destination ->
                val label = stringResource(destination.labelRes())
                val description = stringResource(R.string.nav_item_description, label)
                val selected = state.current == destination
                Surface(
                    onClick = { viewModel.select(destination) },
                    modifier = Modifier
                        .size(44.dp)
                        .focusRequester(railRequesters.getValue(destination))
                        .onFocusChanged { if (it.isFocused) viewModel.onRailItemFocused(destination) }
                        .testTag(ShellTags.railItem(destination))
                        .semantics { contentDescription = description },
                    shape = ClickableSurfaceDefaults.shape(TvDimens.Shape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = UiTokens.FocusScale),
                    border = ClickableSurfaceDefaults.border(focusedBorder = TvDimens.FocusBorder),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) UiTokens.SurfaceVariant else Color.Transparent,
                        focusedContainerColor = UiTokens.SurfaceFocused,
                    ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NavigationIcon(destination, tint = if (selected) UiTokens.Accent else UiTokens.OnBackground)
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(contentRequester)
                .focusRestorer()
                .focusGroup()
                .onFocusChanged { if (it.hasFocus) viewModel.onContentFocused() }
                .testTag(ShellTags.CONTENT),
        ) {
            content(state.current)
        }
    }

    if (state.showExitConfirmation) {
        ExitDialog(onConfirm = viewModel::confirmExit, onDismiss = viewModel::dismissExit)
    }
}

@Composable
private fun ExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).testTag(ShellTags.EXIT_DIALOG),
            shape = TvDimens.Shape,
            colors = SurfaceDefaults.colors(containerColor = UiTokens.SurfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(UiTokens.SpaceXl),
                verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
            ) {
                Text(stringResource(R.string.exit_title), style = MaterialTheme.typography.headlineSmall, color = UiTokens.OnBackground)
                Text(stringResource(R.string.exit_message), style = MaterialTheme.typography.bodyLarge, color = UiTokens.OnSurfaceMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
                    // Cancel takes focus first: an accidental double Back must not close the app.
                    TvButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(cancelFocus),
                    )
                    TvButton(text = stringResource(R.string.exit_confirm), onClick = onConfirm)
                }
            }
        }
    }
}

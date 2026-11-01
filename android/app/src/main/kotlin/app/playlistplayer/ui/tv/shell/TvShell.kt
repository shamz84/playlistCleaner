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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.feature.shell.ShellViewModel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.theme.TvDimens

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

/** Rail glyphs (no icon library dependency; plain Unicode symbols render on Fire OS). */
private fun Destination.glyph(): String = when (this) {
    Destination.LiveTv -> "▶"
    Destination.Home -> "⌂"
    Destination.Movies -> "🎬"
    Destination.Series -> "🎞"
    Destination.Search -> "🔍"
    Destination.Settings -> "⚙"
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
    NavigationDrawer(
        modifier = modifier.fillMaxSize().background(UiTokens.Background),
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = UiTokens.SpaceL, end = UiTokens.SpaceS)
                    .padding(vertical = UiTokens.OverscanVertical)
                    .testTag(ShellTags.RAIL)
                    .semantics { contentDescription = railDescription }
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS, Alignment.CenterVertically),
            ) {
                Destination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes())
                    val description = stringResource(R.string.nav_item_description, label)
                    NavigationDrawerItem(
                        selected = state.current == destination,
                        onClick = { viewModel.select(destination) },
                        leadingContent = { Text(destination.glyph(), style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier
                            .focusRequester(railRequesters.getValue(destination))
                            .onFocusChanged { if (it.isFocused) viewModel.onRailItemFocused(destination) }
                            .testTag(ShellTags.railItem(destination))
                            .semantics { contentDescription = description },
                        scale = NavigationDrawerItemDefaults.scale(focusedScale = UiTokens.FocusScale),
                        border = NavigationDrawerItemDefaults.border(
                            focusedBorder = TvDimens.FocusBorder,
                            focusedSelectedBorder = TvDimens.FocusBorder,
                        ),
                    ) {
                        Text(label)
                    }
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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

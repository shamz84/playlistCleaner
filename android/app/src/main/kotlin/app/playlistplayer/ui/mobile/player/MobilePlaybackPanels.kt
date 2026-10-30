package app.playlistplayer.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.playlistplayer.R
import app.playlistplayer.feature.live.PlaybackErrorPanel
import app.playlistplayer.feature.live.PlaybackStatus

/**
 * A [ViewModelStoreOwner] that lives exactly as long as the calling composition. Used for the
 * guide-driven live player: when a catch-up player covers it, its ViewModel is cleared, and a fresh
 * one re-tunes the channel when the screen shows again (the shared ViewModel has no "resume live"
 * call, and the driver's retry would replay the catch-up session).
 */
@Composable
internal fun rememberCompositionViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

/**
 * False during the first composition, true from the frame after it. The shared player ViewModels
 * start playback from `init`; creating them only after the previous player screen's `onDispose`
 * (which stops the driver) has run keeps that stop from cancelling the new stream.
 */
@Composable
internal fun rememberAfterFirstComposition(): Boolean {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = true }
    return ready
}

@Composable
internal fun indicatorText(indicator: PlaybackStatus.Indicator): String = when (indicator) {
    PlaybackStatus.Indicator.BUFFERING -> stringResource(R.string.player_buffering)
    PlaybackStatus.Indicator.RECONNECTING -> stringResource(R.string.player_reconnecting)
    PlaybackStatus.Indicator.WAITING_FOR_NETWORK -> stringResource(R.string.player_waiting_for_network)
}

/** Buffering / reconnecting indicator, or the transient "Switched to …" notice (U6 mapping via [PlaybackStatus]). */
@Composable
internal fun PlaybackStatusBanner(status: PlaybackStatus, modifier: Modifier = Modifier) {
    val text = status.switchedTo?.let { stringResource(R.string.player_switched_to, it) }
        ?: status.indicator?.let { indicatorText(it) }
        ?: return
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .background(Scrim, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Persistent error with Retry (connection limit, account expired, all versions failed). */
@Composable
internal fun PlaybackErrorPanelView(error: PlaybackErrorPanel, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val (title, message) = when (error) {
        is PlaybackErrorPanel.ConnectionLimit -> stringResource(R.string.player_connection_limit_title) to
            if (error.activeConnections != null && error.maxConnections != null) {
                stringResource(R.string.player_connection_limit_counts, error.activeConnections, error.maxConnections)
            } else {
                stringResource(R.string.player_connection_limit_unknown)
            }
        PlaybackErrorPanel.AccountExpired ->
            stringResource(R.string.player_account_expired_title) to stringResource(R.string.player_account_expired_message)
        is PlaybackErrorPanel.Failed -> stringResource(R.string.player_failed_title) to
            if (error.triedVersions > 1) {
                stringResource(R.string.player_failed_multiple, error.label, error.triedVersions - 1)
            } else {
                stringResource(R.string.player_failed_single, error.label)
            }
    }
    PlayerMessagePanel(title, message, if (error.canRetry) stringResource(R.string.player_retry) else null, onRetry, modifier)
}

/** A centred panel over the video with an optional action. */
@Composable
internal fun PlayerMessagePanel(
    title: String?,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 560.dp)
            .background(Color(0xE0101010), MaterialTheme.shapes.medium)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        title?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = Color.White) }
        Text(message, color = Color.White)
        if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

package app.playlistplayer.ui.tv.player

import android.text.format.DateFormat
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.live.PlaybackErrorPanel
import app.playlistplayer.feature.live.PlaybackStatus
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton
import java.util.Date
import java.util.Locale

/**
 * Video output. SurfaceView (not TextureView): correct HDR output and frame timing (U6 harness, U10).
 * There is deliberately no shutter: on a zap the surface keeps showing the previous channel's last
 * frame until the new stream renders (U22, the `setKeepContentOnPlayerReset` behaviour).
 */
@Composable
internal fun PlayerVideoSurface(exoPlayer: ExoPlayer, modifier: Modifier = Modifier) {
    app.playlistplayer.ui.common.ScaledVideoSurface(exoPlayer, modifier)
}

@Composable
internal fun indicatorText(indicator: PlaybackStatus.Indicator): String = when (indicator) {
    PlaybackStatus.Indicator.BUFFERING -> stringResource(R.string.player_buffering)
    PlaybackStatus.Indicator.RECONNECTING -> stringResource(R.string.player_reconnecting)
    PlaybackStatus.Indicator.WAITING_FOR_NETWORK -> stringResource(R.string.player_waiting_for_network)
}

/** Buffering/Reconnecting indicator and the transient "Switched to" notice. */
@Composable
internal fun StatusBanner(status: PlaybackStatus, modifier: Modifier = Modifier) {
    val text = status.switchedTo?.let { stringResource(R.string.player_switched_to, it) }
        ?: status.indicator?.let { indicatorText(it) }
        ?: return
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(Color(0xB0000000), MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Small buffering / reconnecting indicator for a corner of the video (replaces a centre spinner, U22). */
@Composable
internal fun CornerIndicator(indicator: PlaybackStatus.Indicator, modifier: Modifier = Modifier) {
    val text = indicatorText(indicator)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color(0x99000000), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(LivePlayerTags.CORNER_INDICATOR)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
        Text(text = text, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** Persistent error with Retry (connection limit, account expired, all versions failed). */
@Composable
internal fun PlayerErrorPanel(error: PlaybackErrorPanel, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
    MessagePanel(title = title, message = message, actionLabel = if (error.canRetry) stringResource(R.string.player_retry) else null, onAction = onRetry, modifier = modifier)
}

/** A centred panel with an optional focused action button. */
@Composable
internal fun MessagePanel(
    title: String?,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Column(
        modifier = modifier
            .widthIn(max = 640.dp)
            .background(Color(0xE0101010), MaterialTheme.shapes.medium)
            .padding(32.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        title?.let { Text(text = it, style = MaterialTheme.typography.titleLarge, color = Color.White) }
        Text(text = message, color = Color.White)
        if (actionLabel != null) TvButton(text = actionLabel, onClick = onAction, modifier = Modifier.focusRequester(focus))
    }
    if (actionLabel != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Horizontal progress bar (0..1). */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, available: Float = 1f) {
    Box(modifier = modifier.height(4.dp).background(Color(0x40FFFFFF))) {
        if (available < 1f) {
            Box(modifier = Modifier.fillMaxWidth(available.coerceIn(0f, 1f)).height(4.dp).background(Color(0x80FFFFFF)))
        }
        Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(UiTokens.Accent))
    }
}


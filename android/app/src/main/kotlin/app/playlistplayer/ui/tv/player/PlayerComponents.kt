package app.playlistplayer.ui.tv.player

import android.text.format.DateFormat
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/** Video output. SurfaceView (not TextureView): correct HDR output and frame timing (U6 harness, U10). */
@Composable
internal fun PlayerVideoSurface(exoPlayer: ExoPlayer, modifier: Modifier = Modifier) {
    val surface = remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(factory = { context -> SurfaceView(context).also { surface.value = it } }, modifier = modifier)
    val view = surface.value
    DisposableEffect(exoPlayer, view) {
        if (view != null) exoPlayer.setVideoSurfaceView(view)
        onDispose { if (view != null) exoPlayer.clearVideoSurfaceView(view) }
    }
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

/** Wall-clock time in the device's 12/24 h setting. */
@Composable
internal fun rememberTimeFormatter(): (Long) -> String {
    val context = LocalContext.current
    return remember(context) {
        val format = DateFormat.getTimeFormat(context)
        val formatter: (Long) -> String = { millis -> format.format(Date(millis)) }
        formatter
    }
}

/** `h:mm:ss` / `m:ss` for positions and durations. */
internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

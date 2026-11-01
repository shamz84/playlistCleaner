package app.playlistplayer.ui.mobile.player

import android.content.pm.ActivityInfo
import android.view.SurfaceView
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import app.playlistplayer.R
import app.playlistplayer.core.player.PlayerUiState
import app.playlistplayer.feature.vod.TrackOption
import app.playlistplayer.ui.mobile.GlyphIcon
import app.playlistplayer.ui.mobile.MobileDimens

/**
 * Fullscreen landscape while the player is shown: sensor landscape orientation, system bars hidden
 * (swipe shows them transiently), screen kept on. Everything is restored when the player leaves.
 *
 * Requirement (wiring unit): rotation must not recreate the activity, or the player screen would
 * be disposed and stop the stream. `MainActivity` declares
 * `configChanges="…orientation|screenLayout|screenSize|smallestScreenSize…"`; keep those, and keep
 * the [ExoPlayer] / controller owned outside the composition (AppContainer or a retained holder).
 */
@Composable
internal fun FullscreenLandscapeEffect() {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        // Screen-on is per playback state (KeepScreenOn in each player), not for the whole screen.
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            if (activity != null && previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
}

/**
 * Video output. SurfaceView (not TextureView): correct HDR output and frame timing, as on TV.
 * The view stays in the same composition slot across rotation, and the player keeps decoding even
 * if the surface is recreated, so rotation never re-prepares the stream.
 */
@Composable
internal fun VideoSurface(exoPlayer: ExoPlayer, modifier: Modifier = Modifier) {
    val surface = remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(factory = { context -> SurfaceView(context).also { surface.value = it } }, modifier = modifier)
    val view = surface.value
    DisposableEffect(exoPlayer, view) {
        if (view != null) exoPlayer.setVideoSurfaceView(view)
        onDispose { if (view != null) exoPlayer.clearVideoSurfaceView(view) }
    }
}

/** A round control over the video with a glyph and a mandatory content description (≥ 48 dp). */
@Composable
internal fun PlayerIconButton(glyph: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = description }) {
        GlyphIcon(glyph)
    }
}

/** Buffering / reconnecting indicator and the transient "Switched to …" notice (from [PlayerUiState]). */
@Composable
internal fun PlayerStatus(ui: PlayerUiState, modifier: Modifier = Modifier) {
    val text = listOfNotNull(ui.indicator?.text, ui.notice).joinToString("  ·  ")
    if (text.isEmpty()) return
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

/** Persistent error panel with Retry (connection limit, account expired, all versions failed). */
@Composable
internal fun PlayerErrorPanel(error: PlayerUiState.ErrorPanel, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Color(0xE0000000), MaterialTheme.shapes.medium)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(error.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(error.message, color = Color.White)
        if (error.canRetry) Button(onClick = onRetry) { Text(stringResource(R.string.vod_retry_playback)) }
    }
}

/** Audio and subtitle choices as a bottom sheet (R17), shared by the live and VOD players. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TracksSheet(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
    onAudio: (String) -> Unit,
    onSubtitle: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            item { SheetHeading(stringResource(R.string.vod_audio)) }
            items(audio, key = { "a:${it.id}" }) { track -> TrackRow(track.label, track.selected) { onAudio(track.id) } }
            item { SheetHeading(stringResource(R.string.vod_subtitles)) }
            item(key = "t:off") { TrackRow(stringResource(R.string.vod_subtitles_off), subtitles.none { it.selected }) { onSubtitle(null) } }
            items(subtitles, key = { "t:${it.id}" }) { track -> TrackRow(track.label, track.selected) { onSubtitle(track.id) } }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding, vertical = 8.dp).semantics { heading() },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

internal val Scrim = Color(0xB0000000)

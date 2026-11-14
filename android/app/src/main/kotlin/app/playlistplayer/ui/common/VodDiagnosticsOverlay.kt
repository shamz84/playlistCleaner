package app.playlistplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlaybackDebugStats

val LocalVodDiagnostics = staticCompositionLocalOf { false }

@Composable
fun VodDiagnosticsOverlay(controller: ExoPlaybackController, modifier: Modifier = Modifier) {
    if (!LocalVodDiagnostics.current) return
    val stats by controller.debugStats.collectAsStateWithLifecycle()
    VodDiagnosticsPanel(stats, modifier)
}

@Composable
internal fun VodDiagnosticsPanel(stats: PlaybackDebugStats, modifier: Modifier = Modifier) {
    val status = when {
        stats.isBuffering && stats.playWhenReady -> R.string.vod_diagnostics_buffering
        stats.isPlaying -> R.string.vod_diagnostics_playing
        !stats.playWhenReady -> R.string.vod_diagnostics_paused
        else -> R.string.vod_diagnostics_waiting
    }
    val video = stats.videoFormat
    Column(modifier = modifier.widthIn(max = 320.dp).background(Color(0xD9000000)).padding(10.dp)) {
        DiagnosticsText(stringResource(R.string.vod_diagnostics_title))
        DiagnosticsText(stringResource(R.string.vod_diagnostics_status, stringResource(status)))
        DiagnosticsText(stringResource(R.string.vod_diagnostics_buffer, stats.bufferedMs.coerceAtLeast(0) / 1000f))
        DiagnosticsText(stringResource(R.string.vod_diagnostics_buffer_memory, stats.allocatedBufferBytes / 1_048_576f))
        DiagnosticsText(stringResource(R.string.vod_diagnostics_heap, stats.heapUsedBytes / 1_048_576f, stats.heapMaxBytes / 1_048_576f))
        stats.decoderDroppedFrames?.let { DiagnosticsText(stringResource(R.string.vod_diagnostics_dropped, it)) }
        if (video != null && video.width > 0 && video.height > 0) {
            DiagnosticsText(stringResource(R.string.vod_diagnostics_resolution, video.width, video.height))
        }
        video?.frameRate?.let { DiagnosticsText(stringResource(R.string.vod_diagnostics_fps, it)) }
        stats.videoDecoderName?.let { DiagnosticsText(stringResource(R.string.vod_diagnostics_decoder, it)) }
    }
}

@Composable
private fun DiagnosticsText(text: String) {
    Text(text = text, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp)
}

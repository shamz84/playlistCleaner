package app.playlistplayer

import app.playlistplayer.core.network.Redactor
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import app.playlistplayer.core.player.AccountStatusChecker
import app.playlistplayer.core.player.AndroidConnectivityMonitor
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.DisplayModeSwitcher
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.core.player.PlaybackDebugStats
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.core.player.PlayerFactory
import app.playlistplayer.core.player.PlayerSettings
import app.playlistplayer.core.player.PlayerUiState
import app.playlistplayer.core.player.VideoFormatInfo

/**
 * Debug-only playback harness (not in release builds).
 *
 * ```
 * adb shell am start -n app.playlistplayer/.DebugPlaybackActivity --es url "<url>" \
 *     --esa alts "<alt1>,<alt2>" [--es alt1 <url> ... --es alt5 <url>] \
 *     [--ez live true|false] [--ez tunneling true] [--ez match_frame_rate false]
 * ```
 *
 * The activity is not exported; on user builds `am start` from the shell may be refused, in which
 * case use `adb shell run-as app.playlistplayer am start ...` or a rooted/userdebug device.
 * URLs are never logged unredacted.
 */
class DebugPlaybackActivity : ComponentActivity() {

    private lateinit var configured: ConfiguredPlayer
    private lateinit var connectivity: AndroidConnectivityMonitor
    private lateinit var controller: ExoPlaybackController
    private lateinit var displayModes: DisplayModeSwitcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        configured = PlayerFactory(
            this,
            PlayerSettings(tunnelingEnabled = intent.getBooleanExtra(EXTRA_TUNNELING, false)),
        ).create()
        connectivity = AndroidConnectivityMonitor(this)
        controller = ExoPlaybackController(
            player = configured,
            accountStatusChecker = AccountStatusChecker.UNKNOWN,
            connectivity = connectivity,
            onTransition = { Log.d(TAG, Redactor.redact(it.toString())) },
        )
        displayModes = DisplayModeSwitcher(window, enabled = intent.getBooleanExtra(EXTRA_MATCH_FRAME_RATE, true))

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        val alternatives = buildList {
            intent.getStringArrayExtra(EXTRA_ALTS)?.let { addAll(it) }
            for (i in 1..MAX_ALTERNATIVES) intent.getStringExtra("alt$i")?.let(::add)
        }.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_ALTERNATIVES)
        val liveOverride: Boolean? = if (intent.hasExtra(EXTRA_LIVE)) intent.getBooleanExtra(EXTRA_LIVE, true) else null

        Log.d(TAG, "Harness started with ${alternatives.size} alternative(s), url=${Redactor.redact(initialUrl)}")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DebugPlaybackScreen(
                    exoPlayer = configured.exoPlayer,
                    controller = controller,
                    displayModes = displayModes,
                    initialUrl = initialUrl,
                    alternatives = alternatives,
                    onPlay = { url -> play(url, alternatives, liveOverride) },
                )
            }
        }

        if (initialUrl.isNotEmpty()) play(initialUrl, alternatives, liveOverride)
    }

    private fun play(url: String, alternatives: List<String>, liveOverride: Boolean?) {
        val urls = (listOf(url.trim()) + alternatives).filter(String::isNotEmpty).distinct()
        if (urls.isEmpty()) return
        val sources = urls.mapIndexed { index, u ->
            PlayableSource(
                id = "debug-$index",
                url = u,
                label = if (index == 0) "Primary" else "Alternative $index",
                isLive = liveOverride ?: !(u.contains("/movie/") || u.contains("/series/")),
            )
        }
        controller.play(sources)
    }

    override fun onStop() {
        super.onStop()
        // Release the provider connection when leaving the screen (max_connections is often 1).
        controller.stop()
        displayModes.reset()
    }

    override fun onDestroy() {
        controller.close()
        configured.release()
        connectivity.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DebugPlayback"
        const val EXTRA_URL = "url"
        const val EXTRA_ALTS = "alts"
        const val EXTRA_LIVE = "live"
        const val EXTRA_TUNNELING = "tunneling"
        const val EXTRA_MATCH_FRAME_RATE = "match_frame_rate"
        const val MAX_ALTERNATIVES = 5
    }
}

@Composable
private fun DebugPlaybackScreen(
    exoPlayer: ExoPlayer,
    controller: ExoPlaybackController,
    displayModes: DisplayModeSwitcher,
    initialUrl: String,
    alternatives: List<String>,
    onPlay: (String) -> Unit,
) {
    val state by controller.state.collectAsState()
    val ui by controller.uiState.collectAsState()
    val stats by controller.debugStats.collectAsState()
    val format by controller.videoFormat.collectAsState()
    var url by remember { mutableStateOf(initialUrl) }

    LaunchedEffect(format) { displayModes.apply(format) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(
            exoPlayer = exoPlayer,
            aspectRatio = format?.aspectRatio ?: (16f / 9f),
            modifier = Modifier.align(Alignment.Center),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .widthIn(max = 720.dp)
                .background(Color(0xAA000000))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onPlay(url) }) { Text("Play") }
                Button(onClick = controller::retry) { Text("Retry") }
            }
            if (alternatives.isNotEmpty()) {
                OverlayText("Alternatives (${alternatives.size}):")
                alternatives.forEachIndexed { i, alt -> OverlayText("  ${i + 1}. ${Redactor.redact(alt)}") }
            }
            OverlayText("State: ${describe(state)}")
            OverlayText(statsLine(stats))
            OverlayText("Video: ${describe(format)}")
            OverlayText("Decoders: video=${stats.videoDecoderName ?: "-"} audio=${stats.audioDecoderName ?: "-"}")
            UiStateLines(ui)
        }
    }
}

@Composable
private fun VideoSurface(exoPlayer: ExoPlayer, aspectRatio: Float, modifier: Modifier) {
    // SurfaceView (not TextureView): correct HDR output and frame timing.
    val surface = remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(
        factory = { context -> SurfaceView(context).also { surface.value = it } },
        modifier = modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = true),
    )
    val view = surface.value
    DisposableEffect(exoPlayer, view) {
        if (view != null) exoPlayer.setVideoSurfaceView(view)
        onDispose { if (view != null) exoPlayer.clearVideoSurfaceView(view) }
    }
}

@Composable
private fun UiStateLines(ui: PlayerUiState) {
    ui.indicator?.let { OverlayText("Indicator: ${it.text}", Color(0xFFFFD54F)) }
    ui.notice?.let { OverlayText("Notice: $it", Color(0xFF81C784)) }
    ui.error?.let { OverlayText("Error: ${it.title}. ${it.message} [Retry]", Color(0xFFE57373)) }
}

@Composable
private fun OverlayText(text: String, color: Color = Color.White) {
    Text(text = text, color = color, fontSize = 14.sp)
}

private fun statsLine(stats: PlaybackDebugStats): String =
    "Buffered: ${stats.bufferedMs} ms   Dropped frames: ${stats.droppedFrames}"

private fun describe(format: VideoFormatInfo?): String = format?.let {
    val fps = it.frameRate?.let { r -> "%.3f fps".format(r) } ?: "fps n/a"
    "${it.width}x${it.height} $fps ${it.sampleMimeType ?: "?"} ${it.codecs ?: ""}".trim()
} ?: "-"

private fun describe(state: PlaybackState): String = when (state) {
    PlaybackState.Idle -> "Idle"
    is PlaybackState.Preparing -> "Preparing ${state.source.label} (attempt ${state.attempt}${if (state.reconnecting) ", reconnecting" else ""})"
    is PlaybackState.Playing -> "Playing ${state.source.label}${if (state.buffering) " (buffering)" else ""}"
    is PlaybackState.Stalled -> "Stalled ${state.source.label}"
    is PlaybackState.Retrying -> buildString {
        append("Retrying ${state.source.label} in ${state.delayMs} ms (${state.reason.kind}")
        state.reason.httpStatus?.let { append(" $it") }
        append(")")
        if (state.waitingForNetwork) append(" waiting for network")
        if (state.checkingAccount) append(" checking account")
    }
    is PlaybackState.Failover -> "Failover #${state.count}: ${state.from.label} -> ${state.to.label} (${state.reason.kind})"
    is PlaybackState.ConnectionLimit -> "ConnectionLimit ${state.activeConnections}/${state.maxConnections}"
    is PlaybackState.AccountExpired -> "AccountExpired"
    is PlaybackState.Failed -> "Failed after ${state.triedVersions} version(s) (${state.reason.kind})"
    is PlaybackState.Ended -> "Ended"
}

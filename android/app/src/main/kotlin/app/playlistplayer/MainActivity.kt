package app.playlistplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import app.playlistplayer.feature.live.isStreamActive
import app.playlistplayer.ui.common.ClockOverlay
import app.playlistplayer.ui.common.LiveVideoInfo
import app.playlistplayer.ui.common.LocalLiveVideoInfo
import app.playlistplayer.ui.common.LocalVodDiagnostics
import app.playlistplayer.ui.common.LocalAppPalette
import app.playlistplayer.ui.common.paletteFor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.playlistplayer.core.player.DisplayModeSwitcher
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.feature.settings.AppPreferences
import app.playlistplayer.ui.common.LocalVideoScaling
import app.playlistplayer.ui.common.LocalLiveVideoScaling
import app.playlistplayer.ui.common.VideoScalingControl
import app.playlistplayer.ui.mobile.MobileLoading
import app.playlistplayer.ui.mobile.MobileRoot
import app.playlistplayer.ui.mobile.MobileTheme
import app.playlistplayer.ui.tv.TvRoot
import app.playlistplayer.ui.tv.components.TvLoadingState
import app.playlistplayer.ui.tv.components.LocalTvLongBack
import app.playlistplayer.ui.tv.components.TvBackGesture
import app.playlistplayer.ui.tv.shell.ExitDialog
import app.playlistplayer.ui.tv.theme.TvTheme
import app.playlistplayer.wiring.uiDependencies
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The only activity. Shows a loading state until the container's start-up (parental state) is
 * ready, then the TV or phone tree for the device's [FormFactor] with dependencies from the
 * [AppContainer]. `configChanges` in the manifest keep it (and the player surface) across rotation.
 *
 * Player lifecycle: the app-scoped player pauses when the activity stops and stops (freeing the
 * provider connection) after [BACKGROUND_STOP_MS] in the background; it is released when the
 * activity finishes. "Match frame rate" switches the display mode for the playing video.
 */
class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundStop = Runnable { container.playback.stop() }
    private var displayModes: DisplayModeSwitcher? = null
    private var pausedInBackground = false
    private var showLongBackExit by mutableStateOf(false)
    private var tvBackGesture: TvBackGesture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as PlaylistPlayerApp).container
        val formFactor = container.formFactor
        if (formFactor == FormFactor.Tv) {
            tvBackGesture = TvBackGesture(
                onShort = { onBackPressedDispatcher.onBackPressed() },
                onLong = { showLongBackExit = true },
            )
        }
        setContent {
            val ready by container.ready.collectAsState()
            if (!ready) {
                when (formFactor) {
                    FormFactor.Tv -> TvTheme {
                        TvLoadingState()
                        if (showLongBackExit) ExitDialog(onConfirm = ::finish, onDismiss = { showLongBackExit = false })
                    }
                    FormFactor.Mobile -> MobileTheme { MobileLoading() }
                }
                return@setContent
            }
            val dependencies = remember { container.uiDependencies() }
            val preferences by container.preferencesRepository.preferences.collectAsState(initial = AppPreferences())
            val scope = rememberCoroutineScope()
            val scaling = preferences.playback.videoScaling
            val scalingControl = remember(scaling) {
                VideoScalingControl(scaling, { choice -> scope.launch { container.preferencesRepository.setVideoScaling(choice) } }) {
                    scope.launch { container.preferencesRepository.setVideoScaling(scaling.next()) }
                }
            }
            val liveScaling = preferences.playback.liveVideoScaling
            val liveScalingControl = remember(liveScaling) {
                VideoScalingControl(liveScaling, { choice -> scope.launch { container.preferencesRepository.setLiveVideoScaling(choice) } }) {
                    scope.launch { container.preferencesRepository.setLiveVideoScaling(liveScaling.next()) }
                }
            }
            val controller = remember { container.playback.controller() }
            val videoFormat by controller.videoFormat.collectAsState()
            CompositionLocalProvider(
                LocalVideoScaling provides scalingControl,
                LocalLiveVideoScaling provides liveScalingControl,
                LocalLiveVideoInfo provides LiveVideoInfo(preferences.playback.showVideoInfo, videoFormat),
                LocalVodDiagnostics provides preferences.playback.showVodDiagnostics,
                LocalAppPalette provides paletteFor(preferences.theme),
                LocalTvLongBack provides if (formFactor == FormFactor.Tv) ({ showLongBackExit = true }) else null,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (formFactor) {
                        FormFactor.Tv -> TvRoot(dependencies.tv)
                        FormFactor.Mobile -> MobileRoot(dependencies.mobile)
                    }
                    val streaming by produceState(false) {
                        controller.state.collect { value = it.isStreamActive() }
                    }
                    if (preferences.playback.showClock && streaming) ClockOverlay()
                    if (showLongBackExit) TvTheme {
                        ExitDialog(onConfirm = ::finish, onDismiss = { showLongBackExit = false })
                    }
                }
            }
        }
        startDisplayModeSwitching()
        startGuideFreshness()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (tvBackGesture?.handle(event) == true) true else super.dispatchKeyEvent(event)

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(backgroundStop)
        if (pausedInBackground) {
            pausedInBackground = false
            container.playback.controllerOrNull?.let { controller ->
                if (controller.state.value !is PlaybackState.Idle) controller.resume()
            }
        }
    }

    override fun onStop() {
        tvBackGesture?.cancel()
        super.onStop()
        if (!isChangingConfigurations && container.playback.controllerOrNull != null) {
            container.playback.pause()
            pausedInBackground = true
            handler.postDelayed(backgroundStop, BACKGROUND_STOP_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(backgroundStop)
        displayModes?.reset()
        if (isFinishing) container.playback.release()
        super.onDestroy()
    }

    /** Applies the display mode for the playing video while "Match frame rate" is on (U6). */
    /** While the app is on screen, refreshes guides older than the Settings interval. */
    private fun startGuideFreshness() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.ready.first { it }
                container.keepGuideFresh()
            }
        }
    }

    private fun startDisplayModeSwitching() {
        val switcher = DisplayModeSwitcher(window, enabled = false).also { displayModes = it }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.ready.first { it }
                launch {
                    container.preferencesRepository.preferences.map { it.playback.matchFrameRate }.distinctUntilChanged().collect { on ->
                        switcher.enabled = on
                        if (!on) switcher.reset()
                    }
                }
                val controller = container.playback.controller()
                combine(controller.videoFormat, controller.state) { format, state -> format to state }.collect { (format, state) ->
                    when (state) {
                        is PlaybackState.Idle, is PlaybackState.Ended, is PlaybackState.Failed -> switcher.reset()
                        else -> switcher.apply(format)
                    }
                }
            }
        }
    }

    companion object {
        /** Background time after which playback is stopped (the provider allows one connection). */
        const val BACKGROUND_STOP_MS: Long = 30_000
    }
}

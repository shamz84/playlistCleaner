package app.playlistplayer.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import app.playlistplayer.feature.live.CatchupPlayerViewModel
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.ExoPlayerDriver
import app.playlistplayer.feature.live.LivePlayerViewModel
import app.playlistplayer.feature.live.PlayerDriver
import app.playlistplayer.ui.mobile.MobileLoading
import app.playlistplayer.ui.mobile.MobilePlaybackDependencies
import app.playlistplayer.ui.mobile.epg.MobileGuideDependencies

/** The shared ViewModels' [PlayerDriver] over the app's long-lived controller and player. */
@Composable
internal fun rememberPlayerDriver(playback: MobilePlaybackDependencies): PlayerDriver =
    remember(playback) { ExoPlayerDriver(playback.controller, playback.player) }

/**
 * Live player over [LivePlayerViewModel] for one back-stack entry. The ViewModel lives with this
 * composition (see [rememberCompositionViewModelStoreOwner]): covered by a catch-up player it is
 * cleared, and shown again it re-tunes [ordinal] (kept current through [onChannelChanged]).
 */
@Composable
fun MobileChannelPlayerHost(
    playlistId: Long,
    ordinal: Int,
    guide: MobileGuideDependencies,
    playback: MobilePlaybackDependencies,
    onStartCatchup: (CatchupRequest) -> Unit,
    onChannelChanged: (Int) -> Unit,
    onExit: () -> Unit,
) {
    FullscreenLandscapeEffect() // held across the deferred first frame, so orientation does not flip back
    val driver = rememberPlayerDriver(playback)
    val owner = rememberCompositionViewModelStoreOwner()
    if (!rememberAfterFirstComposition()) return PlayerPending()
    val viewModel = viewModel(viewModelStoreOwner = owner, key = "mobile-channel-player") {
        LivePlayerViewModel(playlistId, ordinal, guide.channels, guide.coordinator, guide.nowNext, driver, guide.clock)
    }
    MobileChannelPlayerScreen(
        viewModel = viewModel,
        player = playback.player,
        onStartCatchup = onStartCatchup,
        onChannelChanged = onChannelChanged,
        onExit = onExit,
    )
}

/** Catch-up player over [CatchupPlayerViewModel]; the ViewModel lives in the entry's store. */
@Composable
fun MobileCatchupPlayerHost(
    request: CatchupRequest,
    guide: MobileGuideDependencies,
    playback: MobilePlaybackDependencies,
    onExit: () -> Unit,
) {
    FullscreenLandscapeEffect()
    val driver = rememberPlayerDriver(playback)
    if (!rememberAfterFirstComposition()) return PlayerPending()
    val viewModel = viewModel(key = "mobile-catchup-player") {
        CatchupPlayerViewModel(request, guide.catchup, driver, guide.clock)
    }
    MobileCatchupPlayerScreen(viewModel = viewModel, player = playback.player, onExit = onExit)
}

@Composable
private fun PlayerPending() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) { MobileLoading() }
}

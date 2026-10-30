package app.playlistplayer.ui.tv

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.playlistplayer.R
import app.playlistplayer.feature.onboarding.OnboardingViewModel
import app.playlistplayer.feature.onboarding.PlaylistOnboarder
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.feature.shell.ShellViewModel
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import app.playlistplayer.ui.common.live.LiveBrowseText
import app.playlistplayer.ui.common.live.LiveBrowseViewModel
import app.playlistplayer.ui.tv.components.TvLoadingState
import app.playlistplayer.ui.tv.live.LiveScreen
import app.playlistplayer.ui.tv.onboarding.OnboardingScreen
import app.playlistplayer.ui.tv.shell.PlaceholderDestination
import app.playlistplayer.ui.tv.shell.TvShell
import app.playlistplayer.ui.tv.theme.TvTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the TV tree needs from the app container. The wiring unit builds this from `AppContainer`
 * and passes it to [TvRoot] from `MainActivity`.
 *
 * @property hasPlaylists emits whether at least one playlist exists; false shows first-run onboarding.
 * @property isXtreamShaped `{ AccountExtractor.fromUrl(it) != null }` from `:core:parsing`.
 * @property onPlayChannel starts live playback (player overlay arrives with U6/U9 player wiring).
 */
class TvRootDependencies(
    val onboarder: PlaylistOnboarder,
    val isXtreamShaped: (String) -> Boolean,
    val liveBrowseSource: LiveBrowseSource,
    val hasPlaylists: Flow<Boolean>,
    val onPlayChannel: (ChannelRow) -> Unit = {},
)

/**
 * Root of the TV UI tree (tv-material). Without [dependencies] (until the wiring unit lands) it shows
 * the shell with placeholder destinations and an empty Live screen.
 */
@Composable
fun TvRoot(dependencies: TvRootDependencies? = null) {
    TvTheme {
        Box(modifier = Modifier.fillMaxSize().background(UiTokens.Background)) {
            val activity = LocalActivity.current
            val hasPlaylists by remember(dependencies) { dependencies?.hasPlaylists ?: flowOf(true) }
                .collectAsState(initial = null)
            var justOnboarded by rememberSaveable { mutableStateOf(false) }
            var addingPlaylist by rememberSaveable { mutableStateOf(false) }
            var onboardingRun by rememberSaveable { mutableIntStateOf(0) }

            when {
                dependencies != null && hasPlaylists == null && !justOnboarded -> TvLoadingState()

                dependencies != null && (hasPlaylists == false && !justOnboarded || addingPlaylist) -> {
                    val firstRun = !addingPlaylist
                    val onboarding = viewModel(key = "onboarding-$onboardingRun") {
                        OnboardingViewModel(dependencies.onboarder, dependencies.isXtreamShaped)
                    }
                    OnboardingScreen(
                        viewModel = onboarding,
                        isFirstRun = firstRun,
                        onFinished = {
                            justOnboarded = true
                            addingPlaylist = false
                            onboardingRun++
                        },
                        onCancel = if (firstRun) null else ({ addingPlaylist = false; onboardingRun++ }),
                    )
                }

                else -> {
                    val shell = viewModel { ShellViewModel() }
                    val liveText = LiveBrowseText(
                        noPlaylists = stringResource(R.string.live_no_playlists),
                        emptyGroup = stringResource(R.string.live_empty_group),
                        loadFailed = stringResource(R.string.live_load_failed),
                    )
                    TvShell(viewModel = shell, onExit = { activity?.finish() }) { destination ->
                        when (destination) {
                            Destination.Live -> {
                                val live = viewModel(key = "live") {
                                    LiveBrowseViewModel(dependencies?.liveBrowseSource ?: EmptyLiveBrowseSource, liveText)
                                }
                                LiveScreen(viewModel = live, onPlay = { dependencies?.onPlayChannel?.invoke(it) })
                            }
                            else -> PlaceholderDestination(
                                destination = destination,
                                onAddPlaylist = dependencies?.let { { addingPlaylist = true } },
                            )
                        }
                    }
                }
            }
        }
    }
}

private object EmptyLiveBrowseSource : LiveBrowseSource {
    override fun sections(): Flow<List<PlaylistSection>> = flowOf(emptyList())

    override fun channels(group: GroupKey): Flow<List<ChannelRow>> = flowOf(emptyList())
}

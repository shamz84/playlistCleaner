package app.playlistplayer.ui.common

import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.home.HomeViewModel
import app.playlistplayer.feature.live.CatchupPlaybackController
import app.playlistplayer.feature.live.CatchupPlayerViewModel
import app.playlistplayer.feature.live.EpgGridSource
import app.playlistplayer.feature.live.LiveChannelSource
import app.playlistplayer.feature.live.LivePlaybackCoordinator
import app.playlistplayer.feature.live.LivePlayerViewModel
import app.playlistplayer.feature.live.NowNextSource
import app.playlistplayer.feature.search.SearchViewModel
import app.playlistplayer.feature.settings.ParentalState
import app.playlistplayer.feature.settings.PinPromptBroker
import app.playlistplayer.feature.settings.SettingsViewModel
import app.playlistplayer.feature.vod.AccountProvider
import app.playlistplayer.feature.vod.FavouritesStore
import app.playlistplayer.feature.vod.MovieInfoLoader
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.SeriesInfoLoader
import app.playlistplayer.feature.vod.SeriesViewModel
import app.playlistplayer.feature.vod.TitleDetailViewModel
import app.playlistplayer.feature.vod.TitlePickStore
import app.playlistplayer.feature.vod.VersionPreferencesProvider
import app.playlistplayer.feature.vod.VodBrowseViewModel
import app.playlistplayer.feature.vod.VodCatalogRepository
import app.playlistplayer.ui.common.live.ChannelRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.time.ZoneId

// What both UI trees need from the app container, built once by `MainActivity` (the wiring unit).

/**
 * What the VOD screens need: exactly the constructor inputs of the shared [VodBrowseViewModel],
 * [TitleDetailViewModel] and [SeriesViewModel], plus [visibilityChanges] (parental lock/unlock),
 * on which the browse screens reload so a newly locked category disappears at once.
 */
class VodDependencies(
    val repository: VodCatalogRepository,
    val movieInfo: MovieInfoLoader,
    val seriesInfo: SeriesInfoLoader,
    val picks: TitlePickStore,
    val favourites: FavouritesStore,
    val progress: PlaybackProgressRepository,
    val preferences: VersionPreferencesProvider,
    val accounts: AccountProvider,
    val visibilityChanges: Flow<Unit> = emptyFlow(),
)

/**
 * The long-lived player (U6), owned outside the composition (AppContainer) so rotation and
 * screen changes never restart a stream.
 *
 * @property saveScope outlives the player screen (application scope), so the final progress save on exit completes.
 * @property liveSources resolves a channel to its playable versions: the channel first, then its
 *   `AlternativeFinder` alternatives, each with its stream URL built at play time (never stored).
 */
class PlaybackDependencies(
    val controller: ExoPlaybackController,
    val player: ConfiguredPlayer,
    val saveScope: CoroutineScope,
    val liveSources: suspend (ChannelRow) -> List<PlayableSource>,
)

/**
 * What the Guide, the guide-driven live player and the catch-up player need: exactly the
 * constructor inputs of the shared [EpgGridViewModel], [LivePlayerViewModel] and
 * [CatchupPlayerViewModel] (U8/U9), apart from the `PlayerDriver`, which each tree builds from
 * [PlaybackDependencies] (the same long-lived controller and player).
 *
 * The group picker reads `TvRootDependencies.liveBrowseSource.sections()`, so PIN-hidden groups
 * stay hidden in the Guide too.
 *
 * @property channels live entries of the active generation (`ChannelEntity` + `ChannelLiveInfo`).
 * @property grid guide rows for a group (`EpgStore::gridForGroup`).
 * @property coordinator live playback plans; also the grid's other-versions resolver.
 * @property catchup catch-up URL building and eligibility.
 * @property nowNext now/next for the live player overlay (`NowNextRepository::nowNext`).
 * @property clock wall clock shared by the grid and the players (tests inject a fixed one).
 * @property zone time zone of the grid's slots and day boundaries (the device's).
 */
class GuideDependencies(
    val channels: LiveChannelSource,
    val grid: EpgGridSource,
    val coordinator: LivePlaybackCoordinator,
    val catchup: CatchupPlaybackController,
    val nowNext: NowNextSource,
    val clock: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** Factories of the Home, Search and Settings ViewModels (each screen gets its own instance). */
class ScreenDependencies(
    val home: () -> HomeViewModel,
    val search: () -> SearchViewModel,
    val settings: () -> SettingsViewModel,
)

/**
 * App-wide state the roots render whatever screen is showing.
 *
 * @property pinPrompts PIN requests from anywhere (playlist edits, group visibility, …); the root
 *   shows the PIN dialog for them (Settings shows its own while it is on screen).
 * @property parentalState [ParentalState.ResetRequired] (damaged parental storage) replaces the
 *   whole UI with a reset-only screen.
 * @property resetEverything "Reset app" from that screen: starts wiping data, parental state and
 *   preferences in the application scope (the screen leaves composition when the state changes).
 */
class AppStateDependencies(
    val pinPrompts: PinPromptBroker,
    val parentalState: StateFlow<ParentalState>,
    val resetEverything: () -> Unit,
)

package app.playlistplayer.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.feature.onboarding.OnboardingViewModel
import app.playlistplayer.feature.shell.Destination
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
import app.playlistplayer.feature.vod.VodCard
import app.playlistplayer.feature.vod.VodCatalogRepository
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import app.playlistplayer.ui.common.live.LiveBrowseText
import app.playlistplayer.ui.common.live.LiveBrowseViewModel
import app.playlistplayer.ui.mobile.epg.MobileGuideDependencies
import app.playlistplayer.ui.mobile.epg.MobileGuideScreen
import app.playlistplayer.ui.mobile.live.MobileLiveScreen
import app.playlistplayer.ui.mobile.navigation.EntryViewModelStores
import app.playlistplayer.ui.mobile.navigation.MobileEntry
import app.playlistplayer.ui.mobile.navigation.MobileNavState
import app.playlistplayer.ui.mobile.navigation.MobileScreen
import app.playlistplayer.ui.mobile.navigation.isWideLayout
import app.playlistplayer.ui.mobile.navigation.navigationTypeFor
import app.playlistplayer.ui.mobile.onboarding.MobileOnboardingScreen
import app.playlistplayer.ui.mobile.player.MobileCatchupPlayerHost
import app.playlistplayer.ui.mobile.player.MobileChannelPlayerHost
import app.playlistplayer.ui.mobile.player.MobileLivePlayerScreen
import app.playlistplayer.ui.mobile.player.MobileVodPlayerScreen
import app.playlistplayer.ui.mobile.settings.MobileSettingsScreen
import app.playlistplayer.ui.mobile.vod.MobileSeriesScreen
import app.playlistplayer.ui.mobile.vod.MobileTitleDetailScreen
import app.playlistplayer.ui.mobile.vod.MobileVodBrowseScreen
import app.playlistplayer.ui.tv.TvRootDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the VOD screens need: exactly the constructor inputs of the shared [VodBrowseViewModel],
 * [TitleDetailViewModel] and [SeriesViewModel]. Built once by the wiring unit (the TV tree needs the
 * same set when its Movies / Series destinations are wired).
 */
class MobileVodDependencies(
    val repository: VodCatalogRepository,
    val movieInfo: MovieInfoLoader,
    val seriesInfo: SeriesInfoLoader,
    val picks: TitlePickStore,
    val favourites: FavouritesStore,
    val progress: PlaybackProgressRepository,
    val preferences: VersionPreferencesProvider,
    val accounts: AccountProvider,
)

/**
 * The long-lived player (U6), owned outside the composition (AppContainer or a retained holder) so
 * rotation and screen changes never restart a stream.
 *
 * @property saveScope outlives the player screen (application scope), so the final progress save on exit completes.
 * @property liveSources resolves a channel to its playable versions: the channel first, then its
 *   `AlternativeFinder` alternatives, each with its stream URL built at play time (never stored).
 */
class MobilePlaybackDependencies(
    val controller: ExoPlaybackController,
    val player: ConfiguredPlayer,
    val saveScope: CoroutineScope,
    val liveSources: suspend (ChannelRow) -> List<PlayableSource>,
)

/**
 * What the phone tree needs from the app container. [shared] is the same object the TV tree takes
 * (onboarder, Xtream-shape check, live browse source, hasPlaylists, onPlayChannel), so the wiring
 * unit builds it once. [vod] enables Movies / Series; [playback] enables the in-app players (without
 * it a channel tap falls back to `shared.onPlayChannel`). [guide] enables the Guide and catch-up;
 * with [playback] too, live channels play through the shared `LivePlayerViewModel` (now/next,
 * other versions, "Restart programme") instead of `playback.liveSources`.
 */
class MobileRootDependencies(
    val shared: TvRootDependencies,
    val vod: MobileVodDependencies? = null,
    val playback: MobilePlaybackDependencies? = null,
    val guide: MobileGuideDependencies? = null,
)

/**
 * Root of the phone UI tree (material3). First run shows onboarding while `hasPlaylists` is false;
 * otherwise the shell with bottom navigation (Home, Live, Guide, Movies, Series, Search), Settings in
 * the top app bar, and a navigation rail at ≥ 600 dp width. Without [dependencies] it renders the
 * shell with placeholder destinations and an empty Live screen.
 */
@Composable
fun MobileRoot(dependencies: MobileRootDependencies? = null) {
    MobileTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = UiTokens.Background) {
            val shared = dependencies?.shared
            val hasPlaylists by remember(dependencies) { shared?.hasPlaylists ?: flowOf(true) }
                .collectAsState(initial = null)
            var justOnboarded by rememberSaveable { mutableStateOf(false) }
            var onboardingRun by rememberSaveable { mutableIntStateOf(0) }

            when {
                shared != null && hasPlaylists == null && !justOnboarded -> MobileLoading()

                shared != null && hasPlaylists == false && !justOnboarded -> {
                    val onboarding = viewModel(key = "mobile-onboarding-$onboardingRun") {
                        OnboardingViewModel(shared.onboarder, shared.isXtreamShaped)
                    }
                    MobileOnboardingScreen(
                        viewModel = onboarding,
                        isFirstRun = true,
                        onFinished = {
                            justOnboarded = true
                            onboardingRun++
                        },
                    )
                }

                else -> MobileApp(dependencies)
            }
        }
    }
}

@Composable
private fun MobileApp(dependencies: MobileRootDependencies?) {
    var nav by remember { mutableStateOf(MobileNavState()) }
    val stores = remember { EntryViewModelStores() }
    val saveable = rememberSaveableStateHolder()
    DisposableEffect(Unit) { onDispose { stores.clearAll() } }

    fun navigate(next: MobileNavState) {
        nav.removedIn(next).forEach { id ->
            stores.clear(id)
            saveable.removeState(entryKey(id))
        }
        nav = next
    }

    /** Pops [entry] only if it is still on top (guards double exits, e.g. Back plus end of series). */
    fun close(entry: MobileEntry) {
        if (nav.top?.id == entry.id) navigate(nav.pop())
    }

    // Inner handlers (live channel list, onboarding progress, players) are composed later and win.
    BackHandler(enabled = nav.canGoBack) { nav.back()?.let(::navigate) }

    val top = nav.top
    if (top != null && nav.isFullscreen) {
        key(top.id) {
            EntryScope(top, stores, saveable) {
                FullscreenEntry(
                    top,
                    dependencies,
                    onChannelChanged = { screen -> if (nav.top?.id == top.id) navigate(nav.replaceTop(screen)) },
                    push = { navigate(nav.push(it)) },
                    onClose = { close(top) },
                )
            }
        }
        return
    }

    val widthDp = LocalConfiguration.current.screenWidthDp
    val title = when (top?.screen) {
        is MobileScreen.MovieDetail -> stringResource(R.string.nav_movies)
        is MobileScreen.SeriesDetail -> stringResource(R.string.nav_series)
        MobileScreen.AddPlaylist -> stringResource(R.string.settings_add_playlist)
        null, is MobileScreen.LivePlayer, is MobileScreen.VodPlayer, is MobileScreen.ChannelPlayer, is MobileScreen.CatchupPlayer ->
            stringResource(nav.destination.mobileLabelRes())
    }
    MobileShell(
        current = nav.destination,
        title = title,
        showBack = nav.stack.isNotEmpty() || nav.destination == Destination.Settings,
        navigationType = navigationTypeFor(widthDp),
        onSelect = { navigate(nav.select(it)) },
        onBack = { nav.back()?.let(::navigate) },
    ) {
        if (top == null) {
            saveable.SaveableStateProvider("tab-${nav.destination.name}") {
                TopLevel(nav.destination, dependencies, wide = isWideLayout(widthDp), push = { navigate(nav.push(it)) })
            }
        } else {
            key(top.id) {
                EntryScope(top, stores, saveable) {
                    PushedEntry(top, dependencies, push = { navigate(nav.push(it)) }, onClose = { close(top) })
                }
            }
        }
    }
}

private fun entryKey(id: Long) = "entry-$id"

/** Gives [entry] its own ViewModel store and saved UI state. */
@Composable
private fun EntryScope(entry: MobileEntry, stores: EntryViewModelStores, saveable: SaveableStateHolder, content: @Composable () -> Unit) {
    saveable.SaveableStateProvider(entryKey(entry.id)) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides stores.owner(entry.id)) { content() }
    }
}

@Composable
private fun TopLevel(destination: Destination, dependencies: MobileRootDependencies?, wide: Boolean, push: (MobileScreen) -> Unit) {
    val vod = dependencies?.vod
    when (destination) {
        Destination.Live -> {
            val liveText = LiveBrowseText(
                noPlaylists = stringResource(R.string.live_no_playlists),
                emptyGroup = stringResource(R.string.live_empty_group),
                loadFailed = stringResource(R.string.live_load_failed),
            )
            val live = viewModel(key = "mobile-live") {
                LiveBrowseViewModel(dependencies?.shared?.liveBrowseSource ?: EmptyLiveBrowseSource, liveText)
            }
            MobileLiveScreen(
                viewModel = live,
                wide = wide,
                onPlay = { channels, index ->
                    val row = channels.getOrNull(index)
                    if (dependencies?.playback != null && dependencies.guide != null && row != null) {
                        push(MobileScreen.ChannelPlayer(row.playlistId, row.ordinal))
                    } else if (dependencies?.playback != null) {
                        push(MobileScreen.LivePlayer(channels, index))
                    } else {
                        channels.getOrNull(index)?.let { dependencies?.shared?.onPlayChannel?.invoke(it) }
                    }
                },
            )
        }
        Destination.Movies, Destination.Series -> {
            if (vod == null) {
                MobilePlaceholder(destination)
            } else {
                val kind = if (destination == Destination.Movies) VodKind.MOVIE else VodKind.SERIES
                val browse = viewModel(key = "mobile-browse-${kind.name}") { VodBrowseViewModel(kind, vod.repository) }
                MobileVodBrowseScreen(viewModel = browse, onOpen = { card -> push(card.detailScreen()) })
            }
        }
        Destination.Settings -> MobileSettingsScreen(
            onAddPlaylist = if (dependencies != null) ({ push(MobileScreen.AddPlaylist) }) else null,
        )
        Destination.Guide -> {
            val guide = dependencies?.guide
            if (guide == null) {
                MobilePlaceholder(destination)
            } else {
                MobileGuideScreen(
                    sections = dependencies.shared.liveBrowseSource.sections(),
                    guide = guide,
                    onPlayCatchup = { request -> if (dependencies.playback != null) push(MobileScreen.CatchupPlayer(request)) },
                    onWatchLive = { channel ->
                        if (dependencies.playback != null) {
                            push(MobileScreen.ChannelPlayer(channel.playlistId, channel.ordinal))
                        } else {
                            dependencies.shared.onPlayChannel(channel.toRow())
                        }
                    },
                )
            }
        }
        Destination.Home, Destination.Search -> MobilePlaceholder(destination)
    }
}

/** Fallback hand-off when there is no in-app player (the number is not known here). */
private fun LiveChannel.toRow() = ChannelRow(playlistId, ordinal, number = null, name = name, logoUrl = logoUrl, hasCatchup = archiveDays > 0, isHeader = isHeader)

private fun VodCard.detailScreen(): MobileScreen =
    if (kind == VodKind.SERIES) MobileScreen.SeriesDetail(playlistId, anchorId) else MobileScreen.MovieDetail(playlistId, anchorId)

@Composable
private fun PushedEntry(entry: MobileEntry, dependencies: MobileRootDependencies?, push: (MobileScreen) -> Unit, onClose: () -> Unit) {
    val vod = dependencies?.vod
    when (val screen = entry.screen) {
        is MobileScreen.MovieDetail -> {
            if (vod == null) return MobilePlaceholder(Destination.Movies)
            val detail = viewModel {
                TitleDetailViewModel(
                    screen.playlistId, screen.itemId, vod.repository, vod.movieInfo, vod.picks, vod.favourites,
                    vod.progress, vod.preferences, vod.accounts,
                )
            }
            MobileTitleDetailScreen(
                viewModel = detail,
                onPlay = { request ->
                    if (dependencies.playback != null) push(MobileScreen.VodPlayer(request, onClosed = detail::refreshProgress))
                },
            )
        }
        is MobileScreen.SeriesDetail -> {
            if (vod == null) return MobilePlaceholder(Destination.Series)
            val series = viewModel {
                SeriesViewModel(
                    screen.playlistId, screen.itemId, vod.repository, vod.seriesInfo, vod.picks, vod.favourites,
                    vod.progress, vod.preferences, vod.accounts,
                )
            }
            MobileSeriesScreen(
                viewModel = series,
                onPlay = { request ->
                    if (dependencies.playback != null) {
                        push(MobileScreen.VodPlayer(request, onNextEpisode = series::nextEpisodeRequest, onClosed = series::refreshProgress))
                    }
                },
            )
        }
        MobileScreen.AddPlaylist -> {
            val shared = dependencies?.shared ?: return MobilePlaceholder(Destination.Settings)
            val onboarding = viewModel { OnboardingViewModel(shared.onboarder, shared.isXtreamShaped) }
            MobileOnboardingScreen(
                viewModel = onboarding,
                isFirstRun = false,
                onFinished = { onClose() },
                onCancel = onClose,
            )
        }
        is MobileScreen.LivePlayer, is MobileScreen.VodPlayer, is MobileScreen.ChannelPlayer, is MobileScreen.CatchupPlayer ->
            Unit // rendered fullscreen by MobileApp
    }
}

@Composable
private fun FullscreenEntry(
    entry: MobileEntry,
    dependencies: MobileRootDependencies?,
    onChannelChanged: (MobileScreen) -> Unit,
    push: (MobileScreen) -> Unit,
    onClose: () -> Unit,
) {
    val playback = dependencies?.playback
    if (playback == null) {
        // Unreachable when wired (players are only pushed with playback dependencies).
        BackHandler { onClose() }
        MobileEmpty(title = stringResource(R.string.mobile_live_unavailable), actionLabel = stringResource(R.string.mobile_back), onAction = onClose)
        return
    }
    when (val screen = entry.screen) {
        is MobileScreen.LivePlayer -> MobileLivePlayerScreen(
            controller = playback.controller,
            player = playback.player,
            channels = screen.channels,
            index = screen.index,
            liveSources = playback.liveSources,
            onChannelChanged = { index -> onChannelChanged(screen.copy(index = index)) },
            onExit = onClose,
        )
        is MobileScreen.VodPlayer -> {
            val progress = dependencies.vod?.progress ?: return
            MobileVodPlayerScreen(
                controller = playback.controller,
                player = playback.player,
                request = screen.request,
                progress = progress,
                saveScope = playback.saveScope,
                onNextEpisode = screen.onNextEpisode,
                onExit = {
                    screen.onClosed()
                    onClose()
                },
            )
        }
        is MobileScreen.ChannelPlayer -> {
            val guide = dependencies.guide ?: return
            MobileChannelPlayerHost(
                playlistId = screen.playlistId,
                ordinal = screen.ordinal,
                guide = guide,
                playback = playback,
                onStartCatchup = { request -> push(MobileScreen.CatchupPlayer(request)) },
                onChannelChanged = { ordinal -> if (ordinal != screen.ordinal) onChannelChanged(screen.copy(ordinal = ordinal)) },
                onExit = onClose,
            )
        }
        is MobileScreen.CatchupPlayer -> {
            val guide = dependencies.guide ?: return
            MobileCatchupPlayerHost(request = screen.request, guide = guide, playback = playback, onExit = onClose)
        }
        else -> Unit
    }
}

private object EmptyLiveBrowseSource : LiveBrowseSource {
    override fun sections(): Flow<List<PlaylistSection>> = flowOf(emptyList())

    override fun channels(group: GroupKey): Flow<List<ChannelRow>> = flowOf(emptyList())
}

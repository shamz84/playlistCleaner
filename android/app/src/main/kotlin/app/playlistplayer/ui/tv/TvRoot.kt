package app.playlistplayer.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.live.CatchupPlayerViewModel
import app.playlistplayer.feature.live.ExoPlayerDriver
import app.playlistplayer.feature.live.LivePlayerViewModel
import app.playlistplayer.feature.onboarding.OnboardingViewModel
import app.playlistplayer.feature.onboarding.PlaylistOnboarder
import app.playlistplayer.feature.settings.ParentalState
import app.playlistplayer.feature.settings.PinPromptBroker
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.feature.shell.ShellViewModel
import app.playlistplayer.feature.vod.SeriesViewModel
import app.playlistplayer.feature.vod.TitleDetailViewModel
import app.playlistplayer.ui.common.AppStateDependencies
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.GuideDependencies
import app.playlistplayer.ui.common.PlaybackDependencies
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.ScreenDependencies
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.VodDependencies
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import app.playlistplayer.ui.common.live.LiveBrowseText
import app.playlistplayer.ui.common.live.LiveBrowseViewModel
import app.playlistplayer.ui.common.live.LiveTvViewModel
import app.playlistplayer.ui.tv.livetv.LiveTvScreen
import app.playlistplayer.ui.common.navigation.EntryViewModelStores
import app.playlistplayer.ui.tv.components.TvEmptyState
import app.playlistplayer.ui.tv.components.TvLoadingState
import app.playlistplayer.ui.tv.home.HomeScreen
import app.playlistplayer.ui.tv.live.LiveScreen
import app.playlistplayer.ui.tv.navigation.TvBackStack
import app.playlistplayer.ui.tv.navigation.TvEntry
import app.playlistplayer.ui.tv.navigation.TvScreen
import app.playlistplayer.ui.tv.onboarding.OnboardingScreen
import app.playlistplayer.ui.tv.player.CatchupPlayerScreen
import app.playlistplayer.ui.tv.player.LivePlayerScreen
import app.playlistplayer.ui.tv.player.PlayerVideoSurface
import app.playlistplayer.feature.live.isStreamActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.playlistplayer.ui.tv.search.SearchScreen
import app.playlistplayer.ui.tv.settings.TvConfirmDialog
import app.playlistplayer.ui.tv.settings.TvPinPromptDialog
import app.playlistplayer.ui.tv.settings.TvSettingsScreen
import app.playlistplayer.ui.tv.shell.PlaceholderDestination
import app.playlistplayer.ui.tv.shell.TvShell
import app.playlistplayer.ui.tv.theme.TvDimens
import app.playlistplayer.ui.tv.theme.TvTheme
import app.playlistplayer.ui.tv.vod.SeriesScreen
import app.playlistplayer.ui.tv.vod.TitleDetailScreen
import app.playlistplayer.ui.tv.vod.VodBrowseScreen
import app.playlistplayer.ui.tv.vod.VodPlayerScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the TV tree (and, as `MobileRootDependencies.shared`, the phone tree) needs from the app
 * container. `MainActivity` builds it from `AppContainer`.
 *
 * @property hasPlaylists emits whether at least one playlist exists; false shows first-run onboarding.
 * @property isXtreamShaped `{ AccountExtractor.fromUrl(it) != null }` from `:core:parsing`.
 * @property onPlayChannel fallback hand-off when there is no in-app player ([playback]/[guide] unset).
 * @property vod enables Movies / Series, title details and the VOD player (with [playback]).
 * @property playback the app-scoped player; with [guide] it enables the live and catch-up players.
 * @property screens Home, Search and Settings ViewModel factories; without them those show placeholders.
 * @property app global PIN prompt and the "reset required" state.
 */
class TvRootDependencies(
    val onboarder: PlaylistOnboarder,
    val isXtreamShaped: (String) -> Boolean,
    val liveBrowseSource: LiveBrowseSource,
    val hasPlaylists: Flow<Boolean>,
    val onPlayChannel: (ChannelRow) -> Unit = {},
    val vod: VodDependencies? = null,
    val playback: PlaybackDependencies? = null,
    val guide: GuideDependencies? = null,
    val screens: ScreenDependencies? = null,
    val app: AppStateDependencies? = null,
)

object TvRootTags {
    const val RESET_REQUIRED = "tv.resetRequired"
}

/**
 * Root of the TV UI tree (tv-material). First run (no playlists) shows onboarding; otherwise the
 * shell (side rail: Live TV, Home, Movies, Series, Search, Settings; lands on Live TV) with full-screen screens
 * (players, title details) pushed above it. Without [dependencies] it shows the shell with
 * placeholder destinations and an empty Live screen.
 *
 * Back: a pushed screen handles Back first (player overlay → leave the player), then pops; the
 * shell then moves content → rail → "Exit app?" (U9). The shell's state is kept while covered
 * ([SaveableStateHolder]; destination ViewModels live in the activity store), so returning from a
 * player restores the destination (the guide restores its focused programme itself).
 */
@Composable
fun TvRoot(dependencies: TvRootDependencies? = null) {
    TvTheme {
        Box(modifier = Modifier.fillMaxSize().background(UiTokens.Background)) {
            val parentalState = dependencies?.app?.parentalState?.collectAsStateWithLifecycle()
            if (parentalState?.value == ParentalState.ResetRequired) {
                TvResetRequiredScreen(onReset = dependencies.app.resetEverything)
                return@Box
            }

            val hasPlaylists by remember(dependencies) { dependencies?.hasPlaylists ?: flowOf(true) }
                .collectAsState(initial = null)
            var justOnboarded by rememberSaveable { mutableStateOf(false) }
            var addingPlaylist by rememberSaveable { mutableStateOf(false) }
            var onboardingRun by rememberSaveable { mutableIntStateOf(0) }
            // A reset (or removing the last playlist) returns to first-run onboarding.
            LaunchedEffect(hasPlaylists) { if (hasPlaylists == false && !addingPlaylist) justOnboarded = false }

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

                else -> TvApp(dependencies, onAddPlaylist = { addingPlaylist = true })
            }
        }
    }
}

@Composable
private fun TvApp(dependencies: TvRootDependencies?, onAddPlaylist: () -> Unit) {
    val activity = LocalActivity.current
    var stack by remember { mutableStateOf(TvBackStack()) }
    val stores = remember { EntryViewModelStores() }
    val saveable = rememberSaveableStateHolder()
    DisposableEffect(Unit) { onDispose { stores.clearAll() } }
    val shell = viewModel { ShellViewModel() }
    /** Group to open when Live TV shows next (Home → live group). */
    var pendingGroup by remember { mutableStateOf<GroupKey?>(null) }

    fun navigate(next: TvBackStack) {
        stack.removedIn(next).forEach { id ->
            stores.clear(id)
            saveable.removeState(entryKey(id))
        }
        stack = next
    }

    fun push(screen: TvScreen) = navigate(stack.push(screen))

    /** Pops [entry] only if it is still on top (guards double exits, e.g. Back plus end of series). */
    fun close(entry: TvEntry) {
        if (stack.top?.id == entry.id) navigate(stack.pop())
    }

    val top = stack.top
    // Inner handlers (player overlays, dialogs) are composed later and win.
    BackHandler(enabled = top != null) { navigate(stack.pop()) }

    if (top == null) {
        saveable.SaveableStateProvider("tv-shell") {
            TvShell(viewModel = shell, onExit = { activity?.finish() }) { destination ->
                TvDestination(
                    destination = destination,
                    dependencies = dependencies,
                    push = ::push,
                    onAddPlaylist = dependencies?.let { onAddPlaylist },
                    pendingGroup = pendingGroup,
                    onPendingGroupConsumed = { pendingGroup = null },
                    openGroup = { group ->
                        pendingGroup = group
                        shell.select(Destination.LiveTv)
                    },
                )
            }
        }
    } else {
        key(top.id) {
            EntryScope(top, stores, saveable) {
                Box(modifier = Modifier.fillMaxSize().background(UiTokens.Background)) {
                    TvEntryContent(
                        top,
                        dependencies,
                        push = ::push,
                        replaceTop = { navigate(stack.replaceTop(it)) },
                        onClose = { close(top) },
                        // Back from the live player to Live TV: the stream keeps playing in the preview.
                        keepLivePlaying = { stack.top == null && shell.state.value.current == Destination.LiveTv },
                    )
                }
            }
        }
    }

    // PIN prompts raised outside Settings (Settings shows its own while it is on screen).
    val shellState by shell.state.collectAsStateWithLifecycle()
    val settingsVisible = top == null && shellState.current == Destination.Settings && dependencies?.screens != null
    // The Live TV preview keeps a stream playing; any other destination stops it.
    val showsOtherDestination = top == null && shellState.current != Destination.LiveTv
    LaunchedEffect(showsOtherDestination) {
        if (showsOtherDestination) dependencies?.playback?.controller?.stop()
    }
    dependencies?.app?.let { app -> if (!settingsVisible) TvGlobalPinPrompt(app.pinPrompts) }
}

private fun entryKey(id: Long) = "tv-entry-$id"

/** Gives [entry] its own ViewModel store and saved UI state. */
@Composable
private fun EntryScope(entry: TvEntry, stores: EntryViewModelStores, saveable: SaveableStateHolder, content: @Composable () -> Unit) {
    saveable.SaveableStateProvider(entryKey(entry.id)) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides stores.owner(entry.id)) { content() }
    }
}

@Composable
private fun TvDestination(
    destination: Destination,
    dependencies: TvRootDependencies?,
    push: (TvScreen) -> Unit,
    onAddPlaylist: (() -> Unit)?,
    pendingGroup: GroupKey?,
    onPendingGroupConsumed: () -> Unit,
    openGroup: (GroupKey) -> Unit,
) {
    val screens = dependencies?.screens
    val vod = dependencies?.vod
    val players = dependencies?.playback != null && dependencies.guide != null
    val playChannel: (Long, Int) -> Unit = { playlistId, ordinal -> push(TvScreen.LivePlayer(playlistId, ordinal)) }
    val openTitle: (VodKind, Long, String) -> Unit = { kind, playlistId, itemId ->
        if (vod != null) push(if (kind == VodKind.SERIES) TvScreen.SeriesDetail(playlistId, itemId) else TvScreen.MovieDetail(playlistId, itemId))
    }
    when (destination) {
        Destination.Home -> {
            if (screens == null) return PlaceholderDestination(destination)
            val home = viewModel(key = "tv-home") { screens.home() }
            HomeScreen(
                viewModel = home,
                onResume = { item -> openTitle(item.title.kind, item.title.playlistId, item.title.anchorId) },
                onPlayChannel = { channel -> if (players) playChannel(channel.playlistId, channel.ordinal) },
                onOpenTitle = { title -> openTitle(title.kind, title.playlistId, title.anchorId) },
                onOpenGroup = { group -> openGroup(group.key) },
            )
        }
        Destination.LiveTv -> {
            val liveText = LiveBrowseText(
                noPlaylists = stringResource(R.string.live_no_playlists),
                emptyGroup = stringResource(R.string.live_empty_group),
                loadFailed = stringResource(R.string.live_load_failed),
            )
            val guide = dependencies?.guide
            if (guide != null && dependencies.playback != null) {
                val liveTv = viewModel(key = "tv-livetv") {
                    LiveTvViewModel(
                        dependencies.liveBrowseSource,
                        { playlistId -> guide.coordinator.lastChannel(playlistId) },
                        liveText,
                        history = guide.history.state,
                        streamActive = dependencies.playback.controller.state.map { it.isStreamActive() }.distinctUntilChanged(),
                    )
                }
                LaunchedEffect(pendingGroup) {
                    pendingGroup?.let {
                        liveTv.selectGroup(it)
                        onPendingGroupConsumed()
                    }
                }
                return LiveTvScreen(
                    viewModel = liveTv,
                    guide = guide,
                    onPlayChannel = { channel -> playChannel(channel.playlistId, channel.ordinal) },
                    onPlayCatchup = { request -> push(TvScreen.CatchupPlayer(request)) },
                    // The app's single player: the preview is the stream already playing.
                    preview = { modifier -> PlayerVideoSurface(dependencies.playback.player.exoPlayer, modifier) },
                    onFullscreen = { ref -> playChannel(ref.playlistId, ref.ordinal) },
                )
            }
            // Without the guide wiring: the plain channel list.
            val live = viewModel(key = "live") {
                LiveBrowseViewModel(dependencies?.liveBrowseSource ?: EmptyLiveBrowseSource, liveText)
            }
            LaunchedEffect(pendingGroup) {
                pendingGroup?.let {
                    live.selectGroup(it)
                    onPendingGroupConsumed()
                }
            }
            LiveScreen(
                viewModel = live,
                onPlay = { row -> if (players) playChannel(row.playlistId, row.ordinal) else dependencies?.onPlayChannel?.invoke(row) },
            )
        }
        Destination.Movies, Destination.Series -> {
            if (vod == null) return PlaceholderDestination(destination)
            val kind = if (destination == Destination.Movies) VodKind.MOVIE else VodKind.SERIES
            val browse = viewModel(key = "tv-browse-${kind.name}") { vod.browseViewModel(kind) }
            LaunchedEffect(browse) {
                browse.refreshContinueWatching()
                vod.visibilityChanges.collect { browse.refresh() }
            }
            VodBrowseScreen(viewModel = browse, onOpen = { card -> openTitle(card.kind, card.playlistId, card.anchorId) })
        }
        Destination.Search -> {
            if (screens == null) return PlaceholderDestination(destination)
            val search = viewModel(key = "tv-search") { screens.search() }
            SearchScreen(
                viewModel = search,
                onPlayChannel = { hit -> if (players) playChannel(hit.playlistId, hit.ordinal) },
                onOpenTitle = { hit -> openTitle(hit.kind, hit.playlistId, hit.anchorId) },
            )
        }
        Destination.Settings -> {
            if (screens == null || onAddPlaylist == null) return PlaceholderDestination(destination, onAddPlaylist = onAddPlaylist)
            val settings = viewModel(key = "tv-settings") { screens.settings() }
            TvSettingsScreen(viewModel = settings, onAddPlaylist = onAddPlaylist)
        }
    }
}

@Composable
private fun TvEntryContent(
    entry: TvEntry,
    dependencies: TvRootDependencies?,
    push: (TvScreen) -> Unit,
    replaceTop: (TvScreen) -> Unit,
    onClose: () -> Unit,
    keepLivePlaying: () -> Boolean = { false },
) {
    val vod = dependencies?.vod
    val playback = dependencies?.playback
    val guide = dependencies?.guide
    when (val screen = entry.screen) {
        is TvScreen.LivePlayer -> {
            if (playback == null || guide == null) return Unavailable(onClose)
            val driver = remember(playback) { ExoPlayerDriver(playback.controller, playback.player) }
            val viewModel = viewModel(key = "tv-live-player") {
                LivePlayerViewModel(screen.playlistId, screen.ordinal, guide.channels, guide.coordinator, guide.nowNext, driver, guide.clock, history = guide.history)
            }
            LivePlayerScreen(
                viewModel = viewModel,
                player = playback.player,
                onStartCatchup = { request -> push(TvScreen.CatchupPlayer(request)) },
                onExit = onClose,
                keepPlayingOnExit = keepLivePlaying,
            )
        }
        is TvScreen.CatchupPlayer -> {
            if (playback == null || guide == null) return Unavailable(onClose)
            val driver = remember(playback) { ExoPlayerDriver(playback.controller, playback.player) }
            val viewModel = viewModel(key = "tv-catchup-player") {
                CatchupPlayerViewModel(screen.request, guide.catchup, driver, guide.clock)
            }
            CatchupPlayerScreen(viewModel = viewModel, player = playback.player, onExit = onClose)
        }
        is TvScreen.MovieDetail -> {
            if (vod == null) return Unavailable(onClose)
            val detail = viewModel(key = "tv-movie") {
                TitleDetailViewModel(
                    screen.playlistId, screen.itemId, vod.repository, vod.movieInfo, vod.picks, vod.favourites,
                    vod.progress, vod.preferences, vod.accounts,
                )
            }
            TitleDetailScreen(
                viewModel = detail,
                onPlay = { request -> if (playback != null) push(TvScreen.VodPlayer(request, onClosed = detail::refreshProgress)) },
            )
        }
        is TvScreen.SeriesDetail -> {
            if (vod == null) return Unavailable(onClose)
            val series = viewModel(key = "tv-series") {
                SeriesViewModel(
                    screen.playlistId, screen.itemId, vod.repository, vod.seriesInfo, vod.picks, vod.favourites,
                    vod.progress, vod.preferences, vod.accounts,
                )
            }
            SeriesScreen(
                viewModel = series,
                onPlay = { request ->
                    if (playback != null) {
                        push(TvScreen.VodPlayer(request, onNextEpisode = series::nextEpisodeRequest, onClosed = series::refreshProgress))
                    }
                },
            )
        }
        is TvScreen.VodPlayer -> {
            if (playback == null || vod == null) return Unavailable(onClose)
            VodPlayerScreen(
                controller = playback.controller,
                player = playback.player,
                request = screen.request,
                progress = vod.progress,
                saveScope = playback.saveScope,
                trackPreferences = vod.trackPreferences,
                onNextEpisode = screen.onNextEpisode,
                onExit = {
                    screen.onClosed()
                    onClose()
                },
            )
        }
    }
}

/** Unreachable when wired (screens are only pushed with their dependencies). */
@Composable
private fun Unavailable(onClose: () -> Unit) {
    TvEmptyState(
        title = stringResource(R.string.live_load_failed),
        actionLabel = stringResource(R.string.common_cancel),
        onAction = onClose,
        modifier = Modifier.fillMaxSize().padding(TvDimens.ScreenPadding),
    )
}

@Composable
private fun TvGlobalPinPrompt(broker: PinPromptBroker) {
    val request by broker.request.collectAsStateWithLifecycle()
    request?.let { r ->
        TvPinPromptDialog(r, onSubmit = { pin -> broker.submit(r.id, pin) }, onCancel = { broker.cancel(r.id) })
    }
}

/**
 * Parental storage is unreadable (U12 fail-closed): everything stays hidden and only "Reset app"
 * (confirmed) recovers.
 */
@Composable
private fun TvResetRequiredScreen(onReset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().testTag(TvRootTags.RESET_REQUIRED)) {
        TvEmptyState(
            title = stringResource(R.string.app_reset_required_title),
            message = stringResource(R.string.settings_pin_reset_required),
            actionLabel = stringResource(R.string.settings_app_reset),
            onAction = { confirming = true },
            modifier = Modifier.fillMaxSize().padding(TvDimens.ScreenPadding),
        )
    }
    if (confirming) {
        TvConfirmDialog(
            title = stringResource(R.string.settings_confirm_reset_title),
            message = stringResource(R.string.settings_confirm_reset_message),
            confirmLabel = stringResource(R.string.settings_reset),
            onConfirm = {
                confirming = false
                onReset()
            },
            onDismiss = { confirming = false },
        )
    }
}

private object EmptyLiveBrowseSource : LiveBrowseSource {
    override fun sections(): Flow<List<PlaylistSection>> = flowOf(emptyList())

    override fun channels(group: GroupKey): Flow<List<ChannelRow>> = flowOf(emptyList())
}

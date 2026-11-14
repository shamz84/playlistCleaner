package app.playlistplayer.wiring

import androidx.annotation.MainThread
import app.playlistplayer.AppContainer
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.feature.live.LivePlanResult
import app.playlistplayer.ui.common.AppStateDependencies
import app.playlistplayer.ui.common.GuideDependencies
import app.playlistplayer.ui.common.PlaybackDependencies
import app.playlistplayer.ui.common.ScreenDependencies
import app.playlistplayer.ui.common.VodDependencies
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.mobile.MobileRootDependencies
import app.playlistplayer.ui.tv.TvRootDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Both trees' dependencies; [mobile] shares [tv] (`MobileRootDependencies.shared`). */
class UiDependencies(val tv: TvRootDependencies, val mobile: MobileRootDependencies)

/**
 * Builds the UI dependencies from the container. Call on the main thread after
 * [AppContainer.ready] (it creates the app-scoped player and reads the parental state).
 */
@MainThread
fun AppContainer.uiDependencies(): UiDependencies {
    val vod = VodDependencies(
        repository = vodCatalogRepository,
        movieInfo = movieInfoLoader,
        seriesInfo = seriesInfoLoader,
        picks = titlePickStore,
        favourites = favouritesStore,
        progress = playbackProgressRepository,
        preferences = versionPreferences,
        accounts = accountProvider,
        visibilityChanges = visibilityChanges(),
        trending = trendingRepository,
        sorts = vodSortStore,
        trackPreferences = trackPreferenceStore,
        skipSpans = skipSpanRepository,
    )
    val player = PlaybackDependencies(
        controller = playback.controller(),
        player = playback.player(),
        saveScope = applicationScope,
        liveSources = { row -> liveSources(row) },
    )
    val guide = GuideDependencies(
        channels = liveChannelSource,
        grid = epgGridSource,
        coordinator = liveCoordinator,
        catchup = catchupController,
        nowNext = nowNextSource,
        history = channelHistory,
    )
    val screens = ScreenDependencies(
        home = { homeViewModel() },
        search = { searchViewModel() },
        settings = { settingsViewModel() },
    )
    val app = AppStateDependencies(
        pinPrompts = pinPromptBroker,
        parentalState = parentalControl.state,
        resetEverything = {
            // Application scope: the reset-only screen leaves composition as soon as the state changes.
            applicationScope.launch {
                try {
                    resetEverything()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.withTag("Reset").e("Reset app failed", e)
                }
            }
        },
    )
    val shared = TvRootDependencies(
        onboarder = playlistOnboarder,
        isXtreamShaped = isXtreamShaped,
        liveBrowseSource = liveBrowseSource,
        hasPlaylists = hasPlaylists(),
        vod = vod,
        playback = player,
        guide = guide,
        screens = screens,
        app = app,
        onReorderPlaylists = { order -> applicationScope.launch { preferencesRepository.setPlaylistOrder(order) } },
    )
    return UiDependencies(shared, MobileRootDependencies(shared = shared, vod = vod, playback = player, guide = guide))
}

/** The channel first, then its other versions, through the live coordinator (URLs built now, never stored). */
private suspend fun AppContainer.liveSources(row: ChannelRow): List<PlayableSource> {
    val channel = liveChannelSource.channel(row.playlistId, row.ordinal) ?: return emptyList()
    return (liveCoordinator.tune(channel) as? LivePlanResult.Ready)?.plan?.sources.orEmpty()
}

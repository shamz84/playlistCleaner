package app.playlistplayer.ui.mobile.epg

import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.live.CatchupPlaybackController
import app.playlistplayer.feature.live.CatchupPlayerViewModel
import app.playlistplayer.feature.live.EpgGridSource
import app.playlistplayer.feature.live.LiveChannelSource
import app.playlistplayer.feature.live.LivePlaybackCoordinator
import app.playlistplayer.feature.live.LivePlayerViewModel
import app.playlistplayer.feature.live.NowNextSource
import java.time.ZoneId

/**
 * What the phone Guide, the guide-driven live player and the catch-up player need: exactly the
 * constructor inputs of the shared [EpgGridViewModel], [LivePlayerViewModel] and
 * [CatchupPlayerViewModel] (U8/U9), apart from the `PlayerDriver`, which the phone tree builds
 * from `MobilePlaybackDependencies` (the same long-lived controller and player).
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
class MobileGuideDependencies(
    val channels: LiveChannelSource,
    val grid: EpgGridSource,
    val coordinator: LivePlaybackCoordinator,
    val catchup: CatchupPlaybackController,
    val nowNext: NowNextSource,
    val clock: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
)

package app.playlistplayer.ui.mobile.epg

import app.playlistplayer.core.sync.epg.NowNext
import app.playlistplayer.feature.live.CatchupPlaybackController
import app.playlistplayer.feature.live.FakeEpgGridSource
import app.playlistplayer.feature.live.FakeLiveChannelSource
import app.playlistplayer.feature.live.FakePreferences
import app.playlistplayer.feature.live.InMemoryLastChannelStore
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.feature.live.LivePlaybackCoordinator
import app.playlistplayer.feature.live.NowNextSource
import app.playlistplayer.feature.live.accounts
import app.playlistplayer.feature.live.amsterdam
import app.playlistplayer.feature.live.programme
import app.playlistplayer.feature.live.utc
import app.playlistplayer.feature.live.versionPrefs
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import java.time.ZoneId

/**
 * One group (playlist 1, group 2) at 19:40 UTC on 29 Oct 2026 (GMT in London):
 * - BBC One HD (archive 3 days): a past programme (19:00–19:35), the airing one (19:35–20:05) and a future one;
 * - BBC Two HD (no archive): a past programme that is not on catch-up;
 * - ITV 1 HD: no programmes ("No guide data" row).
 */
class GuideFixture {
    val zone: ZoneId = ZoneId.of("Europe/London")
    var now: Long = utc("2026-10-29T19:40:00Z")
    val clock: () -> Long = { now }

    val channels = FakeLiveChannelSource()
    val bbcOne = channels.add(LiveChannel(1, 10, 2, "UK: BBC One HD", streamId = 101, archiveDays = 3))
    val bbcTwo = channels.add(LiveChannel(1, 11, 2, "UK: BBC Two HD", streamId = 102))
    val itv = channels.add(LiveChannel(1, 13, 2, "UK: ITV 1 HD", streamId = 103))

    val grid = FakeEpgGridSource().apply {
        add(10, programme(1, "bbc1", utc("2026-10-29T19:00:00Z"), utc("2026-10-29T19:35:00Z"), "Six O'Clock News"))
        add(10, programme(2, "bbc1", utc("2026-10-29T19:35:00Z"), utc("2026-10-29T20:05:00Z"), "The Celebrity Traitors"))
        add(10, programme(3, "bbc1", utc("2026-10-29T20:05:00Z"), utc("2026-10-29T21:00:00Z"), "Question Time"))
        add(11, programme(4, "bbc2", utc("2026-10-29T19:00:00Z"), utc("2026-10-29T19:38:00Z"), "Gardeners' World"))
    }

    val coordinator = LivePlaybackCoordinator(channels, accounts, amsterdam, FakePreferences(), versionPrefs, InMemoryLastChannelStore())

    val dependencies = MobileGuideDependencies(
        channels = channels,
        grid = grid,
        coordinator = coordinator,
        catchup = CatchupPlaybackController(accounts, amsterdam, FakePreferences(), clock = clock),
        nowNext = NowNextSource { _, _ -> NowNext.NONE },
        clock = clock,
        zone = zone,
    )

    val group = GroupKey(1, 2)

    val sections = listOf(
        PlaylistSection(1, "Main", listOf(GroupItem(1, 2, "🇬🇧 UK | Entertainment", 3), GroupItem(1, 3, "🇬🇧 UK | News", 0))),
        PlaylistSection(2, "Family", listOf(GroupItem(2, 0, "Kids", 4))),
    )
}

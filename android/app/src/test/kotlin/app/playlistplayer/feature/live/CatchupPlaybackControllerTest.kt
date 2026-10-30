package app.playlistplayer.feature.live

import app.playlistplayer.core.player.catchup.CatchupDecision
import app.playlistplayer.core.player.catchup.CatchupUrlStyle
import app.playlistplayer.feature.settings.PlaybackPreferences
import app.playlistplayer.feature.vod.MainDispatcherRule
import app.playlistplayer.feature.vod.SeekDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatchupPlaybackControllerTest {

    @get:Rule
    val main = MainDispatcherRule()

    // Spike §10: "The Celebrity Traitors", 19:00–20:05 UTC on 2026-10-29 (after the Oct 25 DST change).
    private val start = utc("2026-10-29T19:00:00Z")
    private val stop = utc("2026-10-29T20:05:00Z")
    private var now = utc("2026-10-29T21:54:00Z")

    private val bbcOne = LiveChannel(1, ordinal = 10, groupOrdinal = 2, name = "UK: BBC One HD", streamId = 1234, archiveDays = 3)
    private val prefs = FakePreferences()

    private fun controller(seekable: Boolean = true) =
        CatchupPlaybackController(accounts, amsterdam, prefs, clock = { now }, seekable = seekable)

    private fun request(channel: LiveChannel = bbcOne, others: List<LiveChannel> = emptyList(), from: Long = start, to: Long = stop) =
        CatchupRequest(channel, others, from, to, title = "The Celebrity Traitors")

    private suspend fun started(c: CatchupPlaybackController, r: CatchupRequest): CatchupPlayback =
        (c.start(r) as CatchupStart.Started).playback

    @Test
    fun `past programme on an archive channel builds the path URL in Amsterdam time`() = runTest {
        val playback = started(controller(), request())
        val source = playback.currentSource()
        assertThat(source.url).isEqualTo("http://provider.invalid:8080/timeshift/user-secret/pass-secret/65/2026-10-29:20-00/1234.ts")
        assertThat(source.isLive).isFalse()
        assertThat(source.toString()).doesNotContain("pass-secret")
        assertThat(playback.durationMs).isEqualTo(65 * MINUTE)
        assertThat(playback.isRestart).isFalse()
        assertThat(playback.usesOtherVersion).isFalse()
    }

    @Test
    fun `php style and user offset come from the playback preferences`() = runTest {
        prefs.playback = PlaybackPreferences(catchupUrlStyle = CatchupUrlStyle.PHP, catchupOffsetHours = -1)
        val url = started(controller(), request()).currentSource().url
        assertThat(url).isEqualTo(
            "http://provider.invalid:8080/streaming/timeshift.php?username=user-secret&password=pass-secret&stream=1234&start=2026-10-29:19-00&duration=65",
        )
    }

    @Test
    fun `unknown server timezone falls back to UTC`() = runTest {
        val c = CatchupPlaybackController(accounts, { LiveAccountStatus(serverTimezone = null) }, prefs, clock = { now })
        assertThat(started(c, request()).currentSource().url).contains("/65/2026-10-29:19-00/1234.ts")
    }

    @Test
    fun `non-archive channel and out-of-window programmes are not eligible`() = runTest {
        val noArchive = bbcOne.copy(archiveDays = 0)
        assertThat(controller().start(request(noArchive))).isEqualTo(CatchupStart.NotEligible)

        now = start + 3 * 24 * HOUR + MINUTE // started 3 days + 1 minute ago on a 3-day channel
        assertThat(controller().start(request())).isEqualTo(CatchupStart.NotEligible)

        now = start - MINUTE // future
        assertThat(controller().start(request())).isEqualTo(CatchupStart.NotEligible)
    }

    @Test
    fun `no stored account reports NoAccount`() = runTest {
        val c = CatchupPlaybackController(noAccount, amsterdam, prefs, clock = { now })
        assertThat(c.start(request())).isEqualTo(CatchupStart.NoAccount)
    }

    @Test
    fun `restart of the airing programme builds a session at the programme start`() = runTest {
        now = utc("2026-10-29T19:40:00Z")
        val c = controller()
        assertThat(c.decide(request())).isInstanceOf(CatchupDecision.RestartLive::class.java)
        val playback = started(c, request())
        assertThat(playback.isRestart).isTrue()
        assertThat(playback.currentSource().url).contains("/65/2026-10-29:20-00/1234.ts")
        // Only what has aired can be reached.
        assertThat(playback.availableMs()).isEqualTo(40 * MINUTE)
        val seek = playback.seekTo(55 * MINUTE)
        assertThat(seek.timelinePositionMs).isEqualTo(40 * MINUTE)
    }

    @Test
    fun `4K entry without archive plays catch-up through its HD other version with archive`() = runTest {
        val uhd = LiveChannel(1, ordinal = 11, groupOrdinal = 5, name = "UK: BBC One 4K", streamId = 9001, archiveDays = 0)
        val hd = bbcOne.copy(ordinal = 12, streamId = 4321, archiveDays = 3)
        val sd = bbcOne.copy(ordinal = 13, name = "UK: BBC One SD", streamId = 5555, archiveDays = 0)
        val playback = started(controller(), request(uhd, others = listOf(sd, hd)))
        assertThat(playback.usesOtherVersion).isTrue()
        assertThat(playback.channel).isEqualTo(hd)
        assertThat(playback.currentSource().url).endsWith("/65/2026-10-29:20-00/4321.ts")
    }

    @Test
    fun `other versions from another playlist are never used`() = runTest {
        val uhd = LiveChannel(1, ordinal = 11, groupOrdinal = 5, name = "UK: BBC One 4K", streamId = 9001, archiveDays = 0)
        val foreign = bbcOne.copy(playlistId = 2, streamId = 777)
        assertThat(controller().start(request(uhd, others = listOf(foreign)))).isEqualTo(CatchupStart.NotEligible)
    }

    @Test
    fun `seekable source seeks natively on the programme timeline`() = runTest {
        val playback = started(controller(seekable = true), request())
        val command = playback.seekTo(35 * MINUTE)
        assertThat(command).isEqualTo(CatchupSeekCommand.NativeSeek(35 * MINUTE, 35 * MINUTE))
        assertThat(playback.timelinePositionMs(35 * MINUTE)).isEqualTo(35 * MINUTE)
    }

    @Test
    fun `without range support a seek to 35 00 rebuilds the URL at start plus 35 min`() = runTest {
        val playback = started(controller(seekable = false), request())
        val command = playback.seekTo(35 * MINUTE + 20_000) as CatchupSeekCommand.Reload
        assertThat(command.source.url).isEqualTo("http://provider.invalid:8080/timeshift/user-secret/pass-secret/30/2026-10-29:20-35/1234.ts")
        assertThat(command.startPositionMs).isEqualTo(20_000)
        assertThat(command.timelinePositionMs).isEqualTo(35 * MINUTE + 20_000)
        // The player restarts at the residual offset; the timeline still reports 35:20.
        assertThat(playback.timelinePositionMs(20_000)).isEqualTo(35 * MINUTE + 20_000)
    }

    @Test
    fun `catch-up player applies native seeks and rebuilds through the driver`() = runTest(main.dispatcher) {
        val nativeDriver = FakePlayerDriver()
        val nativeVm = CatchupPlayerViewModel(request(), controller(seekable = true), nativeDriver, clock = { now }).also { it.start() }
        advanceUntilIdle()
        assertThat(nativeDriver.plays).hasSize(1)
        assertThat(nativeVm.state.value.durationMs).isEqualTo(65 * MINUTE)
        nativeVm.onSeekKey(SeekDirection.FORWARD, 0) // +30 s
        nativeVm.onSeekKey(SeekDirection.FORWARD, 1) // +30 s
        assertThat(nativeVm.state.value.seek?.targetMs).isEqualTo(60_000)
        nativeVm.onSeekKeyUp()
        assertThat(nativeDriver.seeks).containsExactly(60_000L)
        assertThat(nativeDriver.plays).hasSize(1)
        assertThat(nativeVm.state.value.positionMs).isEqualTo(60_000)

        val rebuildDriver = FakePlayerDriver()
        val rebuildVm = CatchupPlayerViewModel(request(), controller(seekable = false), rebuildDriver, clock = { now }).also { it.start() }
        advanceUntilIdle()
        rebuildVm.seekTo(35 * MINUTE)
        assertThat(rebuildDriver.seeks).isEmpty()
        assertThat(rebuildDriver.plays).hasSize(2)
        assertThat(rebuildDriver.lastSources.single().url).contains("/30/2026-10-29:20-35/1234.ts")
        rebuildDriver.positionMs = 5_000
        rebuildVm.tick()
        assertThat(rebuildVm.state.value.positionMs).isEqualTo(35 * MINUTE + 5_000)
    }

    @Test
    fun `catch-up player shows not eligible without starting playback`() = runTest(main.dispatcher) {
        val driver = FakePlayerDriver()
        val vm = CatchupPlayerViewModel(request(bbcOne.copy(archiveDays = 0)), controller(), driver, clock = { now }).also { it.start() }
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(CatchupError.NOT_ELIGIBLE)
        assertThat(driver.plays).isEmpty()
    }
}

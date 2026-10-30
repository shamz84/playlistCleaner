package app.playlistplayer.feature.live

import app.playlistplayer.core.player.PlaybackError
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.core.sync.epg.GuideProgramme
import app.playlistplayer.core.sync.epg.NowNext
import app.playlistplayer.feature.vod.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePlayerViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeLiveChannelSource()
    private val header = source.add(LiveChannel(1, 9, 2, "##### UK #####", isHeader = true))
    private val hd = source.add(LiveChannel(1, 10, 2, "UK: BBC One HD", streamId = 101, tvgId = "bbcone.uk", archiveDays = 3))
    private val two = source.add(LiveChannel(1, 11, 2, "UK: BBC Two HD", streamId = 102, tvgId = "bbctwo.uk"))
    private val header2 = source.add(LiveChannel(1, 12, 2, "##### MORE #####", isHeader = true))
    private val itv = source.add(LiveChannel(1, 13, 2, "UK: ITV 1 HD", streamId = 103, tvgId = "itv1.uk"))
    private val uhd = source.add(LiveChannel(1, 20, 5, "UK: BBC One 4K", streamId = 201, tvgId = "bbcone.uk"))
    private val fhd = source.add(LiveChannel(1, 21, 6, "UK: BBC One FHD", streamId = 202, tvgId = "bbcone.uk"))

    private val driver = FakePlayerDriver()
    private var now = utc("2026-10-29T19:40:00Z")
    private val airing = GuideProgramme(77, "The Celebrity Traitors", null, utc("2026-10-29T19:00:00Z"), utc("2026-10-29T20:05:00Z"))
    private val next = GuideProgramme(78, "News", null, utc("2026-10-29T20:05:00Z"), utc("2026-10-29T20:30:00Z"))
    private val nowNext = NowNextSource { _, ordinal -> if (ordinal == hd.ordinal) NowNext(airing, next, NowNext.Source.ROOM) else NowNext.NONE }
    private val coordinator = LivePlaybackCoordinator(source, accounts, amsterdam, FakePreferences(), versionPrefs, InMemoryLastChannelStore())

    private fun viewModel(ordinal: Int = hd.ordinal) =
        LivePlayerViewModel(1, ordinal, source, coordinator, nowNext, driver, clock = { now }).also { it.start() }

    private fun playingOrdinal(): String = driver.lastSources.first().id

    @Test
    fun `tunes the selected entry with its other versions and the overlay facts`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(playingOrdinal()).isEqualTo("live:1:10")
        assertThat(driver.lastSources.map { it.id }).containsExactly("live:1:10", "live:1:20", "live:1:21").inOrder()
        assertThat(s.channel).isEqualTo(hd)
        assertThat(s.number).isEqualTo(1)
        assertThat(s.playableCount).isEqualTo(3)
        assertThat(s.qualityBadge).isEqualTo("HD")
        assertThat(s.versions.map { it.version.channel }).containsExactly(uhd, fhd, hd).inOrder()
        assertThat(s.versions.single { it.isCurrent }.version.channel).isEqualTo(hd)
        assertThat(s.nowNext?.now?.title).isEqualTo("The Celebrity Traitors")
        assertThat(s.nowNext?.next?.title).isEqualTo("News")
        assertThat(s.nowNext?.progress).isWithin(0.01f).of(40f / 65f)
    }

    @Test
    fun `channel up and down stay within the group, skip headers and stop at the ends`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.channelDown() // first playable entry: stays
        assertThat(driver.plays).hasSize(1)

        vm.channelUp()
        advanceUntilIdle()
        assertThat(playingOrdinal()).isEqualTo("live:1:11")
        assertThat(vm.state.value.number).isEqualTo(2)

        vm.channelUp() // skips the separator header
        advanceUntilIdle()
        assertThat(playingOrdinal()).isEqualTo("live:1:13")
        assertThat(vm.state.value.number).isEqualTo(3)

        vm.channelUp() // last entry: stays, never crosses into group 5
        advanceUntilIdle()
        assertThat(driver.plays).hasSize(3)

        vm.channelDown()
        advanceUntilIdle()
        assertThat(playingOrdinal()).isEqualTo("live:1:11")
    }

    @Test
    fun `number entry jumps to the position within the group`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onDigit(3) // 3 channels: no longer number fits, commits at once
        advanceUntilIdle()
        assertThat(playingOrdinal()).isEqualTo("live:1:13")
        assertThat(vm.state.value.numberDigits).isEmpty()
    }

    @Test
    fun `number entry commits after the timeout and ignores numbers outside the group`() = runTest(main.dispatcher) {
        (1..12).forEach { n -> source.add(LiveChannel(1, 100 + n, 8, "Channel $n", streamId = 1000L + n)) }
        val vm = viewModel(ordinal = 112) // "Channel 12"
        advanceUntilIdle()

        vm.onDigit(1)
        assertThat(vm.state.value.numberDigits).isEqualTo("1")
        advanceTimeBy(1_400)
        runCurrent()
        assertThat(playingOrdinal()).isEqualTo("live:1:112") // still waiting for a second digit
        advanceTimeBy(200)
        runCurrent()
        assertThat(playingOrdinal()).isEqualTo("live:1:101")

        vm.onDigit(0)
        advanceTimeBy(1_600)
        runCurrent()
        assertThat(vm.state.value.numberNotFound).isEqualTo("0")
        assertThat(playingOrdinal()).isEqualTo("live:1:101")

        vm.onDigit(1)
        vm.onDigit(1) // "11": 110 > 12 commits immediately
        runCurrent()
        assertThat(playingOrdinal()).isEqualTo("live:1:111")

        vm.onDigit(1)
        assertThat(vm.commitNumber()).isTrue() // OK commits the pending "1"
        runCurrent()
        assertThat(playingOrdinal()).isEqualTo("live:1:101")
    }

    @Test
    fun `connection limit shows the counts and does not navigate away`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val primary = driver.lastSources.first()
        driver.mutableState.value = PlaybackState.ConnectionLimit(primary, activeConnections = 1, maxConnections = 1)
        val s = vm.state.value
        assertThat(s.status.error).isEqualTo(PlaybackErrorPanel.ConnectionLimit(1, 1))
        assertThat(s.channel).isEqualTo(hd)
        assertThat(driver.plays).hasSize(1)
        assertThat(driver.stops).isEqualTo(0)

        vm.retry()
        assertThat(driver.retries).isEqualTo(1)

        driver.mutableState.value = PlaybackState.AccountExpired(primary)
        assertThat(vm.state.value.status.error).isEqualTo(PlaybackErrorPanel.AccountExpired)
    }

    @Test
    fun `failover shows the Switched notice and moves the current version`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val sources = driver.lastSources
        driver.mutableState.value = PlaybackState.Retrying(sources[0], PlaybackError.STALL, 1_000)
        assertThat(vm.state.value.status.indicator).isEqualTo(PlaybackStatus.Indicator.RECONNECTING)

        driver.mutableState.value = PlaybackState.Playing(sources[1], notice = sources[1].label)
        val s = vm.state.value
        assertThat(s.status.switchedTo).isEqualTo("UK: BBC One 4K")
        assertThat(s.status.error).isNull()
        assertThat(s.currentVersionId).isEqualTo("live:1:20")
        assertThat(s.qualityBadge).isEqualTo("4K")
        assertThat(s.versions.single { it.isCurrent }.version.channel).isEqualTo(uhd)

        driver.mutableState.value = PlaybackState.Failed(sources[2], PlaybackError(PlaybackError.Kind.HTTP_NOT_FOUND, 405), triedVersions = 3)
        assertThat(vm.state.value.status.error).isEqualTo(PlaybackErrorPanel.Failed("UK: BBC One FHD", 3))
    }

    @Test
    fun `other versions list switches the playing version`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openVersions()
        assertThat(vm.state.value.versionsOpen).isTrue()
        vm.switchTo("live:1:21")
        advanceUntilIdle()
        assertThat(driver.lastSources.map { it.id }).containsExactly("live:1:21", "live:1:20", "live:1:10").inOrder()
        assertThat(vm.state.value.versionsOpen).isFalse()
        assertThat(vm.state.value.currentVersionLabel).isEqualTo("UK: BBC One FHD")
        assertThat(vm.state.value.channel).isEqualTo(hd)
    }

    @Test
    fun `restart is offered for the airing programme of an archive channel and opens catch-up at its start`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.canRestart).isTrue()
        vm.restart()
        val event = vm.events.first() as LivePlayerEvent.StartCatchup
        assertThat(event.request.programmeStartMillis).isEqualTo(airing.startMillis)
        assertThat(event.request.channel).isEqualTo(hd)

        val playback = (CatchupPlaybackController(accounts, amsterdam, FakePreferences(), clock = { now }).start(event.request) as CatchupStart.Started).playback
        assertThat(playback.isRestart).isTrue()
        assertThat(playback.currentSource().url).endsWith("/65/2026-10-29:20-00/101.ts")
    }

    @Test
    fun `no restart offer without archive or guide data`() = runTest(main.dispatcher) {
        val vm = viewModel(ordinal = two.ordinal)
        advanceUntilIdle()
        assertThat(vm.state.value.canRestart).isFalse()

        val noArchive = NowNextSource { _, _ -> NowNext(airing, null, NowNext.Source.ROOM) }
        val vm2 = LivePlayerViewModel(1, itv.ordinal, source, coordinator, noArchive, driver, clock = { now }).also { it.start() }
        advanceUntilIdle()
        assertThat(vm2.state.value.canRestart).isFalse()
    }

    @Test
    fun `unknown channel shows not found without playing`() = runTest(main.dispatcher) {
        val vm = viewModel(ordinal = 999)
        advanceUntilIdle()
        assertThat(vm.state.value.startError).isEqualTo(LiveStartError.NOT_FOUND)
        assertThat(driver.plays).isEmpty()
    }

    @Test
    fun `plays only after start and resumes live when shown again`() = runTest(main.dispatcher) {
        val vm = LivePlayerViewModel(1, hd.ordinal, source, coordinator, nowNext, driver, clock = { now })
        advanceUntilIdle()
        assertThat(driver.plays).isEmpty()

        vm.start()
        advanceUntilIdle()
        assertThat(driver.plays).hasSize(1)

        // A catch-up session used the shared driver, then closed; the live screen starts again.
        driver.stop()
        vm.start()
        advanceUntilIdle()
        assertThat(driver.plays).hasSize(2)
        assertThat(playingOrdinal()).isEqualTo("live:1:10")
        assertThat(vm.state.value.channel?.ordinal).isEqualTo(hd.ordinal)
    }

    @Test
    fun `track choices go through the driver`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTracksChanged(
            listOf(
                app.playlistplayer.feature.vod.TrackOption("0:0", app.playlistplayer.feature.vod.TrackType.AUDIO, "English", "en", selected = true),
                app.playlistplayer.feature.vod.TrackOption("0:1", app.playlistplayer.feature.vod.TrackType.AUDIO, "Deutsch", "de"),
            ),
        )
        vm.selectAudio("0:1")
        assertThat(vm.state.value.tracks.selectedAudioId).isEqualTo("0:1")
        assertThat(driver.appliedTracks).containsExactly("0:1" to null)
    }
}

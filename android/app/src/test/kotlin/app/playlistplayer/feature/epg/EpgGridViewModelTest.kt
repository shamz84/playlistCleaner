package app.playlistplayer.feature.epg

import app.playlistplayer.feature.live.CatchupPlaybackController
import app.playlistplayer.feature.live.CatchupStart
import app.playlistplayer.feature.live.EpgGridSource
import app.playlistplayer.feature.live.FakeEpgGridSource
import app.playlistplayer.feature.live.FakeLiveChannelSource
import app.playlistplayer.feature.live.FakePreferences
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.feature.live.OtherVersionsResolver
import app.playlistplayer.feature.live.accounts
import app.playlistplayer.feature.live.amsterdam
import app.playlistplayer.feature.live.programme
import app.playlistplayer.feature.live.utc
import app.playlistplayer.feature.vod.MainDispatcherRule
import app.playlistplayer.ui.common.GroupKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class EpgGridViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val zone = ZoneId.of("Europe/London") // GMT after the Oct 25 DST change
    private var now = utc("2026-10-29T19:40:00Z")

    private val channels = FakeLiveChannelSource()
    private val header = channels.add(LiveChannel(1, 9, 2, "##### UK #####", isHeader = true))
    private val hd = channels.add(LiveChannel(1, 10, 2, "UK: BBC One HD", streamId = 101, archiveDays = 3))
    private val two = channels.add(LiveChannel(1, 11, 2, "UK: BBC Two HD", streamId = 102))
    private val itv = channels.add(LiveChannel(1, 13, 2, "UK: ITV 1 HD", streamId = 103))
    private val c4k = channels.add(LiveChannel(1, 14, 2, "UK: Channel 4 4K", streamId = 104))
    private val c4hd = channels.add(LiveChannel(1, 30, 9, "UK: Channel 4 HD", streamId = 301, archiveDays = 2))

    private val others = HashMap<Int, List<LiveChannel>>().apply { put(c4k.ordinal, listOf(c4hd)) }
    private val resolver = OtherVersionsResolver { others[it.ordinal].orEmpty() }

    private val grid = FakeEpgGridSource().apply {
        add(10, programme(1, "bbc1", utc("2026-10-29T18:00:00Z"), utc("2026-10-29T19:00:00Z"), "Six O'Clock News"))
        add(10, programme(2, "bbc1", utc("2026-10-29T19:00:00Z"), utc("2026-10-29T20:05:00Z"), "The Celebrity Traitors"))
        add(10, programme(3, "bbc1", utc("2026-10-29T20:05:00Z"), utc("2026-10-29T21:00:00Z"), "Question Time"))
        add(11, programme(4, "bbc2", utc("2026-10-29T18:30:00Z"), utc("2026-10-29T19:30:00Z"), "Gardeners' World"))
        add(11, programme(5, "bbc2", utc("2026-10-29T19:30:00Z"), utc("2026-10-29T20:30:00Z"), "University Challenge"))
        add(14, programme(6, "c4", utc("2026-10-29T19:00:00Z"), utc("2026-10-29T19:35:00Z"), "Channel 4 News"))
    }

    private fun viewModel() = EpgGridViewModel(GroupKey(1, 2), channels, grid, resolver, clock = { now }, zone = zone)

    private fun TestScope.events(vm: EpgGridViewModel): MutableList<EpgEvent> {
        val list = mutableListOf<EpgEvent>()
        backgroundScope.launch { vm.events.toList(list) }
        return list
    }

    private fun EpgGridUiState.row(ordinal: Int) = rows.single { it.channel.ordinal == ordinal }
    private fun EpgGridUiState.cell(ordinal: Int, id: Long) = row(ordinal).cells.single { it.programmeId == id }

    @Test
    fun `opens at now with a now-line, 30-minute slots and labelled empty rows`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.windowStartMillis).isEqualTo(utc("2026-10-29T19:30:00Z"))
        assertThat(s.windowEndMillis).isEqualTo(utc("2026-10-29T21:30:00Z"))
        assertThat(s.slots).containsExactly(
            utc("2026-10-29T19:30:00Z"), utc("2026-10-29T20:00:00Z"), utc("2026-10-29T20:30:00Z"), utc("2026-10-29T21:00:00Z"),
        ).inOrder()
        assertThat(s.nowMillis).isEqualTo(now)
        assertThat(s.showNowLine).isTrue()
        assertThat(s.selectedDayOffset).isEqualTo(0)

        // Headers are not guide rows; numbering is the position among playable entries.
        assertThat(s.rows.map { it.channel }).containsExactly(hd, two, itv, c4k).inOrder()
        assertThat(s.rows.map { it.number }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(s.row(13).noGuideData).isTrue()
        assertThat(s.row(10).noGuideData).isFalse()

        assertThat(s.cell(10, 2).timing).isEqualTo(ProgrammeTiming.AIRING)
        assertThat(s.cell(10, 2).restartable).isTrue()
        assertThat(s.cell(10, 3).timing).isEqualTo(ProgrammeTiming.FUTURE)
        assertThat(s.cell(11, 5).restartable).isFalse()
        // The 4K entry has no archive; its HD other version does.
        assertThat(s.cell(14, 6).timing).isEqualTo(ProgrammeTiming.PAST)
        assertThat(s.cell(14, 6).catchup).isTrue()

        // Focus starts on the airing programme of the first row.
        assertThat(vm.focusedCell.value).isEqualTo(EpgFocus(10, 2, now))
    }

    @Test
    fun `pages in time and marks only eligible past programmes with the catch-up icon`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.pageBack()
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.windowStartMillis).isEqualTo(utc("2026-10-29T17:30:00Z"))
        assertThat(s.showNowLine).isFalse()
        assertThat(grid.calls.last()).isEqualTo(utc("2026-10-29T17:30:00Z") to utc("2026-10-29T19:30:00Z"))
        assertThat(s.cell(10, 1).catchup).isTrue()
        assertThat(s.cell(11, 4).catchup).isFalse()

        vm.pageForward()
        vm.pageForward()
        advanceUntilIdle()
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-29T21:30:00Z"))
        assertThat(vm.state.value.row(10).noGuideData).isTrue()
    }

    @Test
    fun `day jump is bounded by the largest archive window and two days ahead`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        // Largest archive: BBC One HD 3 days (the 4K entry's other version has 2).
        assertThat(vm.state.value.days.map { it.offset }).containsExactly(-3, -2, -1, 0, 1, 2).inOrder()
        assertThat(vm.state.value.days.first().date).isEqualTo(LocalDate.of(2026, 10, 26))

        vm.jumpToDay(-5)
        advanceUntilIdle()
        assertThat(vm.state.value.selectedDayOffset).isEqualTo(-3)
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-26T19:30:00Z"))

        repeat(20) { vm.pageBack() }
        advanceUntilIdle()
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-26T00:00:00Z"))
        assertThat(vm.state.value.canPageBack).isFalse()

        vm.jumpToDay(9)
        advanceUntilIdle()
        assertThat(vm.state.value.selectedDayOffset).isEqualTo(2)
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-31T19:30:00Z"))
        repeat(5) { vm.pageForward() }
        advanceUntilIdle()
        assertThat(vm.state.value.windowEndMillis).isEqualTo(utc("2026-11-01T00:00:00Z"))
        assertThat(vm.state.value.canPageForward).isFalse()

        vm.jumpToNow()
        advanceUntilIdle()
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-29T19:30:00Z"))
    }

    @Test
    fun `a group without archive only reaches today and the next two days`() = runTest(main.dispatcher) {
        others.clear()
        val vm = EpgGridViewModel(GroupKey(1, 2), FakeLiveChannelSource(mutableListOf(two, itv)), grid, resolver, clock = { now }, zone = zone)
        advanceUntilIdle()
        assertThat(vm.state.value.days.map { it.offset }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `OK on an eligible past programme starts a catch-up session for that programme`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        vm.pageBack()
        advanceUntilIdle()
        vm.select(10, 1)
        val request = (events.single() as EpgEvent.PlayCatchup).request
        assertThat(request.channel).isEqualTo(hd)
        assertThat(request.programmeStartMillis).isEqualTo(utc("2026-10-29T18:00:00Z"))
        assertThat(request.title).isEqualTo("Six O'Clock News")

        val playback = (CatchupPlaybackController(accounts, amsterdam, FakePreferences(), clock = { now }).start(request) as CatchupStart.Started).playback
        assertThat(playback.currentSource().url).isEqualTo("http://provider.invalid:8080/timeshift/user-secret/pass-secret/60/2026-10-29:19-00/101.ts")
    }

    @Test
    fun `OK on a past programme of a non-archive channel shows details only`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        vm.pageBack()
        advanceUntilIdle()
        vm.select(11, 4)
        assertThat(events).isEmpty()
        val details = vm.state.value.details!!
        assertThat(details.title).isEqualTo("Gardeners' World")
        assertThat(details.catchupAvailable).isFalse()
        vm.playFromDetails()
        assertThat(events).isEmpty()
    }

    @Test
    fun `OK on a future programme shows details only`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        vm.select(10, 3)
        assertThat(events).isEmpty()
        assertThat(vm.state.value.details?.timing).isEqualTo(ProgrammeTiming.FUTURE)
        vm.dismissDetails()
        assertThat(vm.state.value.details).isNull()
    }

    @Test
    fun `OK on the airing programme offers Watch live or Restart`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()

        vm.select(10, 2)
        assertThat(vm.state.value.airingChoice).isEqualTo(AiringChoice(10, 2, "The Celebrity Traitors", canRestart = true))
        vm.chooseRestart()
        val restart = (events.single() as EpgEvent.PlayCatchup).request
        assertThat(restart.programmeStartMillis).isEqualTo(utc("2026-10-29T19:00:00Z"))
        val playback = (CatchupPlaybackController(accounts, amsterdam, FakePreferences(), clock = { now }).start(restart) as CatchupStart.Started).playback
        assertThat(playback.isRestart).isTrue()
        assertThat(playback.currentSource().url).endsWith("/65/2026-10-29:20-00/101.ts")

        vm.select(11, 5)
        assertThat(vm.state.value.airingChoice?.canRestart).isFalse()
        vm.chooseRestart() // not offered: no event
        assertThat(events).hasSize(1)
        vm.select(11, 5)
        vm.chooseWatchLive()
        assertThat(events.last()).isEqualTo(EpgEvent.WatchLive(two))
    }

    @Test
    fun `entry without archive plays catch-up through the other version that has it`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        vm.select(14, 6)
        val request = (events.single() as EpgEvent.PlayCatchup).request
        val playback = (CatchupPlaybackController(accounts, amsterdam, FakePreferences(), clock = { now }).start(request) as CatchupStart.Started).playback
        assertThat(playback.usesOtherVersion).isTrue()
        assertThat(playback.currentSource().url).endsWith("/35/2026-10-29:20-00/301.ts")
    }

    @Test
    fun `OK on a No guide data row watches the channel live`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        vm.selectChannel(13)
        assertThat(events.single()).isEqualTo(EpgEvent.WatchLive(itv))
    }

    @Test
    fun `return opens current time while keeping the channel`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        vm.onFocus(11, 5)
        vm.select(11, 5)
        vm.chooseWatchLive()
        assertThat(events.single()).isEqualTo(EpgEvent.WatchLive(two))

        // Browse into the future, then return after the current 30-minute slot changes.
        vm.pageForward()
        advanceUntilIdle()
        assertThat(vm.state.value.showNowLine).isFalse()
        now += 25 * 60_000
        vm.onReturn()
        advanceUntilIdle()
        assertThat(vm.focusedCell.value?.channelOrdinal).isEqualTo(11)
        assertThat(vm.focusedCell.value?.programmeId).isEqualTo(5)
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-29T20:00:00Z"))
        assertThat(vm.state.value.showNowLine).isTrue()
        assertThat(vm.state.value.nowResetVersion).isEqualTo(1)
    }

    @Test
    fun `reopening or choosing now clears an old programme even in the current window`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onFocus(10, 3)
        vm.jumpToNow()
        advanceUntilIdle()
        assertThat(vm.focusedCell.value).isEqualTo(EpgFocus(10, 2, now))
        vm.pageBack()
        advanceUntilIdle()
        assertThat(vm.state.value.showNowLine).isFalse()
        vm.tick()
        assertThat(vm.state.value.showNowLine).isFalse()
        vm.onReturn()
        advanceUntilIdle()
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-29T19:30:00Z"))
        assertThat(vm.focusedCell.value).isEqualTo(EpgFocus(10, 2, now))
        assertThat(vm.state.value.nowResetVersion).isEqualTo(2)
    }

    @Test
    fun `stale focus during a slow return cannot restore a future programme`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val slowGrid = EpgGridSource { playlist, group, from, to ->
            if (calls++ == 1) gate.await()
            grid.gridForGroup(playlist, group, from, to)
        }
        val vm = EpgGridViewModel(GroupKey(1, 2), channels, slowGrid, resolver, clock = { now }, zone = zone)
        advanceUntilIdle()
        vm.onFocus(10, 3)
        vm.onReturn()
        runCurrent()
        vm.onFocus(10, 3)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.focusedCell.value).isEqualTo(EpgFocus(10, 2, now))
        assertThat(vm.focusedProgramme.value?.title).isEqualTo("The Celebrity Traitors")
    }

    @Test
    fun `the focused cell drives the details and a channel name shows the airing programme`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.focusedProgramme.value?.title).isEqualTo("The Celebrity Traitors")

        vm.onFocus(11, 5)
        val focused = vm.focusedProgramme.value!!
        assertThat(focused.channel).isEqualTo(two)
        assertThat(focused.number).isEqualTo(2)
        assertThat(focused.title).isEqualTo("University Challenge")
        assertThat(focused.timing).isEqualTo(ProgrammeTiming.AIRING)

        vm.onFocus(14, 6)
        assertThat(vm.focusedProgramme.value?.catchup).isTrue()

        vm.onFocus(10, null)
        assertThat(vm.focusedProgramme.value?.title).isEqualTo("The Celebrity Traitors")
        // Focus moves never re-emit the rows.
        val rows = vm.state.value.rows
        vm.onFocus(11, 4)
        assertThat(vm.state.value.rows).isSameInstanceAs(rows)
    }

    @Test
    fun `with row blocks only the rows near the visible ones are loaded`() = runTest(main.dispatcher) {
        val vm = EpgGridViewModel(GroupKey(1, 2), channels, grid, resolver, clock = { now }, zone = zone, rowBlock = 2)
        advanceUntilIdle()
        assertThat(vm.state.value.rows.map { it.loaded }).containsExactly(true, true, false, false).inOrder()
        // Not loaded yet is not "No guide data".
        assertThat(vm.state.value.row(14).noGuideData).isFalse()
        val first = vm.state.value.row(10)

        vm.onVisibleRows(2, 3)
        advanceUntilIdle()
        assertThat(vm.state.value.rows.map { it.loaded }).containsExactly(true, true, true, true).inOrder()
        assertThat(vm.state.value.cell(14, 6).catchup).isTrue()
        assertThat(vm.state.value.row(13).noGuideData).isTrue()
        // Unchanged rows keep their instance.
        assertThat(vm.state.value.row(10)).isSameInstanceAs(first)
    }

    @Test
    fun `scrolling evicts distant guide blocks and reloads them when revisited`() = runTest(main.dispatcher) {
        val manyChannels = FakeLiveChannelSource()
        val manyProgrammes = FakeEpgGridSource()
        repeat(100) { ordinal ->
            manyChannels.add(LiveChannel(1, ordinal, 2, "Channel $ordinal", streamId = ordinal.toLong() + 1))
            manyProgrammes.add(
                ordinal,
                programme(ordinal.toLong() + 1, "id$ordinal", now - 60_000, now + 60_000, "Programme $ordinal"),
            )
        }
        val vm = EpgGridViewModel(
            GroupKey(1, 2), manyChannels, manyProgrammes, OtherVersionsResolver { emptyList() },
            clock = { now }, zone = zone, rowBlock = 10,
        )
        advanceUntilIdle()
        vm.onFocus(0, 1)
        vm.onVisibleRows(80, 89)
        advanceUntilIdle()

        assertThat(vm.state.value.row(0).loaded).isTrue()
        assertThat(vm.state.value.row(80).loaded).isTrue()
        assertThat(vm.state.value.row(40).loaded).isFalse()
        assertThat(vm.state.value.rows.count { it.loaded }).isAtMost(40)
        vm.onFocus(80, 81)
        vm.onVisibleRows(0, 9)
        advanceUntilIdle()
        assertThat(vm.state.value.cell(0, 1).title).isEqualTo("Programme 0")
    }

    @Test
    fun `visible rows changed during the initial load are loaded afterwards`() = runTest(main.dispatcher) {
        val manyChannels = FakeLiveChannelSource()
        repeat(50) { ordinal ->
            manyChannels.add(LiveChannel(1, ordinal, 2, "Channel $ordinal", streamId = ordinal.toLong() + 1))
        }
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val slowGrid = EpgGridSource { _, _, _, _ ->
            if (calls++ == 0) gate.await()
            emptyList()
        }
        val vm = EpgGridViewModel(
            GroupKey(1, 2), manyChannels, slowGrid, OtherVersionsResolver { emptyList() },
            clock = { now }, zone = zone, rowBlock = 10,
        )
        runCurrent()
        vm.onVisibleRows(40, 49)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.row(40).loaded).isTrue()
        assertThat(vm.state.value.row(49).loaded).isTrue()
    }
}

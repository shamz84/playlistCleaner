package app.playlistplayer.ui.mobile.epg

import app.playlistplayer.feature.epg.EpgEvent
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.epg.ProgrammeTiming
import app.playlistplayer.feature.live.utc
import app.playlistplayer.feature.vod.MainDispatcherRule
import app.playlistplayer.ui.common.GroupKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class GuideLayoutTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val f = GuideFixture()

    private fun viewModel() = EpgGridViewModel(
        f.group, f.channels, f.grid, f.coordinator, clock = f.clock, zone = f.zone, windowSlots = GuideGeometry.PHONE_WINDOW_SLOTS,
    )

    private fun TestScope.events(vm: EpgGridViewModel): MutableList<EpgEvent> {
        val list = mutableListOf<EpgEvent>()
        backgroundScope.launch { vm.events.toList(list) }
        return list
    }

    // ---- geometry ----

    private val start = utc("2026-10-29T19:30:00Z")
    private val end = utc("2026-10-29T22:30:00Z")
    private val geometry = GuideGeometry(start, end, slotWidthDp = 120f)

    @Test
    fun `time maps linearly onto the timeline, 120 dp per 30-minute slot`() {
        assertThat(geometry.timelineWidthDp).isEqualTo(720f)
        assertThat(geometry.xOf(start)).isEqualTo(0f)
        assertThat(geometry.xOf(utc("2026-10-29T20:00:00Z"))).isEqualTo(120f)
        assertThat(geometry.xOf(utc("2026-10-29T20:15:00Z"))).isEqualTo(180f)
        assertThat(geometry.xOf(end)).isEqualTo(720f)
    }

    @Test
    fun `times outside the window are clamped to its edges`() {
        assertThat(geometry.xOf(utc("2026-10-29T18:00:00Z"))).isEqualTo(0f)
        assertThat(geometry.xOf(utc("2026-10-30T01:00:00Z"))).isEqualTo(720f)
    }

    @Test
    fun `cells are clipped to the window with a gap and a minimum width`() {
        // 19:00–20:05 starts before the window: drawn from 0 to 20:05 (140 dp) less the 2 dp gap.
        assertThat(geometry.spanOf(utc("2026-10-29T19:00:00Z"), utc("2026-10-29T20:05:00Z"))).isEqualTo(CellSpan(0f, 138f))
        // 21:00–23:00 runs past the window end: clipped at 720 dp.
        assertThat(geometry.spanOf(utc("2026-10-29T21:00:00Z"), utc("2026-10-29T23:00:00Z"))).isEqualTo(CellSpan(360f, 358f))
        // A one-minute programme still gets a tappable sliver.
        assertThat(geometry.spanOf(utc("2026-10-29T20:00:00Z"), utc("2026-10-29T20:01:00Z")).widthDp).isEqualTo(GuideGeometry.MIN_CELL_WIDTH_DP)
    }

    @Test
    fun `now-line is drawn only inside the window`() {
        assertThat(geometry.nowLineX(utc("2026-10-29T19:40:00Z"))).isEqualTo(40f)
        assertThat(geometry.nowLineX(utc("2026-10-29T19:20:00Z"))).isNull()
        assertThat(geometry.nowLineX(end)).isNull()
    }

    @Test
    fun `slot headers sit at each slot start`() {
        val slots = (0 until 6).map { start + it * EpgGridViewModel.SLOT_MILLIS }
        assertThat(geometry.slotXs(slots)).containsExactly(0f, 120f, 240f, 360f, 480f, 600f).inOrder()
    }

    @Test
    fun `phone grid opens at now over six 30-minute slots`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.windowStartMillis).isEqualTo(start)
        assertThat(s.windowEndMillis).isEqualTo(end)
        assertThat(s.slots).hasSize(6)
        assertThat(GuideGeometry.of(s).nowLineX(s.nowMillis)).isEqualTo(40f)
    }

    // ---- slot paging ----

    @Test
    fun `scroll anchor after the window moves`() {
        val window = 3 * 60 * 60_000L
        assertThat(GuidePaging.anchorAfter(null, start, window)).isEqualTo(ScrollAnchor.START)
        assertThat(GuidePaging.anchorAfter(start, start, window)).isEqualTo(ScrollAnchor.KEEP)
        // Later: show the new window's start (continuous with the previous end).
        assertThat(GuidePaging.anchorAfter(start, start + window, window)).isEqualTo(ScrollAnchor.START)
        // One page earlier: show the new window's end (continuous with the previous start).
        assertThat(GuidePaging.anchorAfter(start, start - window, window)).isEqualTo(ScrollAnchor.END)
        // A clamped page back (less than a window) is still a page back.
        assertThat(GuidePaging.anchorAfter(start, start - window / 2, window)).isEqualTo(ScrollAnchor.END)
        // A day jump back shows the same time of day at the start.
        assertThat(GuidePaging.anchorAfter(start, start - 24 * 60 * 60_000L, window)).isEqualTo(ScrollAnchor.START)
    }

    @Test
    fun `paging moves the phone window by six slots within the guide range`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.pageForward()
        advanceUntilIdle()
        assertThat(vm.state.value.windowStartMillis).isEqualTo(end)
        vm.pageBack()
        vm.pageBack()
        advanceUntilIdle()
        assertThat(vm.state.value.windowStartMillis).isEqualTo(utc("2026-10-29T16:30:00Z"))
        assertThat(vm.state.value.canPageBack).isTrue() // the archive reaches 3 days back
    }

    // ---- day chips ----

    @Test
    fun `day chips run from the largest archive window to two days ahead`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val chips = dayChips(vm.state.value.days, vm.state.value.selectedDayOffset)
        assertThat(chips.map { it.offset }).containsExactly(-3, -2, -1, 0, 1, 2).inOrder()
        assertThat(chips.map { it.label }).containsExactly(
            DayLabel.DATE, DayLabel.DATE, DayLabel.YESTERDAY, DayLabel.TODAY, DayLabel.TOMORROW, DayLabel.DATE,
        ).inOrder()
        assertThat(chips.single { it.selected }.offset).isEqualTo(0)
        assertThat(chips.first().date).isEqualTo(LocalDate.of(2026, 10, 26))

        vm.jumpToDay(-1)
        advanceUntilIdle()
        val after = dayChips(vm.state.value.days, vm.state.value.selectedDayOffset)
        assertThat(after.single { it.selected }.label).isEqualTo(DayLabel.YESTERDAY)
        assertThat(vm.state.value.windowStartMillis).isEqualTo(start - 24 * 60 * 60_000L)
    }

    @Test
    fun `without archive the day chips start today`() {
        val days = listOf(0, 1, 2).map { app.playlistplayer.feature.epg.EpgDay(it, LocalDate.of(2026, 10, 29).plusDays(it.toLong())) }
        assertThat(dayChips(days, 0).map { it.label }).containsExactly(DayLabel.TODAY, DayLabel.TOMORROW, DayLabel.DATE).inOrder()
    }

    // ---- group picker ----

    @Test
    fun `selected group is kept while listed, otherwise the first group`() {
        assertThat(resolveGuideGroup(f.sections, null)).isEqualTo(GroupKey(1, 2))
        assertThat(resolveGuideGroup(f.sections, GroupKey(2, 0))).isEqualTo(GroupKey(2, 0))
        // Same group ordinal in another playlist is a different group (R26).
        assertThat(resolveGuideGroup(f.sections, GroupKey(2, 2))).isEqualTo(GroupKey(1, 2))
        assertThat(resolveGuideGroup(emptyList(), GroupKey(1, 2))).isNull()
    }

    @Test
    fun `group label is the playlist name and the group name`() {
        assertThat(guideGroupLabel(f.sections, GroupKey(2, 0))).isEqualTo("Family" to "Kids")
        assertThat(guideGroupLabel(f.sections, GroupKey(1, 2))).isEqualTo("Main" to "🇬🇧 UK | Entertainment")
        assertThat(guideGroupLabel(f.sections, GroupKey(3, 0))).isNull()
    }

    // ---- tap routing (same as OK on TV) ----

    @Test
    fun `tap on an eligible past programme plays it from the archive`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        val row = vm.state.value.rows.single { it.channel == f.bbcOne }
        val past = row.cells.single { it.programmeId == 1L }
        assertThat(past.timing).isEqualTo(ProgrammeTiming.PAST)
        assertThat(past.catchup).isTrue()

        vm.handle(guideTapFor(row, past))
        advanceUntilIdle()
        val play = events.single() as EpgEvent.PlayCatchup
        assertThat(play.request.channel).isEqualTo(f.bbcOne)
        assertThat(play.request.programmeStartMillis).isEqualTo(utc("2026-10-29T19:00:00Z"))
        assertThat(vm.state.value.details).isNull()
    }

    @Test
    fun `tap on the airing programme offers watch live or restart`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        val row = vm.state.value.rows.single { it.channel == f.bbcOne }
        vm.handle(guideTapFor(row, row.cells.single { it.programmeId == 2L }))
        advanceUntilIdle()
        val choice = vm.state.value.airingChoice!!
        assertThat(choice.canRestart).isTrue()
        assertThat(events).isEmpty()

        vm.chooseRestart()
        advanceUntilIdle()
        assertThat((events.single() as EpgEvent.PlayCatchup).request.programmeId).isEqualTo(2L)
    }

    @Test
    fun `tap on a future or non-eligible past programme shows details only`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        val one = vm.state.value.rows.single { it.channel == f.bbcOne }
        vm.handle(guideTapFor(one, one.cells.single { it.programmeId == 3L }))
        advanceUntilIdle()
        assertThat(vm.state.value.details!!.timing).isEqualTo(ProgrammeTiming.FUTURE)
        vm.dismissDetails()

        val two = vm.state.value.rows.single { it.channel == f.bbcTwo }
        val past = two.cells.single()
        assertThat(past.catchup).isFalse()
        vm.handle(guideTapFor(two, past))
        advanceUntilIdle()
        assertThat(vm.state.value.details!!.catchupAvailable).isFalse()
        assertThat(events).isEmpty()
    }

    @Test
    fun `long press shows details even for a catch-up programme`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        val row = vm.state.value.rows.single { it.channel == f.bbcOne }
        val tap = guideTapFor(row, row.cells.single { it.programmeId == 1L }, longPress = true)
        assertThat(tap).isEqualTo(GuideTap.Details(10, 1))
        vm.handle(tap)
        advanceUntilIdle()
        assertThat(vm.state.value.details!!.catchupAvailable).isTrue()
        assertThat(events).isEmpty()
    }

    @Test
    fun `tap on a no guide data row or a channel name watches live`() = runTest(main.dispatcher) {
        val vm = viewModel()
        val events = events(vm)
        advanceUntilIdle()
        val itv = vm.state.value.rows.single { it.channel == f.itv }
        assertThat(itv.noGuideData).isTrue()
        assertThat(guideTapFor(itv, cell = null)).isEqualTo(GuideTap.Channel(13))
        vm.handle(guideTapFor(itv, cell = null))
        advanceUntilIdle()
        assertThat((events.single() as EpgEvent.WatchLive).channel).isEqualTo(f.itv)
    }
}

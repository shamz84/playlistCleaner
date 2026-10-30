package app.playlistplayer.feature.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.player.catchup.CatchupDecision
import app.playlistplayer.core.player.catchup.ProgrammeWindow
import app.playlistplayer.feature.live.CatchupPlaybackController
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.EpgGridSource
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.feature.live.LiveChannelSource
import app.playlistplayer.feature.live.OtherVersionsResolver
import app.playlistplayer.ui.common.GroupKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Guide grid for one group (U9, R7, R8):
 * - rows are the group's playable channels in playlist order; channels without programmes in
 *   the window are "No guide data" rows;
 * - opens at now (window starts at the current 30-minute slot) with a now-line;
 * - pages in time by [windowSlots] slots, and jumps by day from −(largest archive window of the
 *   group's channels and their other versions) to +2 days, bounded to those days;
 * - past programmes inside the archive of the channel or an other version get the catch-up icon;
 * - OK: eligible past → [EpgEvent.PlayCatchup]; airing → [AiringChoice] (Watch live / Restart);
 *   future or non-eligible past → [ProgrammeDetails] only;
 * - remembers the focused programme, so returning from the player restores it.
 *
 * The UI calls [tick] about once a minute to move the now-line.
 */
class EpgGridViewModel(
    private val group: GroupKey,
    private val channels: LiveChannelSource,
    private val grid: EpgGridSource,
    private val otherVersions: OtherVersionsResolver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val windowSlots: Int = DEFAULT_WINDOW_SLOTS,
) : ViewModel() {

    init {
        require(windowSlots > 0) { "windowSlots must be positive" }
    }

    private val mutableState = MutableStateFlow(EpgGridUiState())
    val state: StateFlow<EpgGridUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<EpgEvent>(Channel.BUFFERED)
    val events: Flow<EpgEvent> = eventChannel.receiveAsFlow()

    private val windowMillis = windowSlots * SLOT_MILLIS
    private var playable: List<LiveChannel> = emptyList()
    private var others: Map<Int, List<LiveChannel>> = emptyMap()
    private var maxArchiveDays = 0
    private var programmes: Map<Int, List<ProgrammeEntity>> = emptyMap()
    private var windowStart = 0L
    private var focus: EpgFocus? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val now = clock()
        try {
            playable = channels.groupChannels(group.playlistId, group.groupOrdinal)
                .filter { it.playlistId == group.playlistId && it.isPlayable }
            others = playable.associate { it.ordinal to otherVersions.otherVersions(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        maxArchiveDays = playable.maxOfOrNull { channel ->
            (listOf(channel) + others[channel.ordinal].orEmpty())
                .filter { it.streamId != null }
                .maxOfOrNull { it.archiveDays.coerceAtLeast(0) } ?: 0
        } ?: 0
        if (playable.isEmpty()) {
            mutableState.update { it.copy(loading = false, emptyGroup = true, nowMillis = now) }
            return
        }
        windowStart = clampStart(slotFloor(now))
        load(now)
    }

    // ---- window ----

    /** Moves the window one page later in time (bounded to +2 days). */
    fun pageForward() = moveTo(windowStart + windowMillis)

    /** Moves the window one page earlier in time (bounded to the archive window). */
    fun pageBack() = moveTo(windowStart - windowMillis)

    /** Moves the window by [slots] 30-minute slots (D-pad past the window edge). */
    fun shiftSlots(slots: Int) = moveTo(windowStart + slots * SLOT_MILLIS)

    /** Back to now. */
    fun jumpToNow() = moveTo(slotFloor(clock()))

    /**
     * Jumps to the same time of day on the day [offset] days from today, within
     * `-maxArchiveDays..+2` (out-of-range offsets are clamped).
     */
    fun jumpToDay(offset: Int) {
        val now = clock()
        val clamped = offset.coerceIn(minDayOffset(), MAX_DAYS_AHEAD)
        val nowSlot = slotFloor(now)
        val target = nowSlot.atZone().plusDays(clamped.toLong()).toInstant().toEpochMilli()
        moveTo(target)
    }

    private fun moveTo(start: Long) {
        if (playable.isEmpty()) return
        val clamped = clampStart(start)
        if (clamped == windowStart && !mutableState.value.loading && !mutableState.value.loadFailed) return
        windowStart = clamped
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load(clock()) }
    }

    private suspend fun load(now: Long) {
        val from = windowStart
        val to = windowStart + windowMillis
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        val rows = try {
            grid.gridForGroup(group.playlistId, group.groupOrdinal, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        programmes = rows.groupBy({ it.channelOrdinal }, { it.programme })
        publish(now)
    }

    /** Reloads the current window (e.g. after the guide imported). */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { if (playable.isEmpty()) open() else load(clock()) }
    }

    /** Moves the now-line and re-evaluates past/airing/catch-up flags. */
    fun tick() {
        if (playable.isEmpty() || mutableState.value.loading) return
        publish(clock())
    }

    /** Back from the player: refresh now and keep the window and the remembered focus. */
    fun onReturn() = refresh()

    private fun publish(now: Long) {
        val from = windowStart
        val to = windowStart + windowMillis
        val rows = playable.mapIndexed { index, channel ->
            EpgRow(channel = channel, number = index + 1, cells = cellsFor(channel, from, to, now))
        }
        val dayOffset = dayOffsetOf(from, now)
        mutableState.update {
            it.copy(
                loading = false,
                loadFailed = false,
                emptyGroup = false,
                rows = rows,
                nowMillis = now,
                windowStartMillis = from,
                windowEndMillis = to,
                slots = (0 until windowSlots).map { i -> from + i * SLOT_MILLIS },
                days = (minDayOffset()..MAX_DAYS_AHEAD).map { offset -> EpgDay(offset, today(now).plusDays(offset.toLong())) },
                selectedDayOffset = dayOffset,
                canPageBack = from > minStart(now),
                canPageForward = to < maxEnd(now),
                focus = resolveFocus(rows, now),
            )
        }
    }

    private fun cellsFor(channel: LiveChannel, from: Long, to: Long, now: Long): List<EpgCell> {
        val list = programmes[channel.ordinal].orEmpty()
            .filter { it.stopMillis > from && it.startMillis < to && it.stopMillis > it.startMillis }
            .sortedWith(compareBy({ it.startMillis }, { it.id }))
        val cells = ArrayList<EpgCell>(list.size)
        var lastStop = Long.MIN_VALUE
        for (p in list) {
            // Provider feeds contain overlapping regional variants: keep the first of each slot.
            if (p.startMillis < lastStop) continue
            lastStop = p.stopMillis
            cells += cellOf(channel, p, now)
        }
        return cells
    }

    private fun cellOf(channel: LiveChannel, p: ProgrammeEntity, now: Long): EpgCell {
        val timing = timingOf(p, now)
        val decision = if (timing == ProgrammeTiming.FUTURE) CatchupDecision.NotEligible else decide(channel, p, now)
        return EpgCell(
            programmeId = p.id,
            title = p.title,
            startMillis = p.startMillis,
            stopMillis = p.stopMillis,
            timing = timing,
            catchup = timing == ProgrammeTiming.PAST && decision is CatchupDecision.PlayFromStart,
            restartable = timing == ProgrammeTiming.AIRING && decision is CatchupDecision.RestartLive,
        )
    }

    private fun decide(channel: LiveChannel, p: ProgrammeEntity, now: Long): CatchupDecision =
        CatchupPlaybackController.decide(channel, others[channel.ordinal].orEmpty(), windowOf(p), now)

    // ---- focus ----

    /** Records the focused cell ([programmeId] null on a "No guide data" row). */
    fun onFocus(channelOrdinal: Int, programmeId: Long?) {
        val cell = programmeId?.let { id -> mutableState.value.rows.firstOrNull { it.channel.ordinal == channelOrdinal }?.cells?.firstOrNull { it.programmeId == id } }
        val time = cell?.let { maxOf(it.startMillis, windowStart) } ?: focus?.timeMillis ?: clock()
        focus = EpgFocus(channelOrdinal, programmeId, time)
        mutableState.update { it.copy(focus = focus) }
    }

    private fun resolveFocus(rows: List<EpgRow>, now: Long): EpgFocus? {
        if (rows.isEmpty()) return null
        val remembered = focus
        val row = remembered?.let { f -> rows.firstOrNull { it.channel.ordinal == f.channelOrdinal } } ?: rows.first()
        // The remembered programme, when it is still in the window.
        remembered?.programmeId?.let { id ->
            if (row.channel.ordinal == remembered.channelOrdinal && row.cells.any { it.programmeId == id }) return remembered
        }
        // Otherwise the cell covering the remembered time (or now on first open), else the first cell.
        val time = (remembered?.timeMillis ?: now).coerceIn(windowStart, windowStart + windowMillis - 1)
        val cell = row.cells.firstOrNull { time >= it.startMillis && time < it.stopMillis } ?: row.cells.firstOrNull()
        val resolved = EpgFocus(row.channel.ordinal, cell?.programmeId, remembered?.timeMillis ?: time)
        focus = resolved
        return resolved
    }

    // ---- OK and dialogs ----

    /** OK on a programme cell. */
    fun select(channelOrdinal: Int, programmeId: Long) {
        onFocus(channelOrdinal, programmeId)
        val (channel, programme) = find(channelOrdinal, programmeId) ?: return
        val now = clock()
        val timing = timingOf(programme, now)
        val decision = if (timing == ProgrammeTiming.FUTURE) CatchupDecision.NotEligible else decide(channel, programme, now)
        when {
            timing == ProgrammeTiming.PAST && decision is CatchupDecision.PlayFromStart ->
                eventChannel.trySend(EpgEvent.PlayCatchup(requestFor(channel, programme)))
            timing == ProgrammeTiming.AIRING -> mutableState.update {
                it.copy(airingChoice = AiringChoice(channelOrdinal, programmeId, programme.title, decision is CatchupDecision.RestartLive))
            }
            else -> showDetails(channelOrdinal, programmeId)
        }
    }

    /** OK on a "No guide data" row: watch the channel live. */
    fun selectChannel(channelOrdinal: Int) {
        onFocus(channelOrdinal, null)
        playable.firstOrNull { it.ordinal == channelOrdinal }?.let { eventChannel.trySend(EpgEvent.WatchLive(it)) }
    }

    fun chooseWatchLive() {
        val choice = mutableState.value.airingChoice ?: return
        mutableState.update { it.copy(airingChoice = null) }
        playable.firstOrNull { it.ordinal == choice.channelOrdinal }?.let { eventChannel.trySend(EpgEvent.WatchLive(it)) }
    }

    fun chooseRestart() {
        val choice = mutableState.value.airingChoice ?: return
        mutableState.update { it.copy(airingChoice = null) }
        if (!choice.canRestart) return
        val (channel, programme) = find(choice.channelOrdinal, choice.programmeId) ?: return
        eventChannel.trySend(EpgEvent.PlayCatchup(requestFor(channel, programme)))
    }

    fun dismissChoice() = mutableState.update { it.copy(airingChoice = null) }

    /** Programme details (info key, or OK on a programme that cannot be played). */
    fun showDetails(channelOrdinal: Int, programmeId: Long) {
        val (channel, programme) = find(channelOrdinal, programmeId) ?: return
        val now = clock()
        val timing = timingOf(programme, now)
        val decision = if (timing == ProgrammeTiming.FUTURE) CatchupDecision.NotEligible else decide(channel, programme, now)
        mutableState.update {
            it.copy(
                details = ProgrammeDetails(
                    channelOrdinal = channelOrdinal,
                    programmeId = programmeId,
                    channelName = channel.name,
                    title = programme.title,
                    subTitle = programme.subTitle,
                    description = programme.description,
                    episodeNum = programme.episodeNum,
                    categories = programme.categoryList,
                    startMillis = programme.startMillis,
                    stopMillis = programme.stopMillis,
                    timing = timing,
                    catchupAvailable = timing == ProgrammeTiming.PAST && decision is CatchupDecision.PlayFromStart,
                ),
            )
        }
    }

    /** "Play from start" in the details dialog (only when [ProgrammeDetails.catchupAvailable]). */
    fun playFromDetails() {
        val details = mutableState.value.details ?: return
        mutableState.update { it.copy(details = null) }
        if (details.catchupAvailable) select(details.channelOrdinal, details.programmeId)
    }

    fun dismissDetails() = mutableState.update { it.copy(details = null) }

    private fun find(channelOrdinal: Int, programmeId: Long): Pair<LiveChannel, ProgrammeEntity>? {
        val channel = playable.firstOrNull { it.ordinal == channelOrdinal } ?: return null
        val programme = programmes[channelOrdinal]?.firstOrNull { it.id == programmeId } ?: return null
        return channel to programme
    }

    private fun requestFor(channel: LiveChannel, p: ProgrammeEntity) = CatchupRequest(
        channel = channel,
        otherVersions = others[channel.ordinal].orEmpty(),
        programmeStartMillis = p.startMillis,
        programmeStopMillis = p.stopMillis,
        title = p.title,
        programmeId = p.id,
    )

    // ---- time maths ----

    private fun timingOf(p: ProgrammeEntity, now: Long): ProgrammeTiming = when {
        p.stopMillis <= now -> ProgrammeTiming.PAST
        p.startMillis <= now -> ProgrammeTiming.AIRING
        else -> ProgrammeTiming.FUTURE
    }

    private fun windowOf(p: ProgrammeEntity) = ProgrammeWindow(Instant.ofEpochMilli(p.startMillis), Instant.ofEpochMilli(p.stopMillis))

    private fun Long.atZone(): ZonedDateTime = Instant.ofEpochMilli(this).atZone(zone)

    private fun today(now: Long): LocalDate = now.atZone().toLocalDate()

    private fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Start of the 30-minute slot containing [millis], in [zone]. */
    private fun slotFloor(millis: Long): Long {
        val t = millis.atZone().truncatedTo(ChronoUnit.MINUTES)
        val floored = t.withMinute(if (t.minute >= 30) 30 else 0)
        return floored.toInstant().toEpochMilli()
    }

    private fun minDayOffset(): Int = -maxArchiveDays

    private fun minStart(now: Long): Long = startOfDay(today(now).plusDays(minDayOffset().toLong()))

    private fun maxEnd(now: Long): Long = startOfDay(today(now).plusDays(MAX_DAYS_AHEAD + 1L))

    private fun clampStart(start: Long): Long {
        val now = clock()
        val min = minStart(now)
        val max = maxEnd(now) - windowMillis
        return start.coerceIn(min, maxOf(min, max))
    }

    private fun dayOffsetOf(millis: Long, now: Long): Int =
        ChronoUnit.DAYS.between(today(now), millis.atZone().toLocalDate()).toInt()

    companion object {
        const val SLOT_MILLIS: Long = 30 * 60 * 1000L
        const val DEFAULT_WINDOW_SLOTS = 4
        const val MAX_DAYS_AHEAD = 2
    }
}

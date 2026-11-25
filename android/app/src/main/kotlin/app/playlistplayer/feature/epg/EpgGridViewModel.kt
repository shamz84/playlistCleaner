package app.playlistplayer.feature.epg

import android.util.Log
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
 * - OK: eligible past → [EpgEvent.PlayCatchup]; eligible airing → [AiringChoice]; other airing → live;
 *   future or non-eligible past → [ProgrammeDetails] only;
 * - returning from catch-up preserves the selected time; other returns reset to now.
 *
 * The UI calls [tick] about once a minute to move the now-line.
 *
 * Performance (TV): the focus lives in its own [focusedCell] / [focusedProgramme] flows, so moving focus
 * never re-emits the rows; unchanged rows keep their instance across publishes; with [rowBlock] > 0
 * only the blocks of [rowBlock] channels around the visible rows ([onVisibleRows]) and the focus
 * are loaded, the others are placeholders until scrolled near. [rowBlock] 0 loads the whole group.
 */
class EpgGridViewModel(
    private val group: GroupKey,
    private val channels: LiveChannelSource,
    private val grid: EpgGridSource,
    private val otherVersions: OtherVersionsResolver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val windowSlots: Int = DEFAULT_WINDOW_SLOTS,
    private val rowBlock: Int = 0,
    initialChannelOrdinal: Int? = null,
    private val versionsDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    init {
        require(windowSlots > 0) { "windowSlots must be positive" }
    }

    private val mutableState = MutableStateFlow(EpgGridUiState())
    val state: StateFlow<EpgGridUiState> = mutableState.asStateFlow()

    private val focusFlow = MutableStateFlow<EpgFocus?>(null)

    /** The remembered focus (separate from [state]: focus moves must not re-emit the rows). */
    val focusedCell: StateFlow<EpgFocus?> = focusFlow.asStateFlow()

    private val focusedFlow = MutableStateFlow<FocusedProgramme?>(null)

    /** Details of the focused cell (TV details area). */
    val focusedProgramme: StateFlow<FocusedProgramme?> = focusedFlow.asStateFlow()

    private val eventChannel = Channel<EpgEvent>(Channel.BUFFERED)
    val events: Flow<EpgEvent> = eventChannel.receiveAsFlow()

    private val windowMillis = windowSlots * SLOT_MILLIS
    private var playable: List<LiveChannel> = emptyList()
    private val others = HashMap<Int, List<LiveChannel>>()
    private var maxArchiveDays = 0
    private var programmes: Map<Int, List<ProgrammeEntity>> = emptyMap()
    private var windowStart = 0L
    private var focus: EpgFocus? = initialChannelOrdinal?.let { EpgFocus(it, null, clock()) }
    private var loadJob: Job? = null
    private var rowsJob: Job? = null
    private var metadataJob: Job? = null
    private val loadedBlocks = HashSet<Int>()
    private var visibleFirst = 0
    private var visibleLast = 0
    private var preserveTimeOnReturn = false

    init {
        loadJob = viewModelScope.launch { open() }
        viewModelScope.launch {
            grid.observeChanges(group.playlistId).collect { refresh() }
        }
    }

    private suspend fun open() {
        val now = clock()
        try {
            playable = channels.groupChannels(group.playlistId, group.groupOrdinal)
                .filter { it.playlistId == group.playlistId && it.isPlayable }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        maxArchiveDays = playable.filter { it.streamId != null }.maxOfOrNull { it.archiveDays.coerceAtLeast(0) } ?: 0
        if (playable.isEmpty()) {
            mutableState.update { it.copy(loading = false, emptyGroup = true, nowMillis = now) }
            return
        }
        val initialIndex = focus?.let { f -> playable.indexOfFirst { it.ordinal == f.channelOrdinal } } ?: -1
        if (initialIndex >= 0) {
            visibleFirst = initialIndex
            visibleLast = initialIndex
        } else {
            focus = null
        }
        windowStart = clampStart(slotFloor(now))
        load(now)
        if (rowBlock > 0) resolveRemainingVersions()
    }

    // ---- window ----

    /** Moves the window one page later in time (bounded to +2 days). */
    fun pageForward() = moveTo(windowStart + windowMillis)

    /** Moves the window one page earlier in time (bounded to the archive window). */
    fun pageBack() = moveTo(windowStart - windowMillis)

    /** Moves the window by [slots] 30-minute slots (D-pad past the window edge). */
    fun shiftSlots(slots: Int) = moveTo(windowStart + slots * SLOT_MILLIS)

    /** Back to now. */
    fun jumpToNow() {
        preserveTimeOnReturn = false
        onReturn()
    }

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

    private suspend fun load(now: Long, resetToNow: Boolean = false) {
        val from = windowStart
        val to = windowStart + windowMillis
        rowsJob?.cancel()
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        loadedBlocks.clear()
        programmes = emptyMap()
        try {
            val needed = neededBlocks()
            val focusIndex = focus?.let { f -> playable.indexOfFirst { it.ordinal == f.channelOrdinal } } ?: -1
            val first = if (rowBlock > 0 && focusIndex >= 0) setOf(blockOf(focusIndex)) else needed.take(1).toSet()
            fetch(first, from, to)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        if (resetToNow) focus = focus?.let { EpgFocus(it.channelOrdinal, null, now) }
        publish(now, resetToNow)
        if (rowBlock > 0) onVisibleRows(visibleFirst, visibleLast)
    }

    /** Loads the programmes of [blocks] for the window `[from, to)` and marks them loaded. */
    private suspend fun fetch(blocks: Collection<Int>, from: Long, to: Long) {
        val wanted = blocks.toSet()
        val keepOrdinals = if (rowBlock > 0) wanted.flatMap { block ->
            playable.subList(block * rowBlock, minOf(playable.size, (block + 1) * rowBlock)).map { it.ordinal }
        }.toSet() else programmes.keys
        val loaded = HashMap(programmes.filterKeys { it in keepOrdinals })
        val completed = loadedBlocks.intersect(wanted).toMutableSet()
        for (block in blocks) {
            if (block in completed) continue
            val blockChannels = if (rowBlock <= 0) playable else
                playable.subList(block * rowBlock, minOf(playable.size, (block + 1) * rowBlock))
            resolveVersions(blockChannels)
            val rows = if (rowBlock <= 0) {
                grid.gridForGroup(group.playlistId, group.groupOrdinal, from, to)
            } else {
                val ordinals = blockChannels.mapTo(HashSet()) { it.ordinal }
                grid.gridForChannels(group.playlistId, group.groupOrdinal, blockChannels.minOf { it.ordinal }, blockChannels.maxOf { it.ordinal }, from, to)
                    .filter { it.channelOrdinal in ordinals }
            }

            rows.groupBy({ it.channelOrdinal }, { it.programme }).forEach { (ordinal, list) -> loaded[ordinal] = list }
            completed += block
        }
        loadedBlocks.clear()
        loadedBlocks.addAll(completed)
        programmes = loaded
    }

    private suspend fun resolveVersions(channels: List<LiveChannel>) {
        val unresolved = channels.filterNot { it.ordinal in others }
        if (unresolved.isEmpty()) return
        val resolved = if (rowBlock <= 0) {
            unresolved.associate { it.ordinal to otherVersions.otherVersions(it) }
        } else {
            withContext(versionsDispatcher) {
                unresolved.associate { it.ordinal to otherVersions.otherVersions(it) }
            }
        }
        others.putAll(resolved)
        val extraDays = resolved.values.asSequence().flatten()
            .filter { it.streamId != null }
            .maxOfOrNull { it.archiveDays.coerceAtLeast(0) } ?: 0
        maxArchiveDays = maxOf(maxArchiveDays, extraDays)
    }

    private fun resolveRemainingVersions() {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            try {
                for (block in playable.indices step rowBlock) {
                    val before = maxArchiveDays
                    resolveVersions(playable.subList(block, minOf(playable.size, block + rowBlock)))
                    if (before != maxArchiveDays && !mutableState.value.loading) publish(clock())
                    yield()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EpgGrid", "Failed to resolve remaining channel versions (${e.javaClass.simpleName})")
            }
        }
    }

    private fun blockOf(index: Int): Int = if (rowBlock <= 0) 0 else index / rowBlock

    /** Blocks covering the visible rows and the focused row, half a block of margin each side. */
    private fun neededBlocks(): Set<Int> {
        if (rowBlock <= 0 || playable.isEmpty()) return setOf(0)
        val focusIndex = focus?.let { f -> playable.indexOfFirst { it.ordinal == f.channelOrdinal } }?.takeIf { it >= 0 }
        val first = (visibleFirst - rowBlock / 2).coerceIn(0, playable.size - 1)
        val last = (visibleLast + rowBlock / 2).coerceIn(first, playable.size - 1)
        return (blockOf(first)..blockOf(last)).toSet() + listOfNotNull(focusIndex?.let(::blockOf))
    }

    /**
     * The guide shows rows [firstIndex]..[lastIndex] (row indexes): loads the blocks around them
     * that are not loaded yet (no-op when [rowBlock] is 0).
     */
    fun onVisibleRows(firstIndex: Int, lastIndex: Int) {
        visibleFirst = firstIndex.coerceAtLeast(0)
        visibleLast = lastIndex.coerceAtLeast(visibleFirst)
        if (rowBlock <= 0 || playable.isEmpty() || mutableState.value.loading || rowsJob?.isActive == true) return
        if (neededBlocks() == loadedBlocks) return
        rowsJob = viewModelScope.launch {
            try {
                while (true) {
                    val needed = neededBlocks()
                    if (needed == loadedBlocks) break
                    fetch(needed, windowStart, windowStart + windowMillis)
                    publish(clock())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EpgGrid", "Failed to load guide rows (${e.javaClass.simpleName})")
                return@launch // the rows stay placeholders; the next scroll retries
            }
        }
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

    /** On reopening, show current programmes on the playing channel if it belongs to this group. */
    fun onReturn(playingChannelOrdinal: Int? = null) {
        if (preserveTimeOnReturn) {
            preserveTimeOnReturn = false
            tick()
            return
        }
        if (playable.isEmpty()) {
            refresh()
            return
        }
        val now = clock()
        val channelOrdinal = playingChannelOrdinal?.takeIf { ordinal -> playable.any { it.ordinal == ordinal } }
            ?: focus?.channelOrdinal
        focus = channelOrdinal?.let { EpgFocus(it, null, now) }
        windowStart = clampStart(slotFloor(now))
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load(now, resetToNow = true) }
    }

    private fun publish(now: Long, resetToNow: Boolean = false) {
        val from = windowStart
        val to = windowStart + windowMillis
        val previous = mutableState.value.rows
        val rows = playable.mapIndexed { index, channel ->
            val loaded = rowBlock <= 0 || blockOf(index) in loadedBlocks
            val row = EpgRow(channel = channel, number = index + 1, cells = if (loaded) cellsFor(channel, from, to, now) else emptyList(), loaded = loaded)
            // Same instance for unchanged rows: the guide skips recomposing them.
            previous.getOrNull(index)?.takeIf { it == row } ?: row
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
                nowResetVersion = if (resetToNow) it.nowResetVersion + 1 else it.nowResetVersion,
                slots = (0 until windowSlots).map { i -> from + i * SLOT_MILLIS },
                days = (minDayOffset()..MAX_DAYS_AHEAD).map { offset -> EpgDay(offset, today(now).plusDays(offset.toLong())) },
                selectedDayOffset = dayOffset,
                canPageBack = from > minStart(now),
                canPageForward = to < maxEnd(now),
            )
        }
        focusFlow.value = resolveFocus(rows, now)
        updateFocused()
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
        focusFlow.value = focus
        updateFocused()
    }

    /** Details of the focused cell; on a channel name (or "No guide data") the programme airing now. */
    private fun updateFocused() {
        val f = focus
        val index = f?.let { playable.indexOfFirst { c -> c.ordinal == it.channelOrdinal } } ?: -1
        if (f == null || index < 0) {
            focusedFlow.value = null
            return
        }
        val channel = playable[index]
        val now = clock()
        val list = programmes[channel.ordinal].orEmpty()
        val programme = f.programmeId?.let { id -> list.firstOrNull { it.id == id } }
            ?: list.firstOrNull { now >= it.startMillis && now < it.stopMillis }
        val cell = programme?.let { cellOf(channel, it, now) }
        focusedFlow.value = FocusedProgramme(
            channel = channel,
            number = index + 1,
            programmeId = programme?.id,
            title = programme?.title,
            subTitle = programme?.subTitle,
            description = programme?.description,
            startMillis = programme?.startMillis,
            stopMillis = programme?.stopMillis,
            timing = cell?.timing,
            catchup = cell?.catchup == true,
        )
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
            timing == ProgrammeTiming.PAST && decision is CatchupDecision.PlayFromStart -> {
                preserveTimeOnReturn = true
                eventChannel.trySend(EpgEvent.PlayCatchup(requestFor(channel, programme)))
            }
            timing == ProgrammeTiming.AIRING && decision is CatchupDecision.RestartLive -> mutableState.update {
                it.copy(airingChoice = AiringChoice(channelOrdinal, programmeId, programme.title, canRestart = true))
            }
            timing == ProgrammeTiming.AIRING -> {
                preserveTimeOnReturn = false
                eventChannel.trySend(EpgEvent.WatchLive(channel))
            }
            else -> showDetails(channelOrdinal, programmeId)
        }
    }

    /** OK on a "No guide data" row: watch the channel live. */
    fun selectChannel(channelOrdinal: Int) {
        onFocus(channelOrdinal, null)
        preserveTimeOnReturn = false
        playable.firstOrNull { it.ordinal == channelOrdinal }?.let { eventChannel.trySend(EpgEvent.WatchLive(it)) }
    }

    fun chooseWatchLive() {
        val choice = mutableState.value.airingChoice ?: return
        mutableState.update { it.copy(airingChoice = null) }
        preserveTimeOnReturn = false
        playable.firstOrNull { it.ordinal == choice.channelOrdinal }?.let { eventChannel.trySend(EpgEvent.WatchLive(it)) }
    }

    fun chooseRestart() {
        val choice = mutableState.value.airingChoice ?: return
        mutableState.update { it.copy(airingChoice = null) }
        if (!choice.canRestart) return
        val (channel, programme) = find(choice.channelOrdinal, choice.programmeId) ?: return
        preserveTimeOnReturn = true
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

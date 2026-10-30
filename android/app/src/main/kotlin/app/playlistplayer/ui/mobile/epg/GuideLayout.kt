package app.playlistplayer.ui.mobile.epg

import app.playlistplayer.feature.epg.EpgCell
import app.playlistplayer.feature.epg.EpgDay
import app.playlistplayer.feature.epg.EpgGridUiState
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.epg.EpgRow
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import java.time.LocalDate

// Pure mapping for the phone Guide (no Compose), so geometry, paging, day chips and tap routing
// are JVM-tested. All lengths are in dp.

/**
 * Horizontal geometry of the guide window: time maps linearly onto `0..timelineWidthDp`, one
 * 30-minute slot being [slotWidthDp] wide. Cells and the now-line are clipped to the window.
 */
data class GuideGeometry(
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val slotMillis: Long = EpgGridViewModel.SLOT_MILLIS,
    val slotWidthDp: Float = SLOT_WIDTH_DP,
) {
    init {
        require(slotMillis > 0) { "slotMillis must be positive" }
    }

    private val windowMillis: Long get() = (windowEndMillis - windowStartMillis).coerceAtLeast(0)

    val timelineWidthDp: Float get() = windowMillis.toFloat() / slotMillis * slotWidthDp

    /** x of [millis] on the timeline, clamped to the window. */
    fun xOf(millis: Long): Float {
        val clamped = millis.coerceIn(windowStartMillis, maxOf(windowStartMillis, windowEndMillis))
        return (clamped - windowStartMillis).toFloat() / slotMillis * slotWidthDp
    }

    /** Where a programme cell is drawn: clipped to the window, a small gap after it, never narrower than [MIN_CELL_WIDTH_DP]. */
    fun spanOf(startMillis: Long, stopMillis: Long): CellSpan {
        val x = xOf(startMillis)
        val end = xOf(stopMillis)
        return CellSpan(x, (end - x - CELL_GAP_DP).coerceAtLeast(MIN_CELL_WIDTH_DP))
    }

    fun spanOf(cell: EpgCell): CellSpan = spanOf(cell.startMillis, cell.stopMillis)

    /** x of the now-line, or null when now is outside the window. */
    fun nowLineX(nowMillis: Long): Float? =
        if (nowMillis in windowStartMillis until windowEndMillis) xOf(nowMillis) else null

    /** x of each slot's header label. */
    fun slotXs(slots: List<Long>): List<Float> = slots.map(::xOf)

    companion object {
        /** The phone window: 3 hours (six 30-minute slots), scrolled horizontally by touch. */
        const val PHONE_WINDOW_SLOTS = 6
        const val SLOT_WIDTH_DP = 120f
        const val MIN_CELL_WIDTH_DP = 8f
        const val CELL_GAP_DP = 2f

        fun of(state: EpgGridUiState, slotWidthDp: Float = SLOT_WIDTH_DP) =
            GuideGeometry(state.windowStartMillis, state.windowEndMillis, state.slotMillis, slotWidthDp)
    }
}

data class CellSpan(val xDp: Float, val widthDp: Float)

/** Where the horizontal scroll goes after the window moved. */
enum class ScrollAnchor {
    /** Same window (tick, return from the player): keep the user's scroll position. */
    KEEP,

    /** Paged later, jumped to now or to a day: show the window's start (the current slot when it is today). */
    START,

    /** Paged one page earlier: show the window's end, continuous with what was on screen. */
    END,
}

object GuidePaging {
    /**
     * Anchor after the window moved from [previousStart] (null on first display) to [newStart].
     * Moving back by at most one window ([windowMillis]) is a page back and keeps continuity by
     * showing the end; every other move shows the start.
     */
    fun anchorAfter(previousStart: Long?, newStart: Long, windowMillis: Long): ScrollAnchor = when {
        previousStart == null -> ScrollAnchor.START
        newStart == previousStart -> ScrollAnchor.KEEP
        newStart < previousStart && previousStart - newStart <= windowMillis -> ScrollAnchor.END
        else -> ScrollAnchor.START
    }
}

/** Day chip label, localized by the screen. */
enum class DayLabel { TODAY, YESTERDAY, TOMORROW, DATE }

data class GuideDayChip(val offset: Int, val date: LocalDate, val label: DayLabel, val selected: Boolean)

/** The VM's day range (−archive days … +2) as chips, in order, with the window's day selected. */
fun dayChips(days: List<EpgDay>, selectedOffset: Int): List<GuideDayChip> = days.map { day ->
    GuideDayChip(
        offset = day.offset,
        date = day.date,
        label = when (day.offset) {
            0 -> DayLabel.TODAY
            -1 -> DayLabel.YESTERDAY
            1 -> DayLabel.TOMORROW
            else -> DayLabel.DATE
        },
        selected = day.offset == selectedOffset,
    )
}

/**
 * The group the Guide shows: [selected] while it is still listed (it can vanish after a sync or
 * when the PIN hides it), otherwise the first group of the first non-empty playlist section.
 */
fun resolveGuideGroup(sections: List<PlaylistSection>, selected: GroupKey?): GroupKey? {
    val keys = sections.flatMap { s -> s.groups.map { it.key } }
    return selected?.takeIf { it in keys } ?: keys.firstOrNull()
}

/** Playlist (section) name and group name of [key], for the picker button. */
fun guideGroupLabel(sections: List<PlaylistSection>, key: GroupKey?): Pair<String, String>? {
    key ?: return null
    val section = sections.firstOrNull { it.playlistId == key.playlistId } ?: return null
    val group = section.groups.firstOrNull { it.key == key } ?: return null
    return section.name to group.name
}

/** What a touch on the grid asks for. */
sealed interface GuideTap {
    /** Tap on a programme: the VM routes it exactly as OK on TV (catch-up / airing choice / details). */
    data class Programme(val channelOrdinal: Int, val programmeId: Long) : GuideTap

    /** Long press on a programme: details, as long-press OK / Info on TV. */
    data class Details(val channelOrdinal: Int, val programmeId: Long) : GuideTap

    /** Tap on a "No guide data" row or a channel name: watch the channel live. */
    data class Channel(val channelOrdinal: Int) : GuideTap
}

/** Maps a touch on [row] ([cell] null for the "No guide data" row or the channel column). */
fun guideTapFor(row: EpgRow, cell: EpgCell?, longPress: Boolean = false): GuideTap = when {
    cell == null -> GuideTap.Channel(row.channel.ordinal)
    longPress -> GuideTap.Details(row.channel.ordinal, cell.programmeId)
    else -> GuideTap.Programme(row.channel.ordinal, cell.programmeId)
}

/** Applies [tap] to the shared guide ViewModel. */
fun EpgGridViewModel.handle(tap: GuideTap) = when (tap) {
    is GuideTap.Programme -> select(tap.channelOrdinal, tap.programmeId)
    is GuideTap.Details -> showDetails(tap.channelOrdinal, tap.programmeId)
    is GuideTap.Channel -> selectChannel(tap.channelOrdinal)
}

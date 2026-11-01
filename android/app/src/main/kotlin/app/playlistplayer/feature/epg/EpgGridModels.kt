package app.playlistplayer.feature.epg

import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import java.time.LocalDate

/** Where a programme sits relative to now. */
enum class ProgrammeTiming { PAST, AIRING, FUTURE }

/**
 * One programme cell of the guide grid.
 *
 * @property catchup a past programme inside the archive of the channel or one of its other
 *   versions: shows the catch-up icon and OK plays it from the start (R7).
 * @property restartable the airing programme can be restarted from the archive (R8).
 */
data class EpgCell(
    val programmeId: Long,
    val title: String?,
    val startMillis: Long,
    val stopMillis: Long,
    val timing: ProgrammeTiming,
    val catchup: Boolean,
    val restartable: Boolean,
)

/**
 * One channel row; an empty [cells] list of a [loaded] row is a "No guide data" row. Rows far from
 * the visible ones are not loaded yet (guide rows load lazily on TV) and have no cells.
 */
data class EpgRow(
    val channel: LiveChannel,
    /** 1-based position among the group's playable entries. */
    val number: Int,
    val cells: List<EpgCell>,
    val loaded: Boolean = true,
) {
    val noGuideData: Boolean get() = loaded && cells.isEmpty()
}

/** A day-jump option; [offset] 0 is today, negative days are in the archive. */
data class EpgDay(val offset: Int, val date: LocalDate)

/**
 * The remembered focus. [programmeId] is null for a "No guide data" row; [timeMillis] is the
 * focused time, used to pick the nearest cell when the remembered programme is no longer shown.
 */
data class EpgFocus(val channelOrdinal: Int, val programmeId: Long?, val timeMillis: Long)

/**
 * The focused guide cell for the details area above the guide (TV Live TV): the channel and, when
 * known, the programme (on a channel name or "No guide data" cell, the programme airing now).
 */
data class FocusedProgramme(
    val channel: LiveChannel,
    val number: Int,
    val programmeId: Long?,
    val title: String?,
    val subTitle: String?,
    val description: String?,
    val startMillis: Long?,
    val stopMillis: Long?,
    val timing: ProgrammeTiming?,
    val catchup: Boolean,
)

/** OK on the airing programme: Watch live, or Restart when [canRestart]. */
data class AiringChoice(val channelOrdinal: Int, val programmeId: Long, val title: String?, val canRestart: Boolean)

/** Programme detail dialog. Has no play action unless [catchupAvailable]. */
data class ProgrammeDetails(
    val channelOrdinal: Int,
    val programmeId: Long,
    val channelName: String,
    val title: String?,
    val subTitle: String?,
    val description: String?,
    val episodeNum: String?,
    val categories: List<String>,
    val startMillis: Long,
    val stopMillis: Long,
    val timing: ProgrammeTiming,
    val catchupAvailable: Boolean,
)

data class EpgGridUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    /** The group has no playable channels. */
    val emptyGroup: Boolean = false,
    val rows: List<EpgRow> = emptyList(),
    val nowMillis: Long = 0,
    val windowStartMillis: Long = 0,
    val windowEndMillis: Long = 0,
    val slotMillis: Long = EpgGridViewModel.SLOT_MILLIS,
    /** Start of every 30-minute slot in the window (column headers). */
    val slots: List<Long> = emptyList(),
    val days: List<EpgDay> = emptyList(),
    val selectedDayOffset: Int = 0,
    val canPageBack: Boolean = false,
    val canPageForward: Boolean = false,
    val airingChoice: AiringChoice? = null,
    val details: ProgrammeDetails? = null,
) {
    /** The now-line is drawn when now falls inside the window. */
    val showNowLine: Boolean get() = nowMillis in windowStartMillis until windowEndMillis
}

sealed interface EpgEvent {
    /** Play [request] from the archive (past programme, or a restart of the airing one). */
    data class PlayCatchup(val request: CatchupRequest) : EpgEvent

    /** Open the live player on [channel]. */
    data class WatchLive(val channel: LiveChannel) : EpgEvent
}

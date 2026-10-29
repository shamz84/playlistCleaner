package app.playlistplayer.core.player.catchup

import app.playlistplayer.core.player.url.StreamAccount
import java.time.ZoneId

/** What the player must do for a seek. */
sealed interface CatchupSeekAction {
    /** The programme-relative position after the seek. */
    val positionMs: Long

    /** The source supports byte ranges: seek the current media item to [positionMs]. */
    data class NativeSeek(override val positionMs: Long) : CatchupSeekAction

    /**
     * The source is not seekable: load [url] (starting at the requested minute) and, if possible,
     * skip [residualMs] into it. Never log [url] unredacted.
     */
    data class Rebuild(val url: String, val residualMs: Long, override val positionMs: Long) : CatchupSeekAction {
        override fun toString(): String = "Rebuild(url=<redacted>, residualMs=$residualMs, positionMs=$positionMs)"
    }
}

/**
 * Pure state for one catch-up playback. The UI sees the programme's own timeline, `0..durationMs`,
 * whether or not the source supports native seeking.
 *
 * When [seekable] is false, each seek rebuilds the URL with an adjusted start; [timelinePositionMs]
 * maps the player's position in the current URL back onto the programme timeline.
 *
 * Not thread-safe: confine to the player thread.
 */
class CatchupSession(
    private val account: StreamAccount,
    val streamId: String,
    val programme: ProgrammeWindow,
    private val serverZone: ZoneId,
    private val userOffsetHours: Int = 0,
    private val strategy: CatchupUrlStrategy = CatchupUrlStrategy.forStyle(CatchupUrlStyle.DEFAULT),
    val seekable: Boolean,
) {
    val durationMinutes: Int = CatchupTime.durationMinutes(programme.start, programme.stop)

    /** Length of the programme timeline (whole minutes, matching the URL duration). */
    val durationMs: Long = durationMinutes * MINUTE_MS

    /** Timeline offset where the currently loaded URL starts (always 0 when [seekable]). */
    var segmentStartMs: Long = 0
        private set

    /** The URL currently to be played. Contains credentials: never log it unredacted. */
    var currentUrl: String = buildUrl(offsetMinutes = 0)
        private set

    /** Programme-relative position for a player position within [currentUrl]. */
    fun timelinePositionMs(playerPositionMs: Long): Long =
        (segmentStartMs + playerPositionMs.coerceAtLeast(0)).coerceIn(0, durationMs)

    fun seekTo(positionMs: Long): CatchupSeekAction {
        val target = positionMs.coerceIn(0, durationMs)
        if (seekable) return CatchupSeekAction.NativeSeek(target)

        // Keep at least one minute of stream so a seek to the very end still builds a valid URL.
        val maxOffsetMinutes = (durationMinutes - 1).toLong()
        val seek = CatchupTime.seekStart(programme.start, target)
        val offsetMinutes = seek.offsetMinutes.coerceAtMost(maxOffsetMinutes)
        val residualMs = target - offsetMinutes * MINUTE_MS

        segmentStartMs = offsetMinutes * MINUTE_MS
        currentUrl = buildUrl(offsetMinutes)
        return CatchupSeekAction.Rebuild(currentUrl, residualMs, target)
    }

    private fun buildUrl(offsetMinutes: Long): String {
        val start = CatchupTime.seekStart(programme.start, offsetMinutes * MINUTE_MS).start
        val wallTime = CatchupTime.startWallTime(start, serverZone, userOffsetHours)
        return strategy.build(account, streamId, wallTime, durationMinutes - offsetMinutes.toInt())
    }

    override fun toString(): String =
        "CatchupSession(streamId=$streamId, programme=$programme, seekable=$seekable, segmentStartMs=$segmentStartMs)"

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}

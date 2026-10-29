package app.playlistplayer.core.player.catchup

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Time maths for catch-up URLs.
 *
 * The provider reads the timeshift `start` as wall time in `server_info.timezone`, so the UTC
 * programme start is converted to that zone (DST-aware) and then shifted by the user's offset.
 * Durations always come from UTC instants, never wall clock.
 */
object CatchupTime {
    const val MIN_USER_OFFSET_HOURS = -12
    const val MAX_USER_OFFSET_HOURS = 12

    private const val MINUTE_MS = 60_000L

    /** `YYYY-MM-DD:HH-MM`, the start format both timeshift URL styles expect. */
    val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd:HH-mm", Locale.ROOT)

    /** The programme start as server wall time plus [userOffsetHours], truncated to the minute. */
    fun startWallTime(programmeStart: Instant, serverZone: ZoneId, userOffsetHours: Int = 0): LocalDateTime {
        require(userOffsetHours in MIN_USER_OFFSET_HOURS..MAX_USER_OFFSET_HOURS) {
            "User offset must be within $MIN_USER_OFFSET_HOURS..$MAX_USER_OFFSET_HOURS hours"
        }
        return LocalDateTime.ofInstant(programmeStart, serverZone)
            .plusHours(userOffsetHours.toLong())
            .truncatedTo(ChronoUnit.MINUTES)
    }

    fun formatStart(wallTime: LocalDateTime): String = START_FORMAT.format(wallTime)

    /** `ceil((stop - start) / 60 s)`; at least 1. */
    fun durationMinutes(start: Instant, stop: Instant): Int {
        require(stop.isAfter(start)) { "Programme stop must be after start" }
        val millis = Duration.between(start, stop).toMillis()
        // Math.ceilDiv is not available on Android API 28.
        return ((millis + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }

    /**
     * For servers without range support: the URL start for a seek to [positionMs] into the
     * programme is the programme start plus the position floored to whole minutes; [SeekStart.residualMs]
     * is the remaining offset within that minute.
     */
    fun seekStart(programmeStart: Instant, positionMs: Long): SeekStart {
        require(positionMs >= 0) { "Position must not be negative" }
        val wholeMinutes = positionMs / MINUTE_MS
        return SeekStart(
            start = programmeStart.plus(wholeMinutes, ChronoUnit.MINUTES),
            offsetMinutes = wholeMinutes,
            residualMs = positionMs - wholeMinutes * MINUTE_MS,
        )
    }

    data class SeekStart(
        /** The new URL start instant (programme start + [offsetMinutes]). */
        val start: Instant,
        /** Whole minutes skipped from the programme start. */
        val offsetMinutes: Long,
        /** Milliseconds into the rebuilt stream that correspond to the requested position (0..59 999). */
        val residualMs: Long,
    )
}

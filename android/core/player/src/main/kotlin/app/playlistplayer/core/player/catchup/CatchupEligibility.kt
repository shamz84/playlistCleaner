package app.playlistplayer.core.player.catchup

import java.time.Duration
import java.time.Instant

/** A programme's UTC start and stop. */
data class ProgrammeWindow(val start: Instant, val stop: Instant) {
    init {
        require(stop.isAfter(start)) { "Programme stop must be after start" }
    }
}

/** A playable stream that may carry an archive; [archiveDays] 0 (or less) means no archive. */
data class CatchupCandidate(val streamId: String, val archiveDays: Int)

sealed interface CatchupDecision {
    data object NotEligible : CatchupDecision

    /** The programme has ended and is inside [candidate]'s archive window. */
    data class PlayFromStart(val candidate: CatchupCandidate, val usesOtherVersion: Boolean) : CatchupDecision

    /** The programme is airing; it can be restarted from its start via [candidate]'s archive. */
    data class RestartLive(val candidate: CatchupCandidate, val usesOtherVersion: Boolean) : CatchupDecision
}

/**
 * Decides whether a programme can be played from the archive.
 *
 * A source qualifies when it has archive days and `now - programme.start <= archiveDays`.
 * The selected entry is preferred; otherwise the first qualifying other version is used, so
 * [otherVersions] should be passed in preference order. Future programmes are never eligible.
 */
object CatchupEligibility {

    fun evaluate(
        now: Instant,
        programme: ProgrammeWindow,
        entry: CatchupCandidate,
        otherVersions: List<CatchupCandidate> = emptyList(),
    ): CatchupDecision {
        if (now.isBefore(programme.start)) return CatchupDecision.NotEligible
        val age = Duration.between(programme.start, now)

        val fromEntry = entry.takeIf { it.covers(age) }
        val source = fromEntry ?: otherVersions.firstOrNull { it.covers(age) } ?: return CatchupDecision.NotEligible
        val usesOther = fromEntry == null

        val airing = now.isBefore(programme.stop)
        return if (airing) {
            CatchupDecision.RestartLive(source, usesOther)
        } else {
            CatchupDecision.PlayFromStart(source, usesOther)
        }
    }

    private fun CatchupCandidate.covers(age: Duration): Boolean =
        archiveDays > 0 && age <= Duration.ofDays(archiveDays.toLong())
}

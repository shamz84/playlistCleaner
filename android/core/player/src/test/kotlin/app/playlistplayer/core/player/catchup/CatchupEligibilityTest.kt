package app.playlistplayer.core.player.catchup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CatchupEligibilityTest {
    private val now = Instant.parse("2026-10-29T22:00:00Z")
    private val threeDay = CatchupCandidate("100", archiveDays = 3)
    private val noArchive = CatchupCandidate("200", archiveDays = 0)
    private val sevenDay = CatchupCandidate("300", archiveDays = 7)

    private fun endedAgo(ago: Duration, length: Duration = Duration.ofHours(1)): ProgrammeWindow {
        val stop = now.minus(ago)
        return ProgrammeWindow(stop.minus(length), stop)
    }

    @Test
    fun endedJustOutsideArchiveIsNotEligible() {
        val programme = endedAgo(Duration.ofDays(3).plusMinutes(1))
        assertThat(CatchupEligibility.evaluate(now, programme, threeDay)).isEqualTo(CatchupDecision.NotEligible)
    }

    @Test
    fun endedInsideArchivePlaysFromStart() {
        val programme = endedAgo(Duration.ofDays(1))
        assertThat(CatchupEligibility.evaluate(now, programme, threeDay))
            .isEqualTo(CatchupDecision.PlayFromStart(threeDay, usesOtherVersion = false))
    }

    @Test
    fun startExactlyAtArchiveLimitIsEligible() {
        val programme = ProgrammeWindow(now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(3)).plusSeconds(3600))
        assertThat(CatchupEligibility.evaluate(now, programme, threeDay))
            .isEqualTo(CatchupDecision.PlayFromStart(threeDay, usesOtherVersion = false))
    }

    @Test
    fun airingProgrammeIsRestartOnly() {
        val programme = ProgrammeWindow(now.minusSeconds(1800), now.plusSeconds(1800))
        assertThat(CatchupEligibility.evaluate(now, programme, threeDay))
            .isEqualTo(CatchupDecision.RestartLive(threeDay, usesOtherVersion = false))
    }

    @Test
    fun entryWithoutArchiveUsesOtherVersionWithArchive() {
        val programme = endedAgo(Duration.ofDays(5))
        assertThat(CatchupEligibility.evaluate(now, programme, noArchive, listOf(threeDay, sevenDay)))
            .isEqualTo(CatchupDecision.PlayFromStart(sevenDay, usesOtherVersion = true))
    }

    @Test
    fun entryWithArchiveIsPreferredOverOtherVersions() {
        val programme = endedAgo(Duration.ofDays(1))
        assertThat(CatchupEligibility.evaluate(now, programme, threeDay, listOf(sevenDay)))
            .isEqualTo(CatchupDecision.PlayFromStart(threeDay, usesOtherVersion = false))
    }

    @Test
    fun noArchiveAnywhereIsNotEligible() {
        val programme = endedAgo(Duration.ofHours(2))
        assertThat(CatchupEligibility.evaluate(now, programme, noArchive, listOf(CatchupCandidate("201", 0))))
            .isEqualTo(CatchupDecision.NotEligible)
        val airing = ProgrammeWindow(now.minusSeconds(60), now.plusSeconds(60))
        assertThat(CatchupEligibility.evaluate(now, airing, noArchive)).isEqualTo(CatchupDecision.NotEligible)
    }

    @Test
    fun futureProgrammeIsNotEligible() {
        val programme = ProgrammeWindow(now.plusSeconds(60), now.plusSeconds(3660))
        assertThat(CatchupEligibility.evaluate(now, programme, threeDay)).isEqualTo(CatchupDecision.NotEligible)
    }
}

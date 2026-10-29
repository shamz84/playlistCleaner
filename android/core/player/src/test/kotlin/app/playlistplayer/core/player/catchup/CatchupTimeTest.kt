package app.playlistplayer.core.player.catchup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CatchupTimeTest {
    private val london = ZoneId.of("Europe/London")
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun start(instant: String, zone: ZoneId, offset: Int = 0) =
        CatchupTime.formatStart(CatchupTime.startWallTime(Instant.parse(instant), zone, offset))

    @Test
    fun londonAfterDstChangeIsUtcWallTime() {
        assertThat(start("2026-10-29T20:00:00Z", london)).isEqualTo("2026-10-29:20-00")
        assertThat(CatchupTime.durationMinutes(Instant.parse("2026-10-29T20:00:00Z"), Instant.parse("2026-10-29T21:00:00Z")))
            .isEqualTo(60)
    }

    @Test
    fun londonDuringBstIsOneHourAhead() {
        assertThat(start("2026-10-20T20:00:00Z", london)).isEqualTo("2026-10-20:21-00")
    }

    @Test
    fun amsterdamInWinterIsOneHourAhead() {
        assertThat(start("2026-10-29T20:00:00Z", amsterdam)).isEqualTo("2026-10-29:21-00")
    }

    @Test
    fun dstCrossingDurationComesFromInstants() {
        val s = Instant.parse("2026-10-25T00:30:00Z")
        val e = Instant.parse("2026-10-25T02:30:00Z")
        assertThat(CatchupTime.durationMinutes(s, e)).isEqualTo(120)
        // 00:30 UTC is 01:30 BST, before the 01:00 UTC switch back to GMT.
        assertThat(start("2026-10-25T00:30:00Z", london)).isEqualTo("2026-10-25:01-30")
    }

    @Test
    fun durationRoundsUpToWholeMinutes() {
        val s = Instant.parse("2026-10-29T20:00:00Z")
        assertThat(CatchupTime.durationMinutes(s, s.plusSeconds(59 * 60 + 1))).isEqualTo(60)
        assertThat(CatchupTime.durationMinutes(s, s.plusSeconds(1))).isEqualTo(1)
        assertThrows(IllegalArgumentException::class.java) { CatchupTime.durationMinutes(s, s) }
    }

    @Test
    fun userOffsetShiftsStart() {
        assertThat(start("2026-10-29T20:00:00Z", london, offset = -1)).isEqualTo("2026-10-29:19-00")
        assertThat(start("2026-10-29T20:00:00Z", london, offset = 2)).isEqualTo("2026-10-29:22-00")
    }

    @Test
    fun userOffsetOutOfRangeIsRejected() {
        val s = Instant.parse("2026-10-29T20:00:00Z")
        assertThrows(IllegalArgumentException::class.java) { CatchupTime.startWallTime(s, london, 13) }
        assertThrows(IllegalArgumentException::class.java) { CatchupTime.startWallTime(s, london, -13) }
    }

    @Test
    fun seekStartFloorsToMinuteAndReportsResidual() {
        val s = Instant.parse("2026-10-29T20:00:00Z")
        val exact = CatchupTime.seekStart(s, 35 * 60_000L)
        assertThat(exact.start).isEqualTo(Instant.parse("2026-10-29T20:35:00Z"))
        assertThat(exact.offsetMinutes).isEqualTo(35)
        assertThat(exact.residualMs).isEqualTo(0)

        val mid = CatchupTime.seekStart(s, 35 * 60_000L + 30_000L)
        assertThat(mid.start).isEqualTo(Instant.parse("2026-10-29T20:35:00Z"))
        assertThat(mid.residualMs).isEqualTo(30_000)
    }
}

package app.playlistplayer.core.player.catchup

import app.playlistplayer.core.player.url.StreamAccount
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CatchupSessionTest {
    private val account = StreamAccount("http", "example.invalid", null, "user", "pass")
    private val programme = ProgrammeWindow(Instant.parse("2026-10-29T20:00:00Z"), Instant.parse("2026-10-29T21:00:00Z"))
    private val london = ZoneId.of("Europe/London")

    private fun session(seekable: Boolean, strategy: CatchupUrlStrategy = PathTimeshiftStrategy, offset: Int = 0) =
        CatchupSession(account, "123", programme, london, offset, strategy, seekable)

    @Test
    fun exposesProgrammeTimelineAndInitialUrl() {
        val s = session(seekable = true)
        assertThat(s.durationMs).isEqualTo(60 * 60_000L)
        assertThat(s.currentUrl).isEqualTo("http://example.invalid/timeshift/user/pass/60/2026-10-29:20-00/123.ts")
        assertThat(s.timelinePositionMs(5_000)).isEqualTo(5_000)
    }

    @Test
    fun seekableSourceUsesNativeSeek() {
        val s = session(seekable = true)
        assertThat(s.seekTo(35 * 60_000L)).isEqualTo(CatchupSeekAction.NativeSeek(35 * 60_000L))
        assertThat(s.currentUrl).endsWith("/60/2026-10-29:20-00/123.ts")
    }

    @Test
    fun nonSeekableSeekToWholeMinuteRebuildsUrl() {
        val s = session(seekable = false)
        val action = s.seekTo(35 * 60_000L)
        assertThat(action).isEqualTo(
            CatchupSeekAction.Rebuild(
                url = "http://example.invalid/timeshift/user/pass/25/2026-10-29:20-35/123.ts",
                residualMs = 0,
                positionMs = 35 * 60_000L,
            ),
        )
        assertThat(s.timelinePositionMs(0)).isEqualTo(35 * 60_000L)
        assertThat(s.timelinePositionMs(10_000)).isEqualTo(35 * 60_000L + 10_000)
    }

    @Test
    fun nonSeekableSeekMidMinuteReportsResidual() {
        val s = session(seekable = false)
        val action = s.seekTo(35 * 60_000L + 30_000L) as CatchupSeekAction.Rebuild
        assertThat(action.url).isEqualTo("http://example.invalid/timeshift/user/pass/25/2026-10-29:20-35/123.ts")
        assertThat(action.residualMs).isEqualTo(30_000)
        assertThat(action.positionMs).isEqualTo(35 * 60_000L + 30_000L)
    }

    @Test
    fun nonSeekableRebuildHonoursOffsetAndStrategy() {
        val s = session(seekable = false, strategy = PhpTimeshiftStrategy, offset = -1)
        val action = s.seekTo(10 * 60_000L) as CatchupSeekAction.Rebuild
        assertThat(action.url).isEqualTo(
            "http://example.invalid/streaming/timeshift.php?username=user&password=pass&stream=123" +
                "&start=2026-10-29:19-10&duration=50",
        )
    }

    @Test
    fun seeksAreClampedToTimeline() {
        val s = session(seekable = false)
        val end = s.seekTo(99 * 60_000L) as CatchupSeekAction.Rebuild
        assertThat(end.positionMs).isEqualTo(60 * 60_000L)
        assertThat(end.url).endsWith("/1/2026-10-29:20-59/123.ts")
        assertThat(end.residualMs).isEqualTo(60_000)

        val back = s.seekTo(-5) as CatchupSeekAction.Rebuild
        assertThat(back.positionMs).isEqualTo(0)
        assertThat(back.url).endsWith("/60/2026-10-29:20-00/123.ts")
        assertThat(s.timelinePositionMs(0)).isEqualTo(0)
    }

    @Test
    fun rebuildToStringRedactsUrl() {
        val action = session(seekable = false).seekTo(60_000L)
        assertThat(action.toString()).doesNotContain("pass")
    }
}

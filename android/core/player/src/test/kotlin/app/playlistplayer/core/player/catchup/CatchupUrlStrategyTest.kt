package app.playlistplayer.core.player.catchup

import app.playlistplayer.core.player.url.StreamAccount
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime

class CatchupUrlStrategyTest {
    private val account = StreamAccount("http", "example.invalid", null, "user", "pass")
    private val start = LocalDateTime.of(2026, 10, 29, 20, 0)

    @Test
    fun pathStrategy() {
        assertThat(PathTimeshiftStrategy.build(account, "123", start, 65))
            .isEqualTo("http://example.invalid/timeshift/user/pass/65/2026-10-29:20-00/123.ts")
    }

    @Test
    fun phpStrategy() {
        assertThat(PhpTimeshiftStrategy.build(account, "123", start, 65)).isEqualTo(
            "http://example.invalid/streaming/timeshift.php?username=user&password=pass&stream=123" +
                "&start=2026-10-29:20-00&duration=65",
        )
    }

    @Test
    fun specialCharactersInCredentialsAreEncoded() {
        val tricky = StreamAccount("http", "example.invalid", 8080, "us er/1", "p@ss&x=1#")
        assertThat(PathTimeshiftStrategy.build(tricky, "123", start, 60))
            .isEqualTo("http://example.invalid:8080/timeshift/us%20er%2F1/p%40ss%26x%3D1%23/60/2026-10-29:20-00/123.ts")
        assertThat(PhpTimeshiftStrategy.build(tricky, "123", start, 60)).isEqualTo(
            "http://example.invalid:8080/streaming/timeshift.php?username=us%20er%2F1&password=p%40ss%26x%3D1%23" +
                "&stream=123&start=2026-10-29:20-00&duration=60",
        )
    }

    @Test
    fun pathIsDefaultAndStylesSelectStrategies() {
        assertThat(CatchupUrlStyle.DEFAULT).isEqualTo(CatchupUrlStyle.PATH)
        assertThat(CatchupUrlStrategy.forStyle(CatchupUrlStyle.PATH)).isSameInstanceAs(PathTimeshiftStrategy)
        assertThat(CatchupUrlStrategy.forStyle(CatchupUrlStyle.PHP)).isSameInstanceAs(PhpTimeshiftStrategy)
    }

    @Test
    fun rejectsInvalidDurationAndId() {
        assertThrows(IllegalArgumentException::class.java) { PathTimeshiftStrategy.build(account, "123", start, 0) }
        assertThrows(IllegalArgumentException::class.java) { PhpTimeshiftStrategy.build(account, "", start, 60) }
    }
}

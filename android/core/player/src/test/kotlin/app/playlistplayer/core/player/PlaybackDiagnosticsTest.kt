package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackDiagnosticsTest {
    private val source = PlayableSource(
        id = "catchup:private-id",
        url = "https://example.invalid/timeshift/user/password",
        label = "private channel",
        isLive = false,
        accountKey = "private-account",
    )
    private val snapshot = PlaybackDiagnosticSnapshot(12_000, 3_000, true, false, 4)

    @Test
    fun recordsBufferingDurationAndWatchdogReasonWithoutSourceDetails() {
        var now = 1_000L
        val lines = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics({ now }, lines::add)
        diagnostics.transition(PlaybackState.Preparing(source, 1, false), snapshot)
        diagnostics.buffering(true, snapshot)
        now += 6_000
        diagnostics.transition(PlaybackState.Stalled(source), snapshot)
        diagnostics.transition(PlaybackState.Retrying(source, PlaybackError.STALL, 500), snapshot)
        diagnostics.buffering(false, snapshot)

        assertThat(lines).hasSize(5)
        assertThat(lines[2]).contains("event=watchdog_stall")
        assertThat(lines[2]).contains("bufferingMs=6000")
        assertThat(lines[3]).contains("reason=STALL")
        assertThat(lines[4]).contains("durationMs=6000")
        assertThat(lines.joinToString()).contains("kind=catchup")
        assertThat(lines.joinToString()).contains("seekable=true")
        assertThat(lines.joinToString()).contains("droppedFrames=4")
        listOf("private-id", "private channel", "private-account", "example.invalid", "password")
            .forEach { assertThat(lines.joinToString()).doesNotContain(it) }
    }

    @Test
    fun samplesAreRateLimitedAndNewAttemptResetsBuffering() {
        var now = 0L
        val lines = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics({ now }, lines::add)
        diagnostics.transition(PlaybackState.Preparing(source, 1, false), snapshot)
        diagnostics.buffering(true, snapshot)
        now = 29_999
        diagnostics.sample(snapshot)
        assertThat(lines).hasSize(2)
        now = 30_000
        diagnostics.sample(snapshot)
        assertThat(lines.last()).contains("event=sample")
        diagnostics.transition(PlaybackState.Preparing(source, 2, true), snapshot)
        diagnostics.buffering(false, snapshot)
        assertThat(lines).hasSize(4)
    }

    @Test
    fun reportsHttpStatusAndUnknownSeekability() {
        val lines = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics({ 0 }, lines::add)
        diagnostics.transition(
            PlaybackState.Retrying(source, PlaybackError.fromHttpStatus(503), 1_000),
            snapshot.copy(seekable = null),
        )
        assertThat(lines.single()).contains("reason=NETWORK http=503")
        assertThat(lines.single()).contains("seekable=unknown")
    }
}

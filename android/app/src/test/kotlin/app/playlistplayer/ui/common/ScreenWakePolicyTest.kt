package app.playlistplayer.ui.common

import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.core.player.PlaybackError
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.feature.live.PlaybackStatus
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class ScreenWakePolicyTest {

    private val source = PlayableSource(id = "live:1", url = "http://example.invalid/live/1.ts", label = "Channel 1", isLive = true)
    private val other = source.copy(id = "live:2", label = "Channel 1 HD")

    private val active = listOf(
        PlaybackState.Preparing(source, attempt = 1, reconnecting = false),
        PlaybackState.Preparing(source, attempt = 2, reconnecting = true),
        PlaybackState.Playing(source),
        PlaybackState.Playing(source, buffering = true),
        PlaybackState.Stalled(source),
        PlaybackState.Retrying(source, PlaybackError.STALL, delayMs = 1_000),
        PlaybackState.Retrying(source, PlaybackError.STALL, delayMs = 0, waitingForNetwork = true),
        PlaybackState.Failover(source, other, count = 1, reason = PlaybackError.STALL),
    )

    private val inactive = listOf(
        PlaybackState.Idle,
        PlaybackState.Ended(source),
        PlaybackState.ConnectionLimit(source, activeConnections = 1, maxConnections = 1),
        PlaybackState.AccountExpired(source),
        PlaybackState.Failed(source, PlaybackError.STALL, triedVersions = 2),
    )

    @Test
    fun `running or recovering playback keeps the screen on`() {
        active.forEach { assertWithMessage(it.toString()).that(ScreenWakePolicy.isActive(it)).isTrue() }
    }

    @Test
    fun `idle, ended and terminal states release the screen`() {
        inactive.forEach { assertWithMessage(it.toString()).that(ScreenWakePolicy.isActive(it)).isFalse() }
    }

    @Test
    fun `a user pause releases the screen whatever the state`() {
        (active + inactive).forEach { assertThat(ScreenWakePolicy.isActive(it, paused = true)).isFalse() }
    }

    @Test
    fun `overlay status maps the same way`() {
        active.forEach { assertWithMessage(it.toString()).that(ScreenWakePolicy.isActive(PlaybackStatus.from(it))).isTrue() }
        inactive.forEach { assertWithMessage(it.toString()).that(ScreenWakePolicy.isActive(PlaybackStatus.from(it))).isFalse() }
    }

    @Test
    fun `overlay status honours starting, paused, ended and screen-level errors`() {
        val idle = PlaybackStatus.from(PlaybackState.Idle)
        val playing = PlaybackStatus.from(PlaybackState.Playing(source))
        assertThat(ScreenWakePolicy.isActive(idle, starting = true)).isTrue()
        assertThat(ScreenWakePolicy.isActive(playing, paused = true)).isFalse()
        assertThat(ScreenWakePolicy.isActive(playing, ended = true)).isFalse()
        assertThat(ScreenWakePolicy.isActive(playing, blocked = true)).isFalse()
        assertThat(ScreenWakePolicy.isActive(idle, starting = true, blocked = true)).isFalse()
        val failed = PlaybackStatus.from(PlaybackState.Failed(source, PlaybackError.STALL, triedVersions = 1))
        assertThat(ScreenWakePolicy.isActive(failed, starting = true)).isFalse()
    }
}

package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerUiStateTest {

    private val a = live("a", label = "BBC One FHD")
    private val net = PlaybackError(PlaybackError.Kind.NETWORK)

    @Test
    fun firstPrepare_isBuffering_reprepareIsReconnecting() {
        assertThat(PlayerUiState.from(PlaybackState.Preparing(a, 1, reconnecting = false)).indicator)
            .isEqualTo(PlayerUiState.Indicator.BUFFERING)
        assertThat(PlayerUiState.from(PlaybackState.Preparing(a, 2, reconnecting = true)).indicator)
            .isEqualTo(PlayerUiState.Indicator.RECONNECTING)
    }

    @Test
    fun playing_bufferingAndNotice() {
        val clean = PlayerUiState.from(PlaybackState.Playing(a))
        assertThat(clean.indicator).isNull()
        assertThat(clean.isPlaying).isTrue()
        assertThat(PlayerUiState.from(PlaybackState.Playing(a, buffering = true)).indicator)
            .isEqualTo(PlayerUiState.Indicator.BUFFERING)
        assertThat(PlayerUiState.from(PlaybackState.Playing(a, notice = "BBC One HD")).notice)
            .isEqualTo("Switched to BBC One HD")
    }

    @Test
    fun retryingStalledAndFailover_showReconnecting() {
        for (state in listOf(
            PlaybackState.Retrying(a, net, 500),
            PlaybackState.Stalled(a),
            PlaybackState.Failover(a, live("b"), 1, net),
        )) {
            val ui = PlayerUiState.from(state)
            assertThat(ui.indicator).isEqualTo(PlayerUiState.Indicator.RECONNECTING)
            assertThat(ui.error).isNull()
        }
    }

    @Test
    fun terminalStates_showPersistentErrorWithRetry() {
        val limit = PlayerUiState.from(PlaybackState.ConnectionLimit(a, 1, 1)).error!!
        assertThat(limit.type).isEqualTo(PlayerUiState.ErrorType.CONNECTION_LIMIT)
        assertThat(limit.message).contains("1 connection(s)")
        assertThat(limit.message).contains("(1 active)")
        assertThat(limit.canRetry).isTrue()

        val expired = PlayerUiState.from(PlaybackState.AccountExpired(a)).error!!
        assertThat(expired.type).isEqualTo(PlayerUiState.ErrorType.ACCOUNT_EXPIRED)
        assertThat(expired.canRetry).isTrue()

        val failed = PlayerUiState.from(PlaybackState.Failed(a, net, triedVersions = 3)).error!!
        assertThat(failed.type).isEqualTo(PlayerUiState.ErrorType.FAILED)
        assertThat(failed.message).contains("2 other version(s)")
    }

    @Test
    fun sourceToString_neverContainsTheUrl() {
        assertThat(a.toString()).doesNotContain("/live/")
        assertThat(PlaybackState.Playing(a).toString()).doesNotContain("example.invalid")
    }
}

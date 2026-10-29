package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackPolicyTest {

    @Test
    fun reconnectDelay_doublesAndCaps() {
        val policy = PlaybackPolicy()
        assertThat((0..6).map(policy::reconnectDelayMs))
            .containsExactly(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L).inOrder()
        assertThat(policy.reconnectDelayMs(1_000)).isEqualTo(8_000L)
    }

    @Test
    fun zeroInitialBackoff_staysZero() {
        val policy = PlaybackPolicy(reconnectBackoffInitialMs = 0, reconnectBackoffMaxMs = 0)
        assertThat(policy.reconnectDelayMs(5)).isEqualTo(0L)
    }
}

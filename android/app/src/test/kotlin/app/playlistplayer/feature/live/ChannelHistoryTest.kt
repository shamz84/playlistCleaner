package app.playlistplayer.feature.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelHistoryTest {
    @Test
    fun `history keeps five distinct channels and their logos in recency order`() {
        val history = ChannelHistory()
        (1..6).forEach { history.record(ChannelRef(1, it), "Channel $it", "logo-$it") }
        assertThat(history.state.value.recent.map { it.ref.ordinal }).containsExactly(6, 5, 4, 3, 2).inOrder()
        history.record(ChannelRef(1, 3), "Renamed", "new-logo")
        assertThat(history.state.value.recent.map { it.ref.ordinal }).containsExactly(3, 6, 5, 4, 2).inOrder()
        assertThat(history.state.value.recent.first()).isEqualTo(RecentChannel(ChannelRef(1, 3), "Renamed", "new-logo"))
        assertThat(history.lastChannel).isEqualTo(ChannelRef(1, 6))
        history.clearPrevious()
        assertThat(history.lastChannel).isNull()
        assertThat(history.state.value.current).isEqualTo(ChannelRef(1, 3))
        assertThat(history.state.value.recent.map { it.ref.ordinal }).containsExactly(3)
    }
}

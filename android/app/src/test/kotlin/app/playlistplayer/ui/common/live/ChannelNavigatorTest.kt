package app.playlistplayer.ui.common.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelNavigatorTest {

    private fun row(ordinal: Int, header: Boolean = false) =
        ChannelRow(playlistId = 1, ordinal = ordinal, number = null, name = "C$ordinal", isHeader = header)

    // index: 0 header, 1, 2, 3 header, 4
    private val rows = numberChannels(listOf(row(0, header = true), row(1), row(2), row(3, header = true), row(4)))
    private val navigator = ChannelNavigator.forRows(rows)

    @Test
    fun `numbering skips headers`() {
        assertThat(rows.map { it.number }).containsExactly(null, 1, 2, null, 3).inOrder()
        assertThat(indexOfNumber(rows, 3)).isEqualTo(4)
        assertThat(indexOfNumber(rows, 4)).isNull()
    }

    @Test
    fun `channel up moves to the next playable entry, skipping headers`() {
        assertThat(navigator.channelUp(1)).isEqualTo(2)
        assertThat(navigator.channelUp(2)).isEqualTo(4)
    }

    @Test
    fun `channel down moves to the previous playable entry, skipping headers`() {
        assertThat(navigator.channelDown(4)).isEqualTo(2)
        assertThat(navigator.channelDown(2)).isEqualTo(1)
    }

    @Test
    fun `stops at both ends of the group (no wrap-around)`() {
        assertThat(navigator.channelUp(4)).isNull()
        assertThat(navigator.channelDown(1)).isNull()
    }

    @Test
    fun `from nothing focused, up goes to the first and down to the last playable entry`() {
        assertThat(navigator.channelUp(-1)).isEqualTo(1)
        assertThat(navigator.channelDown(rows.size)).isEqualTo(4)
    }

    @Test
    fun `empty and header-only groups have nowhere to go`() {
        assertThat(ChannelNavigator.forRows(emptyList()).channelUp(-1)).isNull()
        val headers = ChannelNavigator.forRows(listOf(row(0, header = true)))
        assertThat(headers.channelUp(-1)).isNull()
        assertThat(headers.channelDown(1)).isNull()
    }
}

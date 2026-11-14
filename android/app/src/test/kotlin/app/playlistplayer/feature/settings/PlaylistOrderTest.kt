package app.playlistplayer.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistOrderTest {
    @Test
    fun listedPlaylistsFollowTheSavedOrderAndUnlistedKeepTheirOrderAtTheEnd() {
        assertThat(orderedPlaylistIds(listOf(1, 2, 3, 4), listOf(3, 1))).containsExactly(3L, 1L, 2L, 4L).inOrder()
    }

    @Test
    fun anEmptySavedOrderChangesNothing() {
        assertThat(orderedPlaylistIds(listOf(5, 6, 7), emptyList())).containsExactly(5L, 6L, 7L).inOrder()
    }
}

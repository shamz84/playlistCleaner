package app.playlistplayer.ui.mobile.navigation

import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.ui.common.live.ChannelRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MobileNavigationTest {

    @Test
    fun `bottom bar below 600 dp, rail at and above`() {
        assertThat(navigationTypeFor(360)).isEqualTo(MobileNavigationType.BottomBar)
        assertThat(navigationTypeFor(599)).isEqualTo(MobileNavigationType.BottomBar)
        assertThat(navigationTypeFor(600)).isEqualTo(MobileNavigationType.Rail)
        assertThat(navigationTypeFor(840)).isEqualTo(MobileNavigationType.Rail)
        assertThat(isWideLayout(599)).isFalse()
        assertThat(isWideLayout(600)).isTrue()
    }

    @Test
    fun `bottom destinations are the six top-level screens, settings is not among them`() {
        assertThat(BottomDestinations).containsExactly(
            Destination.Home, Destination.Live, Destination.Guide, Destination.Movies, Destination.Series, Destination.Search,
        ).inOrder()
        assertThat(BottomDestinations).doesNotContain(Destination.Settings)
    }

    @Test
    fun `cold start lands on Home with an empty stack and back leaves the app`() {
        val nav = MobileNavState()
        assertThat(nav.destination).isEqualTo(Destination.Home)
        assertThat(nav.top).isNull()
        assertThat(nav.canGoBack).isFalse()
        assertThat(nav.back()).isNull()
    }

    @Test
    fun `back at another destination root goes Home`() {
        val nav = MobileNavState().select(Destination.Movies)
        assertThat(nav.canGoBack).isTrue()
        assertThat(nav.back()).isEqualTo(MobileNavState(destination = Destination.Home))
    }

    @Test
    fun `settings is reachable as a destination and back returns Home`() {
        val nav = MobileNavState().select(Destination.Settings)
        assertThat(nav.destination).isEqualTo(Destination.Settings)
        assertThat(nav.back()?.destination).isEqualTo(Destination.Home)
    }

    @Test
    fun `push gives unique ids and back pops in order`() {
        val nav = MobileNavState().select(Destination.Movies)
            .push(MobileScreen.MovieDetail(1, "10"))
            .push(MobileScreen.MovieDetail(1, "11"))
        assertThat(nav.stack.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(nav.top?.screen).isEqualTo(MobileScreen.MovieDetail(1, "11"))

        val once = nav.back()!!
        assertThat(once.top?.screen).isEqualTo(MobileScreen.MovieDetail(1, "10"))
        assertThat(once.destination).isEqualTo(Destination.Movies)
        val twice = once.back()!!
        assertThat(twice.stack).isEmpty()
        assertThat(twice.destination).isEqualTo(Destination.Movies)
    }

    @Test
    fun `selecting a destination clears the stack and reports removed entries`() {
        val nav = MobileNavState().select(Destination.Series).push(MobileScreen.SeriesDetail(2, "5")).push(MobileScreen.AddPlaylist)
        val next = nav.select(Destination.Live)
        assertThat(next.stack).isEmpty()
        assertThat(next.destination).isEqualTo(Destination.Live)
        assertThat(nav.removedIn(next)).containsExactly(1L, 2L)
        // Ids keep increasing so a cleared ViewModel store is never reused.
        assertThat(next.push(MobileScreen.AddPlaylist).top?.id).isEqualTo(3L)
    }

    @Test
    fun `players are fullscreen, detail screens are not`() {
        val channels = listOf(ChannelRow(1, 0, 1, "One"), ChannelRow(1, 1, 2, "Two"))
        val detail = MobileNavState().push(MobileScreen.MovieDetail(1, "10"))
        assertThat(detail.isFullscreen).isFalse()
        val live = detail.push(MobileScreen.LivePlayer(channels, 0))
        assertThat(live.isFullscreen).isTrue()
        assertThat(live.back()?.isFullscreen).isFalse()
    }

    @Test
    fun `replaceTop switches the channel in place keeping the entry id`() {
        val channels = listOf(ChannelRow(1, 0, 1, "One"), ChannelRow(1, 1, 2, "Two"))
        val nav = MobileNavState().select(Destination.Live).push(MobileScreen.LivePlayer(channels, 0))
        val next = nav.replaceTop(MobileScreen.LivePlayer(channels, 1))
        assertThat(next.stack).hasSize(1)
        assertThat(next.top?.id).isEqualTo(nav.top?.id)
        assertThat((next.top?.screen as MobileScreen.LivePlayer).index).isEqualTo(1)
        assertThat(nav.removedIn(next)).isEmpty()
    }

    @Test
    fun `replaceTop on an empty stack pushes`() {
        val next = MobileNavState().replaceTop(MobileScreen.AddPlaylist)
        assertThat(next.stack.map { it.screen }).containsExactly(MobileScreen.AddPlaylist)
    }

    @Test
    fun `pop on an empty stack is a no-op`() {
        val nav = MobileNavState().select(Destination.Guide)
        assertThat(nav.pop()).isEqualTo(nav)
    }
}

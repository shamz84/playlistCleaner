package app.playlistplayer.ui.mobile.navigation

import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.feature.shell.Destination
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MobileGuideNavigationTest {

    private val channel = LiveChannel(1, 10, 2, "UK: BBC One HD", streamId = 101, archiveDays = 3)
    private val request = CatchupRequest(channel, emptyList(), 1_000, 2_000, "News")

    @Test
    fun `guide players are fullscreen`() {
        assertThat(MobileScreen.ChannelPlayer(1, 10).isFullscreen).isTrue()
        assertThat(MobileScreen.CatchupPlayer(request).isFullscreen).isTrue()
    }

    @Test
    fun `catch-up from the guide returns to the guide, from the live player to the live player`() {
        val guide = MobileNavState().select(Destination.Guide)
        val catchup = guide.push(MobileScreen.CatchupPlayer(request))
        assertThat(catchup.isFullscreen).isTrue()
        val back = catchup.back()!!
        assertThat(back.destination).isEqualTo(Destination.Guide)
        assertThat(back.stack).isEmpty()

        val live = guide.push(MobileScreen.ChannelPlayer(1, 10))
        val restarted = live.push(MobileScreen.CatchupPlayer(request))
        assertThat(restarted.back()!!.top!!.screen).isEqualTo(MobileScreen.ChannelPlayer(1, 10))
    }

    @Test
    fun `channel changes in the live player keep the entry`() {
        val live = MobileNavState().select(Destination.Guide).push(MobileScreen.ChannelPlayer(1, 10))
        val zapped = live.replaceTop(MobileScreen.ChannelPlayer(1, 11))
        assertThat(zapped.top!!.id).isEqualTo(live.top!!.id)
        assertThat(live.removedIn(zapped)).isEmpty()
    }
}

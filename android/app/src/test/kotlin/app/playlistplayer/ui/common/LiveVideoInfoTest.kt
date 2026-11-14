package app.playlistplayer.ui.common

import app.playlistplayer.core.player.VideoFormatInfo
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class LiveVideoInfoTest {
    @Test
    fun `reports only known resolution and frame rate`() {
        fun format(width: Int, height: Int, fps: Float?) = VideoFormatInfo(width, height, fps, null, null)
        assertThat(videoInfoParts(format(1920, 1080, 59.94f), Locale.UK)).isEqualTo("1920 × 1080" to "59.94")
        assertThat(videoInfoParts(format(-1, -1, 25f), Locale.UK)).isEqualTo(null to "25")
        assertThat(videoInfoParts(format(1280, 720, null), Locale.UK)).isEqualTo("1280 × 720" to null)
        assertThat(videoInfoParts(format(-1, -1, Float.NaN), Locale.UK)).isEqualTo(null to null)
        assertThat(videoInfoParts(null, Locale.UK)).isEqualTo(null to null)
    }
}

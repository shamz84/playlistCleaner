package app.playlistplayer.ui.tv.vod

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetailContrastTest {
    @Test
    fun `white detail text has at least 4 point 5 to 1 contrast over bright artwork`() {
        assertThat(DetailTextColor).isEqualTo(Color.White)
        DetailBackdropScrim.forEach { scrim ->
            val background = scrim.compositeOver(Color.White)
            val ratio = (DetailTextColor.luminance() + 0.05f) / (background.luminance() + 0.05f)
            assertThat(ratio).isAtLeast(4.5f)
        }
    }
}

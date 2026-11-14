package app.playlistplayer.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.core.player.PlaybackDebugStats
import app.playlistplayer.core.player.VideoFormatInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VodDiagnosticsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `panel shows buffer state frames format and decoder`() {
        compose.setContent {
            VodDiagnosticsPanel(
                PlaybackDebugStats(
                    bufferedMs = 12_500,
                    droppedFrames = 42,
                    decoderDroppedFrames = 42,
                    allocatedBufferBytes = 32 * 1024 * 1024,
                    heapUsedBytes = 100L * 1024 * 1024,
                    heapMaxBytes = 192L * 1024 * 1024,
                    videoFormat = VideoFormatInfo(3840, 2160, 24f, "video/hevc", null),
                    videoDecoderName = "OMX.test.hevc",
                    isBuffering = true,
                    playWhenReady = true,
                ),
            )
        }
        compose.onNodeWithText("State: Buffering").assertExists()
        compose.onNodeWithText("Buffered ahead: 12.5 s").assertExists()
        compose.onNodeWithText("Player buffer: 32.0 MiB").assertExists()
        compose.onNodeWithText("App heap: 100.0 / 192.0 MiB").assertExists()
        compose.onNodeWithText("Dropped frames (decoder): 42").assertExists()
        compose.onNodeWithText("Video: 3840 × 2160").assertExists()
        compose.onNodeWithText("Frame rate: 24.00 fps").assertExists()
        compose.onNodeWithText("Decoder: OMX.test.hevc").assertExists()
    }

    @Test
    fun `unknown video format is not shown as a bogus resolution`() {
        compose.setContent {
            VodDiagnosticsPanel(PlaybackDebugStats(isPlaying = true, playWhenReady = true))
        }
        compose.onNodeWithText("State: Playing").assertExists()
        compose.onNodeWithText("Video:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Decoder:", substring = true).assertDoesNotExist()
    }
}

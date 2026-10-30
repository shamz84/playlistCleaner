package app.playlistplayer.ui.mobile.player

import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.feature.vod.OverlayCommand
import app.playlistplayer.feature.vod.OverlayEvent
import app.playlistplayer.feature.vod.SeekDirection
import app.playlistplayer.feature.vod.VodPlayerOverlayState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerGesturesTest {

    private val width = 900f

    @Test
    fun `tap zones split the width into thirds`() {
        assertThat(PlayerGestures.zoneOf(0f, width)).isEqualTo(TapZone.Left)
        assertThat(PlayerGestures.zoneOf(299f, width)).isEqualTo(TapZone.Left)
        assertThat(PlayerGestures.zoneOf(450f, width)).isEqualTo(TapZone.Center)
        assertThat(PlayerGestures.zoneOf(601f, width)).isEqualTo(TapZone.Right)
        assertThat(PlayerGestures.zoneOf(900f, width)).isEqualTo(TapZone.Right)
        assertThat(PlayerGestures.zoneOf(10f, 0f)).isEqualTo(TapZone.Center)
    }

    @Test
    fun `single tap toggles the controls`() {
        assertThat(PlayerGestures.onTap()).isEqualTo(GestureCommand.ToggleControls)
    }

    @Test
    fun `double tap left seeks back, right seeks forward, middle plays or pauses`() {
        assertThat(PlayerGestures.onDoubleTap(100f, width, isLive = false)).isEqualTo(GestureCommand.Seek(SeekDirection.BACK))
        assertThat(PlayerGestures.onDoubleTap(800f, width, isLive = false)).isEqualTo(GestureCommand.Seek(SeekDirection.FORWARD))
        assertThat(PlayerGestures.onDoubleTap(450f, width, isLive = false)).isEqualTo(GestureCommand.PlayPause)
    }

    @Test
    fun `double tap does nothing on live`() {
        assertThat(PlayerGestures.onDoubleTap(100f, width, isLive = true)).isEqualTo(GestureCommand.None)
        assertThat(PlayerGestures.onDoubleTap(800f, width, isLive = true)).isEqualTo(GestureCommand.None)
    }

    @Test
    fun `double tap steps are minus 10 s and plus 30 s`() {
        assertThat(PlayerGestures.stepMs(SeekDirection.BACK)).isEqualTo(-10_000L)
        assertThat(PlayerGestures.stepMs(SeekDirection.FORWARD)).isEqualTo(30_000L)
    }

    @Test
    fun `a double tap seek through the overlay commits one step`() {
        // The screen maps Seek(direction) onto SeekKey + SeekKeyUp; check the overlay yields the step.
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(resumeOfferMs = null, startPositionMs = 60_000))
        overlay.handle(OverlayEvent.Progress(positionMs = 60_000, durationMs = 600_000, isPlaying = true, nowMs = 0))

        overlay.handle(OverlayEvent.SeekKey(SeekDirection.FORWARD, repeatCount = 0))
        assertThat(overlay.handle(OverlayEvent.SeekKeyUp)).containsExactly(OverlayCommand.SeekTo(90_000))

        overlay.handle(OverlayEvent.SeekKey(SeekDirection.BACK, repeatCount = 0))
        assertThat(overlay.handle(OverlayEvent.SeekKeyUp)).containsExactly(OverlayCommand.SeekTo(80_000))
    }

    @Test
    fun `horizontal drag maps the full width to two minutes`() {
        assertThat(PlayerGestures.dragTargetMs(60_000, dragPx = width, widthPx = width, durationMs = 3_600_000)).isEqualTo(180_000)
        assertThat(PlayerGestures.dragTargetMs(60_000, dragPx = width / 4, widthPx = width, durationMs = 3_600_000)).isEqualTo(90_000)
        assertThat(PlayerGestures.dragTargetMs(60_000, dragPx = -width / 4, widthPx = width, durationMs = 3_600_000)).isEqualTo(30_000)
    }

    @Test
    fun `drag target is clamped to the title`() {
        assertThat(PlayerGestures.dragTargetMs(10_000, dragPx = -width, widthPx = width, durationMs = 600_000)).isEqualTo(0)
        assertThat(PlayerGestures.dragTargetMs(590_000, dragPx = width, widthPx = width, durationMs = 600_000)).isEqualTo(600_000)
        // Unknown duration: only the lower bound applies.
        assertThat(PlayerGestures.dragTargetMs(10_000, dragPx = width, widthPx = width, durationMs = 0)).isEqualTo(130_000)
        assertThat(PlayerGestures.dragTargetMs(10_000, dragPx = 50f, widthPx = 0f, durationMs = 600_000)).isEqualTo(10_000)
    }

    @Test
    fun `picked version plays first and the rest keep their order`() {
        val a = source("a")
        val b = source("b")
        val c = source("c")
        assertThat(withPickedFirst(listOf(a, b, c), "c").map { it.id }).containsExactly("c", "a", "b").inOrder()
        assertThat(withPickedFirst(listOf(a, b, c), "a").map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(withPickedFirst(listOf(a, b, c), "missing")).containsExactly(a, b, c).inOrder()
    }

    private fun source(id: String) = PlayableSource(id = id, url = "http://example.invalid/$id", label = id.uppercase(), isLive = true)
}

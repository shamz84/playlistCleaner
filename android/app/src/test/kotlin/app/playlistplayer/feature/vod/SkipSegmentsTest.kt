package app.playlistplayer.feature.vod

import app.playlistplayer.feature.settings.VideoScaling
import app.playlistplayer.ui.common.scaledVideoSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkipSegmentsTest {
    private val episode = 45L * 60 * 1000

    @Test
    fun introIsOfferedEarlyInEpisodesOnly() {
        assertThat(SkipSegments.promptAt(20_000, episode, isEpisode = true)?.kind).isEqualTo(SkipKind.INTRO)
        assertThat(SkipSegments.promptAt(20_000, episode, isEpisode = true)?.targetMs).isEqualTo(20_000 + SkipSegments.INTRO_SKIP_MS)
        assertThat(SkipSegments.promptAt(20_000, episode, isEpisode = false)).isNull()
        assertThat(SkipSegments.promptAt(5_000, episode, isEpisode = true)).isNull()
        assertThat(SkipSegments.promptAt(60_000, episode, isEpisode = true)).isNull()
    }

    @Test
    fun creditsAreOfferedBeforeTheNextEpisodeCard() {
        assertThat(SkipSegments.promptAt(episode - 200_000, episode, isEpisode = true)?.kind).isEqualTo(SkipKind.CREDITS)
        assertThat(SkipSegments.promptAt(episode - 300_000, episode, isEpisode = true)).isNull()
        assertThat(SkipSegments.promptAt(episode - 100_000, 5L * 60 * 1000, isEpisode = false)).isNull()
    }

    @Test
    fun skippingIntroSeeksAndCreditsPlayNext() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null, nextLabel = "Next", isEpisode = true))
        overlay.handle(OverlayEvent.Progress(20_000, episode, true, 0))
        assertThat(overlay.state.skip?.kind).isEqualTo(SkipKind.INTRO)
        assertThat(overlay.handle(OverlayEvent.SkipNow)).containsExactly(OverlayCommand.SeekTo(20_000 + SkipSegments.INTRO_SKIP_MS))
        overlay.handle(OverlayEvent.Progress(30_000, episode, true, 0))
        assertThat(overlay.state.skip).isNull()

        overlay.handle(OverlayEvent.Progress(episode - 200_000, episode, true, 0))
        assertThat(overlay.state.skip?.kind).isEqualTo(SkipKind.CREDITS)
        assertThat(overlay.handle(OverlayEvent.SkipNow)).containsExactly(OverlayCommand.PlayNext)
    }

    @Test
    fun fitLetterboxesZoomCropsStretchFills() {
        val wide = 16f / 9f
        val fit = scaledVideoSize(VideoScaling.FIT, 4f / 3f, 1600f, 900f)
        assertThat(fit.first).isWithin(0.5f).of(1200f)
        assertThat(fit.second).isWithin(0.5f).of(900f)
        val zoom = scaledVideoSize(VideoScaling.ZOOM, 4f / 3f, 1600f, 900f)
        assertThat(zoom.first).isWithin(0.5f).of(1600f)
        assertThat(zoom.second).isWithin(0.5f).of(1200f)
        assertThat(scaledVideoSize(VideoScaling.STRETCH, 4f / 3f, 1600f, 900f)).isEqualTo((1600f to 900f))
        assertThat(scaledVideoSize(VideoScaling.FIT, wide, 1600f, 900f).first).isWithin(0.01f).of(1600f)
        assertThat(scaledVideoSize(VideoScaling.FIT, 0f, 1600f, 900f)).isEqualTo((1600f to 900f))
    }

    @Test
    fun ratioOptionsForceTheirShapeAndLetterboxInTheScreen() {
        val a = scaledVideoSize(VideoScaling.R4_3, 16f / 9f, 1600f, 900f)
        assertThat(a.first).isWithin(0.5f).of(1200f)
        assertThat(a.second).isWithin(0.5f).of(900f)
        assertThat(scaledVideoSize(VideoScaling.R16_9, 4f / 3f, 1600f, 900f)).isEqualTo((1600f to 900f))
        val c = scaledVideoSize(VideoScaling.R2_35, 16f / 9f, 1600f, 900f)
        assertThat(c.first).isWithin(0.5f).of(1600f)
        assertThat(c.second).isWithin(0.5f).of(1600f / 2.35f)
        // Shown before the first frame is decoded: fills the box.
        assertThat(scaledVideoSize(VideoScaling.R2_35, 0f, 1600f, 900f)).isEqualTo((1600f to 900f))
    }

    @Test
    fun cyclingVisitsEveryOptionAndWrapsToNormal() {
        var s = VideoScaling.FIT
        val seen = (1..VideoScaling.entries.size).map { s = s.next(); s }
        assertThat(seen).containsExactlyElementsIn(VideoScaling.entries)
        assertThat(s).isEqualTo(VideoScaling.FIT)
    }
}

package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.network.skip.SkipMarker
import app.playlistplayer.core.network.skip.SkipMarkerException
import app.playlistplayer.core.network.skip.SkipMarkerType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SkipSpanTest {
    private val hour = 3_600_000L
    private val spans = listOf(
        SkipSpan(SkipKind.RECAP, 0, 30_000),
        SkipSpan(SkipKind.INTRO, 60_000, 90_000),
        SkipSpan(SkipKind.CREDITS, 55 * 60_000L, null),
    )

    @Test
    fun realSpansDriveTheOfferAndIgnoreTheGuessWindow() {
        // 20 s is inside the guessed intro window but the real intro starts at 60 s.
        assertThat(SkipSegments.promptFromSpans(spans.drop(1), 20_000, hour, true)).isNull()
        assertThat(SkipSegments.promptFromSpans(spans, 10_000, hour, true)).isEqualTo(SkipPrompt(SkipKind.RECAP, 30_000))
        assertThat(SkipSegments.promptFromSpans(spans, 61_000, hour, true)).isEqualTo(SkipPrompt(SkipKind.INTRO, 90_000))
        assertThat(SkipSegments.promptFromSpans(spans, 56 * 60_000L, hour, true)).isEqualTo(SkipPrompt(SkipKind.CREDITS, hour))
    }

    @Test
    fun creditsGuessStillAppliesWhenMarkersHaveNoCredits() {
        val introOnly = listOf(SkipSpan(SkipKind.INTRO, 60_000, 90_000))
        assertThat(SkipSegments.promptFromSpans(introOnly, hour - 100_000, hour, true)?.kind).isEqualTo(SkipKind.CREDITS)
    }

    @Test
    fun overlayUsesMarkersAndSkipsToTheirEnd() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null, isEpisode = true))
        overlay.handle(OverlayEvent.Markers(spans))
        overlay.handle(OverlayEvent.Progress(61_000, hour, true, 0))
        assertThat(overlay.state.skip?.kind).isEqualTo(SkipKind.INTRO)

        val commands = overlay.handle(OverlayEvent.SkipNow)

        assertThat(commands).containsExactly(OverlayCommand.SeekTo(90_000))
    }

    @Test
    fun repositoryMapsCachesAndToleratesFailure() = runBlocking<Unit> {
        var calls = 0
        val repo = SkipSpanRepository({ tmdb, season, episode ->
            calls++
            assertThat(tmdb).isEqualTo(1396)
            assertThat(season).isEqualTo(1)
            assertThat(episode).isEqualTo(2)
            listOf(SkipMarker(SkipMarkerType.INTRO, null, 40_000))
        })
        val request = request(VodKind.SERIES, tmdb = 1396, key = ProgressKey("t", 1, 2))

        assertThat(repo.spans(request)).containsExactly(SkipSpan(SkipKind.INTRO, 0, 40_000))
        repo.spans(request)
        assertThat(calls).isEqualTo(1)

        val failing = SkipSpanRepository({ _, _, _ -> throw SkipMarkerException("down") })
        assertThat(failing.spans(request)).isEmpty()
        assertThat(failing.spans(request(VodKind.SERIES, tmdb = null, key = ProgressKey("t", 1, 2)))).isEmpty()
    }

    private fun request(kind: VodKind, tmdb: Long?, key: ProgressKey) = PlaybackRequest(
        sources = emptyList(),
        progressKey = key,
        playlistId = 1,
        kind = kind,
        label = "x",
        tmdbId = tmdb,
    )
}

package app.playlistplayer.ui.mobile.vod

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.vod.PlaylistSection
import app.playlistplayer.feature.vod.SeriesUiState
import app.playlistplayer.feature.vod.TitleDetailUiState
import app.playlistplayer.feature.vod.VodBrowseUiState
import app.playlistplayer.feature.vod.VodPlaylist
import app.playlistplayer.ui.common.UiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import app.playlistplayer.ui.common.formatClock

class MobileVodStatesTest {

    private val playlist = VodPlaylist(1, "Main")

    @Test
    fun `browse maps loading, empty and content`() {
        assertThat(MobileVodStates.browse(VodBrowseUiState(VodKind.MOVIE), "none")).isEqualTo(UiState.Loading)
        assertThat(MobileVodStates.browse(VodBrowseUiState(VodKind.MOVIE, loading = false), "none")).isEqualTo(UiState.Empty("none"))
        val empty = VodBrowseUiState(VodKind.MOVIE, loading = false, sections = listOf(PlaylistSection(playlist, emptyList())))
        assertThat(MobileVodStates.browse(empty, "none")).isEqualTo(UiState.Empty("none"))
    }

    @Test
    fun `a failed section is shown, not reported as empty`() {
        val failed = VodBrowseUiState(VodKind.SERIES, loading = false, sections = listOf(PlaylistSection(playlist, emptyList(), failed = true)))
        assertThat(MobileVodStates.browse(failed, "none")).isEqualTo(UiState.Content(failed.sections))
    }

    @Test
    fun `movie detail maps loading, not found and content`() {
        assertThat(MobileVodStates.movie(TitleDetailUiState(), "gone")).isEqualTo(UiState.Loading)
        assertThat(MobileVodStates.movie(TitleDetailUiState(loading = false, notFound = true), "gone")).isEqualTo(UiState.Empty("gone"))
        val loaded = TitleDetailUiState(loading = false, titleKey = "k", title = "Film")
        assertThat(MobileVodStates.movie(loaded, "gone")).isEqualTo(UiState.Content(loaded))
    }

    @Test
    fun `series header shows while episodes load`() {
        assertThat(MobileVodStates.series(SeriesUiState(), "gone")).isEqualTo(UiState.Loading)
        val header = SeriesUiState(loading = true, titleKey = "k", title = "Show")
        assertThat(MobileVodStates.series(header, "gone")).isEqualTo(UiState.Content(header))
        assertThat(MobileVodStates.series(SeriesUiState(loading = false, notFound = true), "gone")).isEqualTo(UiState.Empty("gone"))
    }

    @Test
    fun `season labels prefer the provider name, then specials, then the number`() {
        val season = { n: Int -> "Season $n" }
        assertThat(MobileVodStates.seasonLabel("Book One", 1, "Specials", season)).isEqualTo("Book One")
        assertThat(MobileVodStates.seasonLabel(" ", 2, "Specials", season)).isEqualTo("Season 2")
        assertThat(MobileVodStates.seasonLabel(null, 0, "Specials", season)).isEqualTo("Specials")
    }

    @Test
    fun `clock text formats minutes and hours`() {
        assertThat(formatClock(0)).isEqualTo("0:00")
        assertThat(formatClock(245_000)).isEqualTo("4:05")
        assertThat(formatClock(3_723_000)).isEqualTo("1:02:03")
        assertThat(formatClock(-5)).isEqualTo("0:00")
    }
}

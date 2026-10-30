package app.playlistplayer.ui.mobile.vod

import app.playlistplayer.feature.vod.SeriesUiState
import app.playlistplayer.feature.vod.TitleDetailUiState
import app.playlistplayer.feature.vod.VodBrowseUiState
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.feature.vod.PlaylistSection as VodSection

/**
 * Maps the VOD view-model states onto the shared [UiState] so the phone renders loading / empty /
 * error with the same views as live. Pure display mapping; no feature decisions.
 */
internal object MobileVodStates {

    /** Sections shown in browse. Empty only when nothing loaded and no section failed (failed ones show Retry). */
    fun browse(state: VodBrowseUiState, emptyText: String): UiState<List<VodSection>> = when {
        state.loading -> UiState.Loading
        state.isEmpty && state.sections.none { it.failed } -> UiState.Empty(emptyText)
        else -> UiState.Content(state.sections)
    }

    fun movie(state: TitleDetailUiState, notFoundText: String): UiState<TitleDetailUiState> = when {
        state.loading -> UiState.Loading
        state.notFound -> UiState.Empty(notFoundText)
        else -> UiState.Content(state)
    }

    /** The series header is shown while episodes load; only "not found" replaces the screen. */
    fun series(state: SeriesUiState, notFoundText: String): UiState<SeriesUiState> = when {
        state.notFound -> UiState.Empty(notFoundText)
        state.loading && state.titleKey == null -> UiState.Loading
        else -> UiState.Content(state)
    }

    /** Label of a season tab: the provider's season name, else "Specials" for 0, else "Season n". */
    fun seasonLabel(name: String?, number: Int, specials: String, season: (Int) -> String): String =
        name?.takeIf { it.isNotBlank() } ?: if (number == 0) specials else season(number)
}

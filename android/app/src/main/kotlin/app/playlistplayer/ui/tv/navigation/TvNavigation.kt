package app.playlistplayer.ui.tv.navigation

import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.vod.PlaybackRequest

/** A full-screen TV screen opened above the shell (side rail + destination). */
sealed interface TvScreen {
    /** Live player over the shared `LivePlayerViewModel`; channel up/down stays within the channel's group. */
    data class LivePlayer(val playlistId: Long, val ordinal: Int) : TvScreen

    /** Catch-up player (past programme from the Guide, or "Restart programme" from the live player). */
    data class CatchupPlayer(val request: CatchupRequest) : TvScreen

    data class MovieDetail(val playlistId: Long, val itemId: String) : TvScreen

    data class SeriesDetail(val playlistId: Long, val itemId: String) : TvScreen

    /**
     * VOD player. [onNextEpisode] builds the following episode's request (series only); [onClosed]
     * lets the detail screen below re-read progress. Not a data class: it carries callbacks.
     */
    class VodPlayer(
        val request: PlaybackRequest,
        val onNextEpisode: suspend () -> PlaybackRequest? = { null },
        val onClosed: () -> Unit = {},
    ) : TvScreen
}

/** A pushed screen with a stable [id] (keys its ViewModel store and saved UI state). */
data class TvEntry(val id: Long, val screen: TvScreen)

/**
 * The TV back stack above the shell. Pure and immutable; the root holds the current value. Back
 * pops the top screen (the player, then the detail, …); with an empty stack the shell handles
 * Back (content → rail → "Exit app?", U9).
 */
data class TvBackStack(
    val entries: List<TvEntry> = emptyList(),
    val nextId: Long = 1,
) {
    val top: TvEntry? get() = entries.lastOrNull()

    fun push(screen: TvScreen): TvBackStack = copy(entries = entries + TvEntry(nextId, screen), nextId = nextId + 1)

    fun pop(): TvBackStack = if (entries.isEmpty()) this else copy(entries = entries.dropLast(1))

    /** Replaces the top screen keeping its id (next episode). */
    fun replaceTop(screen: TvScreen): TvBackStack {
        val current = top ?: return push(screen)
        return copy(entries = entries.dropLast(1) + current.copy(screen = screen))
    }

    /** Back to the shell. */
    fun clear(): TvBackStack = copy(entries = emptyList())

    /** Entry ids present here but not in [next]: their ViewModels and saved state can be cleared. */
    fun removedIn(next: TvBackStack): Set<Long> = entries.map { it.id }.toSet() - next.entries.map { it.id }.toSet()
}

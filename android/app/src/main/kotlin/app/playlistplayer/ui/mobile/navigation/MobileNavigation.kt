package app.playlistplayer.ui.mobile.navigation

import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.ui.common.live.ChannelRow

/** How the top-level destinations are shown: a bottom bar on phones, a side rail on wide windows. */
enum class MobileNavigationType { BottomBar, Rail }

/** Width (dp) from which the rail replaces the bottom bar (Material "medium" window width class). */
const val RAIL_MIN_WIDTH_DP: Int = 600

/** Bottom bar below [RAIL_MIN_WIDTH_DP], rail at or above it (tablets, phones in landscape). */
fun navigationTypeFor(widthDp: Int): MobileNavigationType =
    if (widthDp >= RAIL_MIN_WIDTH_DP) MobileNavigationType.Rail else MobileNavigationType.BottomBar

/** Two-pane live browse (groups beside channels) needs the same width as the rail. */
fun isWideLayout(widthDp: Int): Boolean = widthDp >= RAIL_MIN_WIDTH_DP

/**
 * Destinations in the bottom bar / rail, in order. Settings is not among them: it is reached from
 * the top app bar action on every top-level screen (a phone bottom bar fits six items at most).
 */
val BottomDestinations: List<Destination> = listOf(
    Destination.Home,
    Destination.Live,
    Destination.Guide,
    Destination.Movies,
    Destination.Series,
    Destination.Search,
)

/** A screen pushed on top of a top-level destination. */
sealed interface MobileScreen {
    data class MovieDetail(val playlistId: Long, val itemId: String) : MobileScreen

    data class SeriesDetail(val playlistId: Long, val itemId: String) : MobileScreen

    /** "Add playlist" from Settings (onboarding form without the welcome title). */
    data object AddPlaylist : MobileScreen

    /**
     * Fullscreen live player. [channels] is the group the channel was opened from, so previous /
     * next channel stays within the group (R26), as channel up/down does on TV.
     */
    data class LivePlayer(val channels: List<ChannelRow>, val index: Int) : MobileScreen

    /**
     * Fullscreen VOD player. [onNextEpisode] builds the following episode's request (series only);
     * [onClosed] lets the screen below re-read progress. Not a data class: it carries callbacks.
     */
    class VodPlayer(
        val request: PlaybackRequest,
        val onNextEpisode: (suspend () -> PlaybackRequest?)? = null,
        val onClosed: () -> Unit = {},
    ) : MobileScreen
}

val MobileScreen.isFullscreen: Boolean
    get() = this is MobileScreen.LivePlayer || this is MobileScreen.VodPlayer

/** A pushed screen with a stable [id] (keys its ViewModel store and saved UI state). */
data class MobileEntry(val id: Long, val screen: MobileScreen)

/**
 * Phone navigation state: the selected top-level destination plus a stack of pushed screens.
 * Pure and immutable; the UI holds the current value.
 *
 * - Cold start lands on Home (as on TV).
 * - Selecting a destination (bottom bar, rail or the Settings action) clears the stack; selecting
 *   the current one again returns to its root.
 * - Back pops the stack; at a destination root other than Home it goes to Home; at Home's root
 *   [back] returns null and the system handles Back (the app closes, the phone convention).
 */
data class MobileNavState(
    val destination: Destination = Destination.Home,
    val stack: List<MobileEntry> = emptyList(),
    val nextId: Long = 1,
) {
    val top: MobileEntry? get() = stack.lastOrNull()

    val isFullscreen: Boolean get() = top?.screen?.isFullscreen == true

    val canGoBack: Boolean get() = stack.isNotEmpty() || destination != Destination.Home

    fun select(target: Destination): MobileNavState = copy(destination = target, stack = emptyList())

    fun push(screen: MobileScreen): MobileNavState =
        copy(stack = stack + MobileEntry(nextId, screen), nextId = nextId + 1)

    /** Replaces the top screen (e.g. next episode, or a new channel in the player) keeping its id. */
    fun replaceTop(screen: MobileScreen): MobileNavState {
        val current = top ?: return push(screen)
        return copy(stack = stack.dropLast(1) + current.copy(screen = screen))
    }

    /** Drops the top screen; unchanged when the stack is empty. */
    fun pop(): MobileNavState = if (stack.isEmpty()) this else copy(stack = stack.dropLast(1))

    /** The state after Back, or null when Back should leave the app. */
    fun back(): MobileNavState? = when {
        stack.isNotEmpty() -> pop()
        destination != Destination.Home -> select(Destination.Home)
        else -> null
    }

    /** Entry ids present here but not in [next]: their ViewModels and saved state can be cleared. */
    fun removedIn(next: MobileNavState): Set<Long> = stack.map { it.id }.toSet() - next.stack.map { it.id }.toSet()
}

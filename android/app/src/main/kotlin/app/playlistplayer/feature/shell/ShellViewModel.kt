package app.playlistplayer.feature.shell

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Top-level destinations of the side navigation rail, in rail order (U9). */
enum class Destination { Home, Live, Guide, Movies, Series, Search, Settings }

/** Which part of the shell holds focus. */
enum class ShellFocus { Rail, Content }

data class ShellState(
    val current: Destination = Destination.Home,
    val focus: ShellFocus = ShellFocus.Content,
    /** Rail item focused last; focus returns here when Back leaves a destination. */
    val lastRailFocus: Destination = Destination.Home,
    val showExitConfirmation: Boolean = false,
    val exitRequested: Boolean = false,
    /** Bumped on every Back that returns to the rail, so the UI re-requests focus even when [focus] was already Rail. */
    val railFocusRequest: Int = 0,
)

/**
 * Shell navigation state (U9). Pure state machine; no coroutines.
 *
 * - Cold start lands on Home with focus in its content.
 * - Selecting a rail item opens that destination and moves focus into it.
 * - Back from a destination's content returns to the rail, focus restored to the rail item
 *   focused last (destinations handle their own inner Back first, e.g. channel list → groups).
 * - Back at the root (rail focused) asks "Exit app?". Back again, or Cancel, dismisses it.
 */
class ShellViewModel : ViewModel() {
    private val _state = MutableStateFlow(ShellState())
    val state: StateFlow<ShellState> = _state.asStateFlow()

    fun onRailItemFocused(destination: Destination) {
        _state.update { it.copy(focus = ShellFocus.Rail, lastRailFocus = destination) }
    }

    fun select(destination: Destination) {
        _state.update {
            it.copy(current = destination, focus = ShellFocus.Content, lastRailFocus = destination, showExitConfirmation = false)
        }
    }

    fun onContentFocused() {
        _state.update { if (it.focus == ShellFocus.Content) it else it.copy(focus = ShellFocus.Content) }
    }

    /** Handles Back at shell level. Always consumes it (the confirmation dialog decides about exiting). */
    fun onBack() {
        _state.update {
            when {
                it.showExitConfirmation -> it.copy(showExitConfirmation = false)
                it.focus == ShellFocus.Content ->
                    it.copy(focus = ShellFocus.Rail, railFocusRequest = it.railFocusRequest + 1)
                else -> it.copy(showExitConfirmation = true)
            }
        }
    }

    fun confirmExit() {
        _state.update { it.copy(showExitConfirmation = false, exitRequested = true) }
    }

    fun dismissExit() {
        _state.update { it.copy(showExitConfirmation = false) }
    }
}

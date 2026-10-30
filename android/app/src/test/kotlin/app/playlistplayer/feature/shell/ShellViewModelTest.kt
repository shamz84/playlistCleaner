package app.playlistplayer.feature.shell

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellViewModelTest {

    private val vm = ShellViewModel()
    private val state get() = vm.state.value

    @Test
    fun `cold start lands on Home with focus in its content`() {
        assertThat(state.current).isEqualTo(Destination.Home)
        assertThat(state.focus).isEqualTo(ShellFocus.Content)
        assertThat(state.showExitConfirmation).isFalse()
        assertThat(state.exitRequested).isFalse()
    }

    @Test
    fun `rail order is Home Live Guide Movies Series Search Settings`() {
        assertThat(Destination.entries).containsExactly(
            Destination.Home, Destination.Live, Destination.Guide, Destination.Movies,
            Destination.Series, Destination.Search, Destination.Settings,
        ).inOrder()
    }

    @Test
    fun `back from content returns to the rail, then back at the root asks to exit`() {
        vm.onBack()
        assertThat(state.focus).isEqualTo(ShellFocus.Rail)
        assertThat(state.showExitConfirmation).isFalse()
        assertThat(state.railFocusRequest).isEqualTo(1)

        vm.onBack()
        assertThat(state.showExitConfirmation).isTrue()
        assertThat(state.exitRequested).isFalse()
    }

    @Test
    fun `back at the root on cold start (rail focused) asks to exit`() {
        vm.onRailItemFocused(Destination.Home)
        vm.onBack()
        assertThat(state.showExitConfirmation).isTrue()
    }

    @Test
    fun `back or cancel dismisses the exit confirmation, confirm requests exit`() {
        vm.onRailItemFocused(Destination.Home)
        vm.onBack()
        vm.onBack()
        assertThat(state.showExitConfirmation).isFalse()
        assertThat(state.exitRequested).isFalse()

        vm.onBack()
        vm.dismissExit()
        assertThat(state.showExitConfirmation).isFalse()

        vm.onBack()
        vm.confirmExit()
        assertThat(state.showExitConfirmation).isFalse()
        assertThat(state.exitRequested).isTrue()
    }

    @Test
    fun `back from a destination restores focus to the rail item focused last`() {
        vm.onRailItemFocused(Destination.Live)
        vm.select(Destination.Live)
        assertThat(state.current).isEqualTo(Destination.Live)
        assertThat(state.focus).isEqualTo(ShellFocus.Content)

        vm.onBack()
        assertThat(state.focus).isEqualTo(ShellFocus.Rail)
        assertThat(state.lastRailFocus).isEqualTo(Destination.Live)
        assertThat(state.current).isEqualTo(Destination.Live)
    }

    @Test
    fun `moving through the rail then into content restores the last focused item`() {
        vm.select(Destination.Live)
        vm.onRailItemFocused(Destination.Movies) // browsed the rail without selecting
        vm.onContentFocused() // moved right into Live content
        vm.onBack()
        assertThat(state.lastRailFocus).isEqualTo(Destination.Movies)
        assertThat(state.current).isEqualTo(Destination.Live)
    }

    @Test
    fun `each back to the rail bumps the focus request`() {
        vm.onBack()
        vm.onContentFocused()
        vm.onBack()
        assertThat(state.railFocusRequest).isEqualTo(2)
    }
}

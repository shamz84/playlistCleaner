package app.playlistplayer.ui.common.live

import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LiveBrowseViewModelTest {

    private val text = LiveBrowseText(noPlaylists = "none", emptyGroup = "empty", loadFailed = "failed")

    private fun group(playlistId: Long, ordinal: Int, name: String) = GroupItem(playlistId, ordinal, name, channelCount = 2)

    private val first = PlaylistSection(1, "Main", listOf(group(1, 0, "🇬🇧 UK | News"), group(1, 1, "Sports")))
    private val second = PlaylistSection(2, "Family", listOf(group(2, 0, "Kids")))

    private class FakeSource(sections: List<PlaylistSection>) : LiveBrowseSource {
        val sectionsFlow = MutableStateFlow(sections)
        var failChannels = false
        override fun sections(): Flow<List<PlaylistSection>> = sectionsFlow
        override fun channels(group: GroupKey): Flow<List<ChannelRow>> =
            if (failChannels) flow { throw IllegalStateException("provider http://secret.test/u/p") }
            else flowOf(numberChannels(listOf(ChannelRow(group.playlistId, group.groupOrdinal * 10, null, "Ch ${group.groupOrdinal}"))))
    }

    private fun TestScope.vm(source: LiveBrowseSource) = LiveBrowseViewModel(source, text, backgroundScope).also { runCurrent() }

    @Test
    fun `sections keep playlist and group order and the first group is selected`() = runTest {
        val vm = vm(FakeSource(listOf(first, second)))
        val s = vm.state.value
        assertThat((s.sections as UiState.Content).value).containsExactly(first, second).inOrder()
        assertThat(s.selectedGroup).isEqualTo(GroupKey(1, 0))
        assertThat((s.channels as UiState.Content).value.single().number).isEqualTo(1)
        assertThat(s.playableCount).isEqualTo(1)
    }

    @Test
    fun `selecting a group of another playlist loads its channels only`() = runTest {
        val vm = vm(FakeSource(listOf(first, second)))
        vm.selectGroup(GroupKey(2, 0))
        runCurrent()
        val channels = (vm.state.value.channels as UiState.Content).value
        assertThat(channels.map { it.playlistId }).containsExactly(2L)
    }

    @Test
    fun `selection falls back to the first group when its group disappears`() = runTest {
        val source = FakeSource(listOf(first, second))
        val vm = vm(source)
        vm.selectGroup(GroupKey(2, 0))
        source.sectionsFlow.value = listOf(first)
        runCurrent()
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(1, 0))
    }

    @Test
    fun `no playlists is an empty state`() = runTest {
        val vm = vm(FakeSource(emptyList()))
        assertThat(vm.state.value.sections).isEqualTo(UiState.Empty("none"))
        assertThat(vm.state.value.channels).isEqualTo(UiState.Empty("empty"))
    }

    @Test
    fun `channel load failure shows the generic message, never the exception text`() = runTest {
        val source = FakeSource(listOf(first)).apply { failChannels = true }
        val vm = vm(source)
        val channels = vm.state.value.channels as UiState.Error
        assertThat(channels.message).isEqualTo("failed")
        assertThat(channels.retry).isNotNull()
    }
}

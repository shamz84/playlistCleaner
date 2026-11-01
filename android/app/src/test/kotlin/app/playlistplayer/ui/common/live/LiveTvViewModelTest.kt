package app.playlistplayer.ui.common.live

import app.playlistplayer.feature.live.LastChannel
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvViewModelTest {

    private val text = LiveBrowseText(noPlaylists = "none", emptyGroup = "empty", loadFailed = "failed")

    private fun group(playlistId: Long, ordinal: Int, name: String) = GroupItem(playlistId, ordinal, name, channelCount = 2)

    private val first = PlaylistSection(1, "Main", listOf(group(1, 0, "🇬🇧 UK | News"), group(1, 1, "Sports")))
    private val second = PlaylistSection(2, "Family", listOf(group(2, 0, "Kids"), group(2, 1, "Docs")))
    private val empty = PlaylistSection(3, "Empty", emptyList())

    private class FakeSource(sections: List<PlaylistSection>) : LiveBrowseSource {
        val sectionsFlow = MutableStateFlow(sections)
        override fun sections(): Flow<List<PlaylistSection>> = sectionsFlow
        override fun channels(group: GroupKey): Flow<List<ChannelRow>> = flowOf(emptyList())
    }

    private fun TestScope.vm(source: LiveBrowseSource, last: suspend (Long) -> LastChannel? = { null }) =
        LiveTvViewModel(source, last, text, backgroundScope).also { runCurrent() }

    @Test
    fun `without a last channel the first group of the first playlist is selected`() = runTest {
        val vm = vm(FakeSource(listOf(empty, first, second)))
        val s = vm.state.value
        assertThat((s.sections as UiState.Content).value).containsExactly(first, second).inOrder()
        assertThat(s.selectedGroup).isEqualTo(GroupKey(1, 0))
        assertThat(s.restoreChannel).isNull()
    }

    @Test
    fun `first open restores the group and channel last played`() = runTest {
        val vm = vm(FakeSource(listOf(first, second))) { playlistId -> if (playlistId == 2L) LastChannel(ordinal = 17, groupOrdinal = 1) else null }
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(2, 1))
        assertThat(vm.state.value.restoreChannel).isEqualTo(17)

        vm.onRestoreHandled()
        assertThat(vm.state.value.restoreChannel).isNull()
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(2, 1))
    }

    @Test
    fun `a last channel whose group is no longer listed falls back to the first group`() = runTest {
        val vm = vm(FakeSource(listOf(first))) { LastChannel(ordinal = 3, groupOrdinal = 9) }
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(1, 0))
        assertThat(vm.state.value.restoreChannel).isNull()
    }

    @Test
    fun `a failing last-channel lookup still opens the first group`() = runTest {
        val vm = vm(FakeSource(listOf(first))) { throw IllegalStateException("db") }
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(1, 0))
    }

    @Test
    fun `selecting a group shows it and drops a pending restore of another group`() = runTest {
        val vm = vm(FakeSource(listOf(first, second))) { playlistId -> if (playlistId == 1L) LastChannel(5, 1) else null }
        assertThat(vm.state.value.restoreChannel).isEqualTo(5)

        vm.selectGroup(GroupKey(2, 0))
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(2, 0))
        assertThat(vm.state.value.restoreChannel).isNull()
    }

    @Test
    fun `a group chosen before the restore lookup finishes wins`() = runTest {
        val lookup = CompletableDeferred<LastChannel?>()
        val vm = vm(FakeSource(listOf(first, second))) { lookup.await() }
        vm.selectGroup(GroupKey(2, 1))
        lookup.complete(LastChannel(ordinal = 1, groupOrdinal = 0))
        runCurrent()
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(2, 1))
        assertThat(vm.state.value.restoreChannel).isNull()
    }

    @Test
    fun `the selection survives a sync and falls back when its group disappears`() = runTest {
        val source = FakeSource(listOf(first, second))
        val vm = vm(source)
        vm.selectGroup(GroupKey(2, 1))
        source.sectionsFlow.value = listOf(second, first)
        runCurrent()
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(2, 1))

        source.sectionsFlow.value = listOf(first) // locked or removed
        runCurrent()
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(1, 0))
    }

    @Test
    fun `no playlists show the empty state and a load failure offers retry`() = runTest {
        assertThat(vm(FakeSource(emptyList())).state.value.sections).isInstanceOf(UiState.Empty::class.java)
        val failing = object : LiveBrowseSource {
            override fun sections(): Flow<List<PlaylistSection>> = flow { throw IllegalStateException("provider http://example.invalid/u/p") }
            override fun channels(group: GroupKey): Flow<List<ChannelRow>> = flowOf(emptyList())
        }
        val error = vm(failing).state.value.sections as UiState.Error
        assertThat(error.message).isEqualTo("failed")
    }
}

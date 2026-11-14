package app.playlistplayer.ui.tv.livetv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import app.playlistplayer.ui.common.live.LiveBrowseText
import app.playlistplayer.ui.common.live.LiveTvViewModel
import app.playlistplayer.ui.mobile.epg.GuideFixture
import app.playlistplayer.ui.tv.epg.EpgTags
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.playlistplayer.feature.live.ChannelHistoryState
import app.playlistplayer.feature.live.ChannelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * TV Live TV (Robolectric + Compose): group rail beside the selected group's guide grid over the
 * shared fixtures. tv-material surfaces react to D-pad keys, not touch, so clicks invoke the
 * OnClick semantics action (as OK does).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h720dp-land")
class LiveTvScreenTest {

    private val f = GuideFixture()
    private val watched = mutableListOf<LiveChannel>()
    private val played = mutableListOf<CatchupRequest>()
    private val text = LiveBrowseText(noPlaylists = "No playlists", emptyGroup = "Empty group", loadFailed = "Failed")

    @get:Rule
    val compose = createComposeRule()

    private class Sections(private val sections: List<PlaylistSection>) : LiveBrowseSource {
        override fun sections(): Flow<List<PlaylistSection>> = flowOf(sections)
        override fun channels(group: GroupKey): Flow<List<ChannelRow>> = flowOf(emptyList())
    }

    private val history = MutableStateFlow(ChannelHistoryState())
    private val streamActive = MutableStateFlow(false)
    private val fullscreen = mutableListOf<ChannelRef>()

    private fun show(): LiveTvViewModel {
        val vm = LiveTvViewModel(Sections(f.sections), { null }, text, history = history, streamActive = streamActive)
        compose.setContent {
            TvTheme {
                LiveTvScreen(
                    vm,
                    f.dependencies,
                    onPlayChannel = { watched += it },
                    onPlayCatchup = { played += it },
                    // Stands in for the shared player's video surface.
                    preview = { modifier -> Box(modifier.testTag(FAKE_VIDEO)) },
                    onFullscreen = { fullscreen += it },
                )
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasClickAction() and matcher, useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun clickText(text: String) = click(hasAnyDescendant(hasText(text)))

    private fun clickTag(tag: String) = click(hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag)))

    @Test
    fun `opens on the first group's grid with every channel listed`() {
        val vm = show()
        assertThat(vm.state.value.selectedGroup).isEqualTo(f.group)
        assertThat(exists(hasText("Main · 🇬🇧 UK | Entertainment"))).isTrue()
        listOf(f.bbcOne, f.bbcTwo, f.itv).forEach { assertThat(exists(hasTestTag(EpgTags.channel(it.ordinal)))).isTrue() }
        // A channel without programmes is still listed.
        assertThat(exists(hasText("No guide data"))).isTrue()
    }

    @Test
    fun `the group tree is always visible and selecting a group shows its grid`() {
        val vm = show()
        assertThat(exists(hasTestTag(LiveTvTags.GROUPS_PANEL))).isTrue()
        vm.openGroupsPanel()
        compose.waitForIdle()
        assertThat(exists(hasTestTag(LiveTvTags.GROUPS_PANEL))).isTrue()
        clickText("Kids")
        assertThat(vm.state.value.groupsPanelOpen).isFalse()
        assertThat(vm.state.value.selectedGroup).isEqualTo(GroupKey(2, 0))
        assertThat(exists(hasText("Family · Kids"))).isTrue()
        assertThat(exists(hasText("This group has no channels."))).isTrue()
        assertThat(exists(hasTestTag(EpgTags.channel(f.bbcOne.ordinal)))).isFalse()
    }

    @Test
    fun `OK on a channel name plays the channel live`() {
        show()
        clickTag(EpgTags.channel(f.bbcTwo.ordinal))
        assertThat(watched).containsExactly(f.bbcTwo)
        assertThat(played).isEmpty()
    }

    @Test
    fun `OK on a no guide data row plays the channel live`() {
        show()
        clickText("No guide data")
        assertThat(watched).containsExactly(f.itv)
    }

    @Test
    fun `programmes keep the guide routing`() {
        show()
        // Eligible past programme → catch-up player.
        clickText("Six O'Clock News")
        assertThat(played.single().programmeId).isEqualTo(1L)
        assertThat(watched).isEmpty()

        // Airing programme → Watch live / Restart; Watch live opens the live player.
        clickText("The Celebrity Traitors")
        assertThat(compose.onAllNodesWithText("Watch live").fetchSemanticsNodes()).isNotEmpty()
        clickText("Watch live")
        assertThat(watched).containsExactly(f.bbcOne)

        // Future programme → details only.
        clickText("Question Time")
        assertThat(compose.onAllNodesWithText("Question Time").fetchSemanticsNodes().size).isAtLeast(2)
        assertThat(played).hasSize(1)
        assertThat(watched).hasSize(1)
    }

    @Test
    fun `the details area shows the focused programme`() {
        val vm = show()
        val inDetails = hasAnyAncestor(hasTestTag(LiveTvTags.DETAILS))
        // Opens on the airing programme of the first channel.
        assertThat(exists(hasText("The Celebrity Traitors") and inDetails)).isTrue()
        assertThat(exists(hasText(f.bbcOne.name) and inDetails)).isTrue()
        assertThat(vm.focused.value?.channel).isEqualTo(f.bbcOne)
    }

    @Test
    fun `the preview renders the shared player only while a channel plays and goes fullscreen`() {
        val vm = show()
        assertThat(exists(hasTestTag(FAKE_VIDEO))).isFalse()
        assertThat(exists(hasTestTag(LiveTvTags.LAST_CHANNEL))).isFalse()

        history.value = ChannelHistoryState(ChannelRef(f.bbcTwo.playlistId, f.bbcTwo.ordinal), ChannelRef(f.bbcOne.playlistId, f.bbcOne.ordinal))
        streamActive.value = true
        compose.waitForIdle()
        assertThat(vm.state.value.playing).isEqualTo(ChannelRef(f.bbcTwo.playlistId, f.bbcTwo.ordinal))
        assertThat(exists(hasTestTag(FAKE_VIDEO))).isTrue()

        click(hasTestTag(LiveTvTags.PREVIEW) or hasAnyAncestor(hasTestTag(LiveTvTags.PREVIEW)) and hasAnyDescendant(hasTestTag(FAKE_VIDEO)))
        assertThat(fullscreen).containsExactly(ChannelRef(f.bbcTwo.playlistId, f.bbcTwo.ordinal))
        clickTag(LiveTvTags.LAST_CHANNEL)
        assertThat(fullscreen.last()).isEqualTo(ChannelRef(f.bbcOne.playlistId, f.bbcOne.ordinal))
        assertThat(watched).isEmpty()
    }

    private companion object {
        const val FAKE_VIDEO = "test.video"
    }
}

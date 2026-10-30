package app.playlistplayer.ui.mobile.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.feature.onboarding.OnboardingProgress
import app.playlistplayer.feature.onboarding.OnboardingResult
import app.playlistplayer.feature.onboarding.PlaylistOnboarder
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import app.playlistplayer.ui.mobile.MobileRoot
import app.playlistplayer.ui.mobile.MobileRootDependencies
import app.playlistplayer.ui.mobile.MobileTheme
import app.playlistplayer.ui.tv.TvRootDependencies
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Composition smoke tests for the phone Guide (Robolectric + Compose test rule): the screen
 * composes over fake sources, the shared ViewModel loads the group's rows,
 * its events reach the host callbacks, and the airing / details bottom sheets compose.
 */
@RunWith(AndroidJUnit4::class)
class MobileGuideSmokeTest {

    private val f = GuideFixture()
    private val played = mutableListOf<CatchupRequest>()
    private val watched = mutableListOf<LiveChannel>()

    @get:Rule
    val compose = createComposeRule()

    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private var contentKey by mutableIntStateOf(0)
    private var installed = false

    /** Shows [body], replacing earlier content (the rule allows one `setContent` per test). */
    private fun show(body: @Composable () -> Unit) {
        content.value = body
        contentKey++
        if (!installed) {
            installed = true
            compose.setContent { key(contentKey) { content.value?.invoke() } }
        }
    }

    /** Waits until composition, effects and the main looper are idle (infinite animations are settled by the rule). */
    private fun idle() = compose.waitForIdle()

    private fun assertComposed() {
        compose.onRoot().assertExists()
    }

    private fun gridViewModel() = EpgGridViewModel(
        f.group, f.channels, f.grid, f.coordinator, clock = f.clock, zone = f.zone, windowSlots = GuideGeometry.PHONE_WINDOW_SLOTS,
    )

    @Test
    fun `guide screen composes with a group picker and the selected group's grid`() {
        show {
            MobileTheme {
                MobileGuideScreen(flowOf(f.sections), f.dependencies, onPlayCatchup = { played += it }, onWatchLive = { watched += it })
            }
        }
        idle()
        assertComposed()
        // The first group of the first section was loaded through the shared ViewModel.
        assertThat(f.grid.calls).isNotEmpty()
    }

    @Test
    fun `guide screen composes while sections load, fail or are empty`() {
        listOf<Flow<List<PlaylistSection>>>(emptyFlow(), flow { throw IllegalStateException("boom") }, flowOf(emptyList())).forEach { sections ->
            show { MobileTheme { MobileGuideScreen(sections, f.dependencies, onPlayCatchup = {}, onWatchLive = {}) } }
            idle()
            assertComposed()
        }
    }

    @Test
    fun `grid events reach the host and the sheets compose`() {
        val vm = gridViewModel()
        show { MobileTheme { MobileGuideGrid(vm, onPlayCatchup = { played += it }, onWatchLive = { watched += it }) } }
        idle()
        val rows = vm.state.value.rows
        assertThat(rows.map { it.channel }).containsExactly(f.bbcOne, f.bbcTwo, f.itv).inOrder()

        // Past programme on catch-up → the host opens the catch-up player.
        vm.handle(GuideTap.Programme(10, 1))
        idle()
        assertThat(played.single().programmeId).isEqualTo(1L)

        // Airing programme → Watch live / Restart sheet; Watch live reaches the host.
        vm.handle(GuideTap.Programme(10, 2))
        idle()
        assertThat(vm.state.value.airingChoice).isNotNull()
        vm.chooseWatchLive()
        idle()
        assertThat(watched.single()).isEqualTo(f.bbcOne)

        // Details sheet.
        vm.handle(GuideTap.Details(10, 3))
        idle()
        assertThat(vm.state.value.details).isNotNull()
        vm.dismissDetails()
        idle()

        // "No guide data" row → watch live.
        vm.handle(GuideTap.Channel(13))
        idle()
        assertThat(watched.last()).isEqualTo(f.itv)
        assertComposed()
    }

    private object NoOnboarder : PlaylistOnboarder {
        override val progress: Flow<OnboardingProgress> = emptyFlow()
        override suspend fun addPlaylist(name: String, source: SourceInput, xtreamEnabled: Boolean): OnboardingResult =
            OnboardingResult.Success(1, xtreamEnabled = false)
    }

    private inner class Sections : LiveBrowseSource {
        override fun sections(): Flow<List<PlaylistSection>> = flowOf(f.sections)
        override fun channels(group: GroupKey): Flow<List<ChannelRow>> = flowOf(emptyList())
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp-land")
    fun `phone root composes with guide dependencies`() {
        val shared = TvRootDependencies(onboarder = NoOnboarder, isXtreamShaped = { false }, liveBrowseSource = Sections(), hasPlaylists = flowOf(true))
        show { MobileRoot(MobileRootDependencies(shared, guide = f.dependencies)) }
        idle()
        assertComposed()
    }

}

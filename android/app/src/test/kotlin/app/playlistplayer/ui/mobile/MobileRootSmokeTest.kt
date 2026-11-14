package app.playlistplayer.ui.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.feature.onboarding.OnboardingProgress
import app.playlistplayer.feature.onboarding.OnboardingResult
import app.playlistplayer.feature.onboarding.PlaylistOnboarder
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import app.playlistplayer.ui.tv.TvRootDependencies
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Composition smoke tests (Robolectric + Compose test rule): the phone root composes without
 * dependencies, on first run, and in a wide (rail) window.
 */
@RunWith(AndroidJUnit4::class)
class MobileRootSmokeTest {

    private object NoOnboarder : PlaylistOnboarder {
        override val progress: Flow<OnboardingProgress> = emptyFlow()
        override suspend fun addPlaylist(name: String, source: SourceInput, xtreamEnabled: Boolean, epgOverrideUrl: String?): OnboardingResult =
            OnboardingResult.Success(1, xtreamEnabled = false)
    }

    private object OneGroup : LiveBrowseSource {
        override fun sections(): Flow<List<PlaylistSection>> =
            flowOf(listOf(PlaylistSection(1, "Main", listOf(GroupItem(1, 0, "🇬🇧 UK | News", 2)))))
        override fun channels(group: GroupKey): Flow<List<ChannelRow>> =
            flowOf(listOf(ChannelRow(1, 0, 1, "News One"), ChannelRow(1, 1, 2, "News Two")))
    }

    private fun shared(hasPlaylists: Boolean) = TvRootDependencies(
        onboarder = NoOnboarder,
        isXtreamShaped = { false },
        liveBrowseSource = OneGroup,
        hasPlaylists = flowOf(hasPlaylists),
    )

    @get:Rule
    val compose = createComposeRule()

    /** Composes the root and waits until it is idle (the rule's clock settles infinite animations). */
    private fun render(dependencies: MobileRootDependencies?) {
        compose.setContent { MobileRoot(dependencies) }
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }

    @Test
    fun `renders placeholders without dependencies`() {
        render(null)
    }

    @Test
    fun `renders first-run onboarding when there are no playlists`() {
        render(MobileRootDependencies(shared(hasPlaylists = false)))
    }

    @Test
    fun `renders the shell when playlists exist`() {
        render(MobileRootDependencies(shared(hasPlaylists = true)))
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp-land")
    fun `renders the rail layout on a wide window`() {
        render(MobileRootDependencies(shared(hasPlaylists = true)))
    }
}

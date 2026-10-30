package app.playlistplayer.ui.mobile

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Composition smoke tests (Robolectric, no Compose UI test dependency in `:app` yet): the phone
 * root composes without dependencies, on first run, and in a wide (rail) window.
 */
@RunWith(AndroidJUnit4::class)
class MobileRootSmokeTest {

    private object NoOnboarder : PlaylistOnboarder {
        override val progress: Flow<OnboardingProgress> = emptyFlow()
        override suspend fun addPlaylist(name: String, source: SourceInput, xtreamEnabled: Boolean): OnboardingResult =
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

    private fun compose(dependencies: MobileRootDependencies?): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { MobileRoot(dependencies) }
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun ComponentActivity.contentChildren(): Int =
        (findViewById<ViewGroup>(android.R.id.content)).childCount

    @Test
    fun `renders placeholders without dependencies`() {
        assertThat(compose(null).contentChildren()).isEqualTo(1)
    }

    @Test
    fun `renders first-run onboarding when there are no playlists`() {
        assertThat(compose(MobileRootDependencies(shared(hasPlaylists = false))).contentChildren()).isEqualTo(1)
    }

    @Test
    fun `renders the shell when playlists exist`() {
        assertThat(compose(MobileRootDependencies(shared(hasPlaylists = true))).contentChildren()).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp-land")
    fun `renders the rail layout on a wide window`() {
        assertThat(compose(MobileRootDependencies(shared(hasPlaylists = true))).contentChildren()).isEqualTo(1)
    }
}

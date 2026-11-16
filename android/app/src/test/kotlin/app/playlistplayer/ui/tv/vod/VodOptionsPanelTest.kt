package app.playlistplayer.ui.tv.vod

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.feature.settings.VideoScaling
import app.playlistplayer.feature.vod.TrackOption
import app.playlistplayer.feature.vod.TrackType
import app.playlistplayer.ui.common.VideoScalingControl
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h720dp-land")
class VodOptionsPanelTest {
    @get:Rule val compose = createComposeRule()

    private fun click(text: String) {
        compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun `menu opens only the chosen list and back returns to that menu item`() {
        var page by mutableStateOf<TrackMenuPage?>(null)
        var menuFocus by mutableStateOf(TrackMenuPage.PICTURE)
        var scaling by mutableStateOf(VideoScaling.FIT)
        var selectedAudio: String? = null
        var selectedSubtitle: String? = "unset"
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            BackHandler(enabled = page != null) { menuFocus = checkNotNull(page); page = null }
            TvTheme {
                TrackPanel(
                    audio = listOf(TrackOption("a", TrackType.AUDIO, "English", "en", selected = true)),
                    subtitles = listOf(TrackOption("s", TrackType.TEXT, "French", "fr")),
                    onAudio = { selectedAudio = it },
                    onSubtitle = { selectedSubtitle = it },
                    scaling = VideoScalingControl(scaling, { scaling = it }, {}),
                    page = page,
                    menuFocus = menuFocus,
                    onPage = { page = it; menuFocus = it },
                    onInteraction = {},
                    modifier = Modifier,
                )
            }
        }
        assertThat(compose.onAllNodes(hasText("Normal")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("English")).fetchSemanticsNodes()).isEmpty()
        val picture = compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("Picture")), useUnmergedTree = true).onFirst()
        picture.assertIsFocused()
        picture.performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithTag("vod.options").performScrollToNode(hasText("Zoom (fill, crop edges)"))
        click("Zoom (fill, crop edges)")
        assertThat(scaling).isEqualTo(VideoScaling.ZOOM)
        assertThat(compose.onAllNodes(hasText("English")).fetchSemanticsNodes()).isEmpty()
        compose.runOnIdle { back.onBackPressed() }
        compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("Picture")), useUnmergedTree = true).onFirst().assertIsFocused()
        click("Audio")
        click("English")
        assertThat(selectedAudio).isEqualTo("a")
        compose.runOnIdle { back.onBackPressed() }
        compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("Audio")), useUnmergedTree = true).onFirst().assertIsFocused()
        click("Subtitles")
        click("Off")
        assertThat(selectedSubtitle).isNull()
        assertThat(compose.onAllNodes(hasText("French")).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `selected track receives focus even when deep in a long list`() {
        compose.setContent {
            TvTheme {
                TrackPanel(
                    audio = (0..20).map { TrackOption("$it", TrackType.AUDIO, "Audio $it", "en", selected = it == 20) },
                    subtitles = emptyList(),
                    onAudio = {},
                    onSubtitle = {},
                    scaling = VideoScalingControl(VideoScaling.FIT, {}, {}),
                    page = TrackMenuPage.AUDIO,
                    menuFocus = TrackMenuPage.AUDIO,
                    onPage = {},
                    onInteraction = {},
                    modifier = Modifier,
                )
            }
        }
        compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("Audio 20")), useUnmergedTree = true)
            .onFirst().assertIsFocused()
    }

    @Test
    fun `inactivity waits 30 seconds and restarts on interaction`() = runTest {
        var dismissals = 0
        val timer = launch { awaitVodControlsTimeout { dismissals++ } }
        runCurrent()
        advanceTimeBy(29_000)
        assertThat(dismissals).isEqualTo(0)
        timer.cancel()
        launch { awaitVodControlsTimeout { dismissals++ } }
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertThat(dismissals).isEqualTo(0)
        advanceTimeBy(1)
        runCurrent()
        assertThat(dismissals).isEqualTo(1)
    }
}

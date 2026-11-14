package app.playlistplayer.ui.tv.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.feature.vod.FilterChipModel
import app.playlistplayer.feature.vod.TitleDetailUiState
import app.playlistplayer.feature.vod.VersionRow
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h720dp-land")
class TitleDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private val versions = listOf(
        VersionRow("4k", "English 4K", "en", QualityTier.UHD, false, true, true),
        VersionRow("hd", "English HD", "en", QualityTier.FHD, false, false, false),
    )
    private var played: Boolean? = null
    private var picked: String? = null

    private fun show(resumePosition: Long? = null) {
        compose.setContent {
            var state by remember {
                mutableStateOf(
                    TitleDetailUiState(
                        loading = false,
                        title = "Film",
                        resumePositionMs = resumePosition,
                        versions = versions,
                        selectedVersionId = "4k",
                        languageChips = listOf(FilterChipModel("en", false), FilterChipModel("fr", false)),
                    ),
                )
            }
            TvTheme {
                TitleDetailContent(
                    state = state,
                    onPlay = { played = it },
                    onFavourite = {},
                    onRetryDetails = {},
                    onLanguage = {},
                    onQuality = {},
                    onVersion = { picked = it; state = state.copy(selectedVersionId = it) },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun control(text: String) = compose.onAllNodes(
        hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text))),
        useUnmergedTree = true,
    ).onFirst()

    private fun click(text: String) {
        control(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun `movie playback and versions panel are navigable by remote`() {
        show()
        assertThat(compose.onAllNodes(hasText("Selected version: English 4K")).fetchSemanticsNodes()).isNotEmpty()
        control("Play").performSemanticsAction(SemanticsActions.RequestFocus)
        control("Play").performKeyInput { pressKey(Key.DirectionRight) }
        control("Add to favourites").assertIsFocused()
        control("Add to favourites").performKeyInput { pressKey(Key.DirectionRight) }
        control("Versions & filters").assertIsFocused()
        click("Versions & filters")
        click("English HD")
        assertThat(picked).isEqualTo("hd")
        click("Close")
        control("Versions & filters").assertIsFocused()
        assertThat(compose.onAllNodes(hasText("Selected version: English HD")).fetchSemanticsNodes()).isNotEmpty()
        click("Play")
        assertThat(played).isFalse()
    }

    @Test
    fun `resume and start over retain their playback actions`() {
        show(resumePosition = 60000)
        click("Start over")
        assertThat(played).isFalse()
        click("Resume  1:00")
        assertThat(played).isTrue()
    }
}

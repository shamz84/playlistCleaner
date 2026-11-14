package app.playlistplayer.ui.tv.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.playlistplayer.feature.vod.EpisodeKey
import app.playlistplayer.feature.vod.EpisodeRow
import app.playlistplayer.feature.vod.FilterChipModel
import app.playlistplayer.feature.vod.SeasonTab
import app.playlistplayer.feature.vod.SeriesUiState
import app.playlistplayer.feature.vod.VersionRow
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h720dp-land")
class SeriesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val hd = VersionRow("hd", "English HD", "en", QualityTier.FHD, false, false, true)
    private var selectedSeason = 1
    private var chosenVersion: String? = null
    private var chosenLanguage: String? = null
    private var played: EpisodeKey? = null

    private fun state(season: Int) = SeriesUiState(
        loading = false,
        title = "Series",
        seasons = listOf(SeasonTab(1, null, season == 1), SeasonTab(2, null, season == 2)),
        episodes = listOf(EpisodeRow(EpisodeKey(season, 1), "First", null, null, 3600, listOf(hd), "hd", null, false)),
        versions = listOf(hd),
        languageChips = listOf(FilterChipModel("en", false), FilterChipModel("fr", false)),
    )

    private fun show() {
        compose.setContent {
            var current by androidx.compose.runtime.remember { mutableStateOf(state(1)) }
            TvTheme {
                SeriesContent(
                    state = current,
                    onFavourite = {},
                    onLanguage = { chosenLanguage = it },
                    onQuality = {},
                    onVersion = { chosenVersion = it },
                    onSeason = { selectedSeason = it; current = state(it) },
                    onRetry = {},
                    onEpisode = { played = it },
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
    fun `season two and episodes are reachable without opening filters`() {
        show()
        control("Season 1").performSemanticsAction(SemanticsActions.RequestFocus)
        control("Season 1").performKeyInput { pressKey(Key.DirectionRight) }
        control("Season 2").assertIsFocused()
        click("Season 2")
        assertThat(selectedSeason).isEqualTo(2)
        control("Season 2").performKeyInput { pressKey(Key.DirectionDown) }
        control("Episode 1  ·  First").assertIsFocused()
        click("Episode 1  ·  First")
        assertThat(played).isEqualTo(EpisodeKey(2, 1))
        assertThat(chosenLanguage).isNull()
    }

    @Test
    fun `versions panel opens changes selection and returns to season screen`() {
        show()
        click("Versions & filters")
        click("English HD")
        assertThat(chosenVersion).isEqualTo("hd")
        click("Close")
        control("Versions & filters").assertIsFocused()
        click("Season 2")
        assertThat(selectedSeason).isEqualTo(2)
    }
}

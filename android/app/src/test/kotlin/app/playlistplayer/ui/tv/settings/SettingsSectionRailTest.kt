package app.playlistplayer.ui.tv.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.BuildConfig
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h720dp-land")
class SettingsSectionRailTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `version appears below App and is not an interactive setting`() {
        var selected = SettingsSection.Playlists
        compose.setContent {
            TvTheme { SettingsSectionRail(SettingsSection.Playlists) { selected = it } }
        }
        val app = compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("App")), useUnmergedTree = true).onFirst()
        val version = compose.onNodeWithText("App Version: ${BuildConfig.VERSION_NAME}", useUnmergedTree = true)
        version.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        version.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        assertThat(version.getUnclippedBoundsInRoot().top).isAtLeast(app.getUnclippedBoundsInRoot().bottom)
        app.performSemanticsAction(SemanticsActions.OnClick)
        assertThat(selected).isEqualTo(SettingsSection.App)
    }
}

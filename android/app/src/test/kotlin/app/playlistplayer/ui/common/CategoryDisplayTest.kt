package app.playlistplayer.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.ui.mobile.MobileTheme
import app.playlistplayer.ui.mobile.live.GroupRow
import app.playlistplayer.ui.tv.live.GroupRail
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryDisplayTest {
    @get:Rule val compose = createComposeRule()
    private val group = GroupItem(1, 2, "Entertainment", 12345)

    @Test
    fun `tv category omits visible count and remains selectable`() {
        var opened: GroupKey? = null
        compose.setContent {
            TvTheme {
                GroupRail(
                    sections = listOf(PlaylistSection(1, "Playlist", listOf(group))),
                    selected = null, onFocusGroup = {}, onOpenGroup = { opened = it }, compact = true,
                )
            }
        }
        compose.onNodeWithText("12345", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Entertainment", useUnmergedTree = true).assertExists()
        compose.onAllNodes(
            hasClickAction() and hasContentDescription("Entertainment, 12345 channels"),
            useUnmergedTree = true,
        ).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertThat(opened).isEqualTo(group.key) }
    }

    @Test
    fun `phone category omits visible count and remains selectable`() {
        var opened = false
        compose.setContent {
            MobileTheme { GroupRow(group, selected = false, onOpen = { opened = true }) }
        }
        compose.onNodeWithText("12345", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Entertainment").assertExists().performClick()
        compose.runOnIdle { assertThat(opened).isTrue() }
    }
}

package app.playlistplayer.ui.tv.components

import android.os.Looper
import android.view.ViewConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class TvDialogBackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `dialog intercepts short and long Back separately`() {
        var dismisses = 0
        var exits = 0
        compose.setContent {
            CompositionLocalProvider(LocalTvLongBack provides { exits++ }) {
                TvTheme {
                    TvDialog(onDismissRequest = { dismisses++ }) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TvButton(text = "Control", onClick = {}, modifier = Modifier.focusRequester(focus))
                    }
                }
            }
        }
        compose.onNodeWithText("Control").performKeyInput { pressKey(Key.Back) }
        compose.runOnIdle {
            assertThat(dismisses).isEqualTo(1)
            assertThat(exits).isEqualTo(0)
        }
        compose.onNodeWithText("Control").performKeyInput { keyDown(Key.Back) }
        compose.runOnIdle {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()))
        }
        compose.onNodeWithText("Control").performKeyInput { keyUp(Key.Back) }
        compose.runOnIdle {
            assertThat(dismisses).isEqualTo(1)
            assertThat(exits).isEqualTo(1)
        }
    }
}

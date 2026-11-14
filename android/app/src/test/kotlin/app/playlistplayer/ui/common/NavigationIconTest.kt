package app.playlistplayer.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.feature.shell.Destination
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationIconTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `all destinations use different loadable vector icons`() {
        assertThat(Destination.entries.map { it.iconRes() }.distinct()).hasSize(Destination.entries.size)
        compose.setContent {
            Row { Destination.entries.forEach { NavigationIcon(it, Color.White) } }
        }
        compose.waitForIdle()
    }
}

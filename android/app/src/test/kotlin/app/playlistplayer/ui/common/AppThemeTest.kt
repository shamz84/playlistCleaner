package app.playlistplayer.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.feature.settings.AppTheme
import app.playlistplayer.ui.mobile.MobileTheme
import app.playlistplayer.ui.tv.theme.TvTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `both material themes and shared tokens update when the palette changes`() {
        val selected = mutableStateOf(AppTheme.MIDNIGHT_BLUE)
        var tvPrimary = Color.Unspecified
        var mobilePrimary = Color.Unspecified
        var background = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalAppPalette provides paletteFor(selected.value)) {
                TvTheme {
                    tvPrimary = androidx.tv.material3.MaterialTheme.colorScheme.primary
                    background = UiTokens.Background
                }
                MobileTheme {
                    mobilePrimary = androidx.compose.material3.MaterialTheme.colorScheme.primary
                }
            }
        }
        compose.runOnIdle {
            assertThat(tvPrimary).isEqualTo(MidnightPalette.accent)
            assertThat(mobilePrimary).isEqualTo(MidnightPalette.accent)
            assertThat(background).isEqualTo(MidnightPalette.background)
            selected.value = AppTheme.CINEMA_EMBER
        }
        compose.runOnIdle {
            assertThat(tvPrimary).isEqualTo(EmberPalette.accent)
            assertThat(mobilePrimary).isEqualTo(EmberPalette.accent)
            assertThat(background).isEqualTo(EmberPalette.background)
            selected.value = AppTheme.GRAPHITE
        }
        compose.runOnIdle {
            assertThat(tvPrimary).isEqualTo(GraphitePalette.accent)
            assertThat(mobilePrimary).isEqualTo(GraphitePalette.accent)
            assertThat(background).isEqualTo(GraphitePalette.background)
        }
    }
}

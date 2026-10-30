package app.playlistplayer.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens shared by the TV and mobile trees. The TV theme (`ui.tv.theme.TvTheme`) maps them
 * onto tv-material; mobile maps them onto material3.
 */
object UiTokens {
    // Palette (dark only; the app is a media app used in dim rooms).
    val Background = Color(0xFF101820) // matches @color/brand_background (window background)
    val Surface = Color(0xFF18232E)
    val SurfaceVariant = Color(0xFF223140)
    val SurfaceFocused = Color(0xFF2C3F52)
    val Accent = Color(0xFF2F80ED) // matches @color/brand_accent
    val OnAccent = Color(0xFFFFFFFF)
    val OnBackground = Color(0xFFECEFF3)
    val OnSurfaceMuted = Color(0xFFA9B4C0)
    val FocusBorder = Color(0xFFFFFFFF)
    val Error = Color(0xFFFF8A80)
    val OnError = Color(0xFF3B0A06)
    val Success = Color(0xFF7BD88F)
    val Warning = Color(0xFFFFD166)

    // TV overscan-safe insets: 5% of a 960x540 dp screen horizontally (48 dp) and vertically (27 dp).
    val OverscanHorizontal = 48.dp
    val OverscanVertical = 27.dp

    // Focus indication: scale up and draw a solid border so focus is visible from 3 m away.
    const val FocusScale = 1.06f
    val FocusBorderWidth = 3.dp
    val CornerRadius = 12.dp

    // Spacing.
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp
    val SpaceL = 24.dp
    val SpaceXl = 32.dp
}

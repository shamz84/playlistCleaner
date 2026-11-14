package app.playlistplayer.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.playlistplayer.feature.settings.AppTheme

data class AppPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceFocused: Color,
    val accent: Color,
    val onAccent: Color,
    val onBackground: Color,
    val onSurfaceMuted: Color,
    val guidePast: Color,
    val guideTree: Color,
)

val MidnightPalette = AppPalette(
    Color(0xFF101820), Color(0xFF18232E), Color(0xFF223140), Color(0xFF2C3F52),
    Color(0xFF2F80ED), Color.White, Color(0xFFECEFF3), Color(0xFFA9B4C0),
    Color(0xFF141D26), Color(0xF2101820),
)

val EmberPalette = AppPalette(
    Color(0xFF151311), Color(0xFF211C19), Color(0xFF302825), Color(0xFF70412A),
    Color(0xFFEEA05A), Color(0xFF241308), Color(0xFFFFF3E9), Color(0xFFD4C1B5),
    Color(0xFF1A1614), Color(0xFF211C19),
)

val GraphitePalette = AppPalette(
    Color(0xFF101113), Color(0xFF1B1D20), Color(0xFF2A2D32), Color(0xFF526174),
    Color(0xFFE0E7F2), Color(0xFF101113), Color(0xFFF4F6F9), Color(0xFFB1B8C2),
    Color(0xFF17191C), Color(0xFF1B1D20),
)

fun paletteFor(theme: AppTheme): AppPalette = when (theme) {
    AppTheme.MIDNIGHT_BLUE -> MidnightPalette
    AppTheme.CINEMA_EMBER -> EmberPalette
    AppTheme.GRAPHITE -> GraphitePalette
}

val LocalAppPalette = staticCompositionLocalOf { MidnightPalette }

/**
 * Design tokens shared by the TV and mobile trees. The TV theme (`ui.tv.theme.TvTheme`) maps them
 * onto tv-material; mobile maps them onto material3.
 */
object UiTokens {
    // Palette (dark only; the app is a media app used in dim rooms).
    val Background: Color @Composable get() = LocalAppPalette.current.background
    val Surface: Color @Composable get() = LocalAppPalette.current.surface
    val SurfaceVariant: Color @Composable get() = LocalAppPalette.current.surfaceVariant
    val SurfaceFocused: Color @Composable get() = LocalAppPalette.current.surfaceFocused
    val Accent: Color @Composable get() = LocalAppPalette.current.accent
    val OnAccent: Color @Composable get() = LocalAppPalette.current.onAccent
    val OnBackground: Color @Composable get() = LocalAppPalette.current.onBackground
    val OnSurfaceMuted: Color @Composable get() = LocalAppPalette.current.onSurfaceMuted
    val GuidePast: Color @Composable get() = LocalAppPalette.current.guidePast
    val GuideTree: Color @Composable get() = LocalAppPalette.current.guideTree
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

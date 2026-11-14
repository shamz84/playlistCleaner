package app.playlistplayer.ui.tv.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import app.playlistplayer.ui.common.UiTokens

@Composable
private fun tvColors() = darkColorScheme(
    primary = UiTokens.Accent,
    onPrimary = UiTokens.OnAccent,
    secondary = UiTokens.SurfaceFocused,
    onSecondary = UiTokens.OnBackground,
    background = UiTokens.Background,
    onBackground = UiTokens.OnBackground,
    surface = UiTokens.Surface,
    onSurface = UiTokens.OnBackground,
    surfaceVariant = UiTokens.SurfaceVariant,
    onSurfaceVariant = UiTokens.OnSurfaceMuted,
    error = UiTokens.Error,
    onError = UiTokens.OnError,
    border = UiTokens.FocusBorder,
)

/**
 * Compact 10-foot type scale (U21, R37), TiviMate density: Fire TV reports 960x540 dp at 1080p and
 * at 4K, so the old 18-22 sp body text looked oversized. Running text is 13-14 sp, labels and the
 * guide ruler 11-12 sp; titles step up by about 2 sp. Every TV screen (shell rail, Live TV, VOD
 * cards, settings) takes it through [TvTheme].
 */
private val TvTypography: Typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = 30.sp, lineHeight = 36.sp),
        headlineLarge = headlineLarge.copy(fontSize = 26.sp, lineHeight = 32.sp),
        headlineMedium = headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = headlineSmall.copy(fontSize = 19.sp, lineHeight = 24.sp),
        titleLarge = titleLarge.copy(fontSize = 17.sp, lineHeight = 22.sp),
        titleMedium = titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
        bodyLarge = bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 17.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
}

/** Dark TV theme (tv-material) over the shared [UiTokens]. */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = tvColors(), typography = TvTypography, content = content)
}

/**
 * Layout debugging: each Live TV section gets a distinct tint so a misbehaving area is easy to
 * name. Set [ENABLED] to false to remove every tint.
 */
object TvDebugColors {
    const val ENABLED = false
    val Rail = Color(0x66E53935)
    val Tree = Color(0x66FB8C00)
    val Details = Color(0x661E88E5)
    val Preview = Color(0x6643A047)
    val Ruler = Color(0x66FDD835)
    val ChannelColumn = Color(0x6600ACC1)
    val Timeline = Color(0x668E24AA)
}

fun Modifier.debugTint(color: Color): Modifier = if (TvDebugColors.ENABLED) background(color) else this

/** Shared focus and layout values for the TV tree. */
object TvDimens {
    /** Overscan-safe screen padding (≥ 48 dp horizontal, ≥ 27 dp vertical). */
    val ScreenPadding = PaddingValues(horizontal = UiTokens.OverscanHorizontal, vertical = UiTokens.OverscanVertical)

    val Shape = RoundedCornerShape(UiTokens.CornerRadius)

    /** Solid border drawn around the focused element, together with [UiTokens.FocusScale]. */
    val FocusBorder = Border(
        border = BorderStroke(UiTokens.FocusBorderWidth, UiTokens.FocusBorder),
        shape = RoundedCornerShape(UiTokens.CornerRadius),
    )
}

/** Compact spacing tokens of the Live TV guide (U21, R37); tune on the device at 1080p and 4K. */
object TvDensity {
    /** Share of the screen height taken by the details + preview area above the guide. */
    const val DetailsFraction = 0.27f

    /** Share of the guide width taken by the logo / number / name column. */
    const val ChannelColumnFraction = 0.22f

    val GuideRowHeight = 36.dp
    val GuideRowGap = 2.dp
    val RulerHeight = 20.dp
    val CellPadding = 6.dp
    val LogoSize = 28.dp
    val GroupsPanelWidth = 128.dp
    val FocusBorderWidth = 2.dp

    /** Tighter than [TvDimens.ScreenPadding] (still overscan-safe on Fire TV, which reports no overscan). */
    val ScreenPadding = PaddingValues(start = 8.dp, end = 28.dp, top = 14.dp, bottom = 24.dp)

    /** Thinner focus border for dense guide cells (the cell colour changes too). */
    val CellFocusBorder = Border(
        border = BorderStroke(FocusBorderWidth, UiTokens.FocusBorder),
        shape = RoundedCornerShape(4.dp),
    )

    val CellShape = RoundedCornerShape(4.dp)
}

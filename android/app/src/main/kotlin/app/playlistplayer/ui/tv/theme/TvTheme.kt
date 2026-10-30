package app.playlistplayer.ui.tv.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import app.playlistplayer.ui.common.UiTokens

private val TvColors = darkColorScheme(
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
 * 10-foot type scale: tv-material defaults with body and label sizes raised so running text is at
 * least 18 sp (readable from about 3 m on a 1080p panel at 960x540 dp).
 */
private val TvTypography: Typography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontSize = 40.sp, lineHeight = 48.sp),
        headlineMedium = headlineMedium.copy(fontSize = 34.sp, lineHeight = 42.sp),
        headlineSmall = headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp),
        titleLarge = titleLarge.copy(fontSize = 26.sp, lineHeight = 34.sp),
        titleMedium = titleMedium.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
        bodyLarge = bodyLarge.copy(fontSize = 22.sp, lineHeight = 30.sp),
        bodyMedium = bodyMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
        bodySmall = bodySmall.copy(fontSize = 18.sp, lineHeight = 26.sp),
        labelLarge = labelLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
        labelMedium = labelMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
        labelSmall = labelSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
    )
}

/** Dark TV theme (tv-material) over the shared [UiTokens]. */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColors, typography = TvTypography, content = content)
}

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

package app.playlistplayer.ui.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.playlistplayer.ui.common.UiTokens

/** Material3 theme of the phone tree, mapped from the shared [UiTokens] (dark only, like TV). */
@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MobileColors, content = content)
}

private val MobileColors = darkColorScheme(
    primary = UiTokens.Accent,
    onPrimary = UiTokens.OnAccent,
    primaryContainer = UiTokens.SurfaceFocused,
    onPrimaryContainer = UiTokens.OnBackground,
    secondaryContainer = UiTokens.SurfaceFocused,
    onSecondaryContainer = UiTokens.OnBackground,
    background = UiTokens.Background,
    onBackground = UiTokens.OnBackground,
    surface = UiTokens.Background,
    onSurface = UiTokens.OnBackground,
    surfaceVariant = UiTokens.SurfaceVariant,
    onSurfaceVariant = UiTokens.OnSurfaceMuted,
    surfaceContainerLowest = UiTokens.Background,
    surfaceContainerLow = UiTokens.Surface,
    surfaceContainer = UiTokens.Surface,
    surfaceContainerHigh = UiTokens.SurfaceVariant,
    surfaceContainerHighest = UiTokens.SurfaceVariant,
    error = UiTokens.Error,
    onError = UiTokens.OnError,
)

/** Phone layout dimensions. */
object MobileDimens {
    /** Minimum touch target (Material / WCAG): every clickable is at least this tall and wide. */
    val MinTouchTarget = 48.dp
    val ScreenPadding = 16.dp
    val PosterWidth = 112.dp
    val DetailPosterWidth = 120.dp
}

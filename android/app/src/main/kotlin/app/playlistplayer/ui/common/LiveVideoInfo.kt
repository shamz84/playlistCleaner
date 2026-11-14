package app.playlistplayer.ui.common

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.playlistplayer.R
import app.playlistplayer.core.player.VideoFormatInfo
import java.text.NumberFormat
import java.util.Locale

data class LiveVideoInfo(val enabled: Boolean = false, val format: VideoFormatInfo? = null)

val LocalLiveVideoInfo = compositionLocalOf { LiveVideoInfo() }

internal fun videoInfoParts(format: VideoFormatInfo?, locale: Locale): Pair<String?, String?> {
    if (format == null) return null to null
    val resolution = if (format.width > 0 && format.height > 0) "${format.width} × ${format.height}" else null
    val fps = format.frameRate?.takeIf { it.isFinite() && it > 0 }?.let {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(it)
    }

    return resolution to fps
}

@Composable
fun liveVideoInfoLabel(): String? {
    val info = LocalLiveVideoInfo.current
    if (!info.enabled) return null
    val (resolution, fps) = videoInfoParts(info.format, Locale.getDefault())
    return listOfNotNull(resolution, fps?.let { stringResource(R.string.player_video_fps, it) })
        .joinToString(" · ").takeIf { it.isNotEmpty() }
}

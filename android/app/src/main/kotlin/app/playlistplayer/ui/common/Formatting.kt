package app.playlistplayer.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.feature.vod.UNKNOWN_LANGUAGE
import java.util.Date
import java.util.Locale

// Display helpers shared by the TV and phone trees (and the live player state).

/** Short quality badge text (`4K`, `FHD`, …), or null for unlabeled/low sources. */
fun QualityTier?.badge(): String? = when (this) {
    QualityTier.UHD_8K -> "8K"
    QualityTier.UHD -> "4K"
    QualityTier.RAW -> "RAW"
    QualityTier.FHD -> "FHD"
    QualityTier.HD -> "HD"
    QualityTier.SD -> "SD"
    QualityTier.UNLABELED, QualityTier.LOW_SOURCE, null -> null
}

/** Language chip / version label: the upper-case provider code, or "Unknown". */
@Composable
fun languageLabel(code: String?): String =
    if (code == null || code == UNKNOWN_LANGUAGE) stringResource(R.string.vod_language_unknown) else code.uppercase(Locale.ROOT)

/** Quality chip label for a [QualityTier] name, or "Unknown". */
@Composable
fun qualityLabel(key: String): String =
    QualityTier.entries.firstOrNull { it.name == key }?.badge() ?: stringResource(R.string.vod_language_unknown)

/** `h:mm:ss` / `m:ss` for positions and durations. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** Wall-clock time in the device's 12/24 h setting. */
@Composable
fun rememberTimeFormatter(): (Long) -> String {
    val context = LocalContext.current
    return remember(context) {
        val format = DateFormat.getTimeFormat(context)
        val formatter: (Long) -> String = { millis -> format.format(Date(millis)) }
        formatter
    }
}

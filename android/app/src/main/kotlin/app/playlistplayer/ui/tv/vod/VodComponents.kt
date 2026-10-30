package app.playlistplayer.ui.tv.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.feature.vod.FilterChipModel
import app.playlistplayer.feature.vod.UNKNOWN_LANGUAGE
import java.util.Locale

/** Short badge text for a quality tier ("4K", "FHD", …), or null for unlabeled. */
internal fun QualityTier.badge(): String? = when (this) {
    QualityTier.UHD_8K -> "8K"
    QualityTier.UHD -> "4K"
    QualityTier.RAW -> "RAW"
    QualityTier.FHD -> "FHD"
    QualityTier.HD -> "HD"
    QualityTier.SD -> "SD"
    QualityTier.UNLABELED, QualityTier.LOW_SOURCE -> null
}

@Composable
internal fun languageLabel(code: String?): String =
    if (code == null || code == UNKNOWN_LANGUAGE) stringResource(R.string.vod_language_unknown) else code.uppercase(Locale.ROOT)

@Composable
internal fun qualityLabel(key: String): String =
    QualityTier.entries.firstOrNull { it.name == key }?.badge() ?: stringResource(R.string.vod_language_unknown)

/** A labelled row of filter chips; selecting the active chip clears the filter. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ChipRow(
    title: String,
    chips: List<FilterChipModel>,
    label: @Composable (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.size < 2 && chips.none { it.selected }) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips, key = { it.key }) { chip ->
                FilterChip(selected = chip.selected, onClick = { onSelect(chip.key) }) {
                    Text(text = label(chip.key))
                }
            }
        }
    }
}

@Composable
internal fun CenteredMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier = modifier.padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

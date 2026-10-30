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


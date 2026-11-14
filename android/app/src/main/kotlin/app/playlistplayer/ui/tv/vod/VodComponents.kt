package app.playlistplayer.ui.tv.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.feature.vod.FilterChipModel
import app.playlistplayer.feature.vod.VersionRow
import app.playlistplayer.feature.vod.UNKNOWN_LANGUAGE
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.languageLabel
import app.playlistplayer.ui.common.qualityLabel
import app.playlistplayer.ui.tv.theme.TvDimens
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
internal fun VersionsAndFilters(
    languageChips: List<FilterChipModel>,
    qualityChips: List<FilterChipModel>,
    versions: List<VersionRow>,
    loading: Boolean,
    onLanguage: (String) -> Unit,
    onQuality: (String) -> Unit,
    onVersion: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    var restoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(open, restoreFocus) {
        if (!open && restoreFocus) {
            buttonFocus.requestFocus()
            restoreFocus = false
        }
    }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.focusRequester(buttonFocus)) {
        Text(stringResource(R.string.vod_versions_and_filters))
    }
    if (open) {
        val closeFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = { open = false; restoreFocus = true }) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 500.dp)
                    .background(UiTokens.Surface, TvDimens.Shape).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.vod_versions_and_filters), style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(
                            onClick = { open = false; restoreFocus = true },
                            modifier = Modifier.focusRequester(closeFocus),
                        ) {
                            Text(stringResource(R.string.epg_close))
                        }
                    }
                }
                item { ChipRow(stringResource(R.string.vod_filter_language), languageChips, { languageLabel(it) }, onLanguage) }
                item { ChipRow(stringResource(R.string.vod_filter_quality), qualityChips, { qualityLabel(it) }, onQuality) }
                item { Text(stringResource(R.string.vod_versions), style = MaterialTheme.typography.titleMedium) }
                if (versions.isEmpty()) {
                    item { Text(stringResource(if (loading) R.string.vod_loading else R.string.vod_no_matching_versions)) }
                }
                items(versions, key = { it.id }) { row -> VersionListItem(row, onClick = { onVersion(row.id) }) }
            }
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}

@Composable
internal fun CenteredMessage(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier = modifier.padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

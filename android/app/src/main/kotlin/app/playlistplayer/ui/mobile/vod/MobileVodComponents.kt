package app.playlistplayer.ui.mobile.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.playlistplayer.R
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.feature.vod.FilterChipModel
import app.playlistplayer.feature.vod.UNKNOWN_LANGUAGE
import app.playlistplayer.feature.vod.VersionRow
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import coil3.compose.AsyncImage
import java.util.Locale

/** Short badge text for a quality tier ("4K", "FHD", …), or null for unlabeled. Same labels as TV. */
internal fun QualityTier.mobileBadge(): String? = when (this) {
    QualityTier.UHD_8K -> "8K"
    QualityTier.UHD -> "4K"
    QualityTier.RAW -> "RAW"
    QualityTier.FHD -> "FHD"
    QualityTier.HD -> "HD"
    QualityTier.SD -> "SD"
    QualityTier.UNLABELED, QualityTier.LOW_SOURCE -> null
}

@Composable
internal fun languageText(code: String?): String =
    if (code == null || code == UNKNOWN_LANGUAGE) stringResource(R.string.vod_language_unknown) else code.uppercase(Locale.ROOT)

@Composable
internal fun qualityText(key: String): String =
    QualityTier.entries.firstOrNull { it.name == key }?.mobileBadge() ?: stringResource(R.string.vod_language_unknown)

/** "1:02:03" / "4:05". */
internal fun clockText(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** Poster with a dark placeholder while loading or when there is no artwork. Decorative. */
@Composable
internal fun Poster(url: String?, width: Dp, modifier: Modifier = Modifier, badge: String? = null) {
    Box(modifier = modifier.width(width).aspectRatio(2f / 3f).background(UiTokens.SurfaceVariant, MaterialTheme.shapes.small)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** A labelled row of filter chips; tapping the active chip clears the filter (R15). */
@Composable
internal fun MobileChipRow(
    title: String,
    chips: List<FilterChipModel>,
    label: @Composable (String) -> String,
    onSelect: (String) -> Unit,
) {
    if (chips.size < 2 && chips.none { it.selected }) return
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = UiTokens.OnSurfaceMuted, modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding))
        LazyRow(
            contentPadding = PaddingValues(horizontal = MobileDimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
        ) {
            items(chips, key = { it.key }) { chip ->
                // FilterChip enforces the 48 dp minimum interactive size.
                FilterChip(selected = chip.selected, onClick = { onSelect(chip.key) }, label = { Text(label(chip.key)) })
            }
        }
    }
}

/** One option in a bottom-sheet picker. */
internal data class SheetOption(val id: String, val label: String, val supporting: String?, val selected: Boolean)

/** Supporting line of a version row: language · quality · HDR · Default. */
@Composable
internal fun versionSupporting(row: VersionRow): String = listOfNotNull(
    languageText(row.language),
    row.quality.mobileBadge(),
    if (row.hdr) "HDR" else null,
    if (row.isDefault) stringResource(R.string.vod_version_default) else null,
).joinToString("  ·  ")

@Composable
internal fun VersionRow.toSheetOption(): SheetOption = SheetOption(id, label, versionSupporting(this), isSelected)

/**
 * A single-choice bottom sheet ("Versions", "Other versions"). Picking an option calls [onPick]
 * and dismisses; Back or a swipe down dismisses without a change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionsSheet(
    title: String,
    options: List<SheetOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    emptyText: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS).semantics { heading() },
                )
            }
            if (options.isEmpty() && emptyText != null) {
                item { Text(emptyText, modifier = Modifier.padding(MobileDimens.ScreenPadding), color = UiTokens.OnSurfaceMuted) }
            }
            items(options, key = { it.id }) { option ->
                ListItem(
                    leadingContent = { RadioButton(selected = option.selected, onClick = null) },
                    headlineContent = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = option.supporting?.let { { Text(it) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = option.selected, role = Role.RadioButton) {
                        onPick(option.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

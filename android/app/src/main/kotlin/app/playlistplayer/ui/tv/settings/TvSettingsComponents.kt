package app.playlistplayer.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.settings.SettingsText
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.theme.TvDimens
import kotlinx.coroutines.delay

/** Resolves a shared [SettingsText] (nested texts first). */
@Composable
internal fun SettingsText.resolve(): String {
    val resolved = args.map { if (it is SettingsText) it.resolve() else it }
    return stringResource(res, *resolved.toTypedArray())
}

@Composable
internal fun TvSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = UiTokens.Accent,
        modifier = modifier.padding(top = UiTokens.SpaceM, bottom = UiTokens.SpaceXs).semantics { heading() },
    )
}

@Composable
internal fun TvMutedText(text: String, modifier: Modifier = Modifier, color: Color = UiTokens.OnSurfaceMuted) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

/** A focusable settings row: title, optional supporting text and trailing value. */
@Composable
internal fun TvSettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val description = listOfNotNull(title, value, supporting).joinToString(". ")
    ListItem(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = trailing ?: value?.let { { Text(it, style = MaterialTheme.typography.labelLarge) } },
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder),
    )
}

/** A row whose click flips a switch (the switch is display-only; the row is the focus target). */
@Composable
internal fun TvToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val state = stringResource(if (checked) R.string.settings_on else R.string.settings_off)
    TvSettingRow(
        title = title,
        supporting = supporting,
        onClick = { onToggle(!checked) },
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = "$title. $state" },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** Value with Decrease / Increase buttons (D-pad left/right moves between them). */
@Composable
internal fun TvStepperRow(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = UiTokens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = UiTokens.OnBackground)
            supporting?.let { TvMutedText(it) }
        }
        TvButton(text = "−", onClick = onDecrease, contentDescription = stringResource(R.string.settings_decrease) + " " + title)
        Text(value, style = MaterialTheme.typography.titleMedium, color = UiTokens.OnBackground, modifier = Modifier.widthIn(min = 96.dp))
        TvButton(text = "+", onClick = onIncrease, contentDescription = stringResource(R.string.settings_increase) + " " + title)
    }
}

/** One-shot message with Dismiss; auto-dismisses after a few seconds. */
@Composable
internal fun TvMessageBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) {
        delay(MESSAGE_MILLIS)
        onDismiss()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(UiTokens.SurfaceVariant, TvDimens.Shape)
            .padding(horizontal = UiTokens.SpaceM, vertical = UiTokens.SpaceS)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = UiTokens.OnBackground, modifier = Modifier.weight(1f))
        TvButton(text = stringResource(R.string.settings_dismiss), onClick = onDismiss)
    }
}

private const val MESSAGE_MILLIS = 5_000L

/** Dialog frame with initial focus on the first focusable child that uses [initialFocus]. */
@Composable
internal fun TvSettingsDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable (initialFocus: FocusRequester) -> Unit,
) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .background(UiTokens.Surface, TvDimens.Shape)
                .padding(UiTokens.SpaceXl),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = UiTokens.OnBackground, modifier = Modifier.semantics { heading() })
            content(focus)
        }
    }
    LaunchedEffect(title) { runCatching { focus.requestFocus() } }
}

/** Confirmation with Cancel focused first (destructive actions need a deliberate move). */
@Composable
internal fun TvConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvSettingsDialog(title = title, onDismiss = onDismiss) { focus ->
        Text(message, style = MaterialTheme.typography.bodyLarge, color = UiTokens.OnSurfaceMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            TvButton(text = stringResource(R.string.settings_cancel), onClick = onDismiss, modifier = Modifier.focusRequester(focus))
            TvButton(text = confirmLabel, onClick = onConfirm)
        }
    }
}

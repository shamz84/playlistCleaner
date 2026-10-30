package app.playlistplayer.ui.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.core.player.url.LiveFormatPreference
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.playlists.PlaylistSummary
import app.playlistplayer.feature.playlists.SyncHealth
import app.playlistplayer.feature.settings.GroupVisibilityState
import app.playlistplayer.feature.settings.GuideUiState
import app.playlistplayer.feature.settings.LanguageCatalog
import app.playlistplayer.feature.settings.OverrideTestState
import app.playlistplayer.feature.settings.ParentalState
import app.playlistplayer.feature.settings.PinFormat
import app.playlistplayer.feature.settings.PinPromptRequest
import app.playlistplayer.feature.settings.QualityPreference
import app.playlistplayer.feature.settings.SettingsDialog
import app.playlistplayer.feature.settings.SettingsText
import app.playlistplayer.feature.settings.SettingsTexts
import app.playlistplayer.feature.settings.SettingsUiState
import app.playlistplayer.feature.settings.SettingsViewModel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.GlyphIcon
import app.playlistplayer.ui.mobile.MobileDimens
import kotlinx.coroutines.delay

@Composable
private fun SettingsText.resolve(): String {
    val resolved = args.map { if (it is SettingsText) it.resolve() else it }
    return stringResource(res, *resolved.toTypedArray())
}

/**
 * Phone Settings (U12, Material 3) over the same [SettingsViewModel] as TV: Playlists, Guide,
 * Playback, Content (languages, quality, PIN, adult-group suggestions, group visibility) and App.
 */
@Composable
fun MobileSettingsScreen(viewModel: SettingsViewModel, onAddPlaylist: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(modifier = modifier.fillMaxSize().testTag(MobileSettingsTags.LIST)) {
        item(key = "messages") { Messages(state, viewModel) }
        header("h-playlists", R.string.settings_section_playlists)
        playlists(state, viewModel, onAddPlaylist)
        header("h-guide", R.string.settings_section_guide)
        guide(state, viewModel)
        header("h-playback", R.string.settings_section_playback)
        playback(state, viewModel)
        header("h-content", R.string.settings_section_content)
        content(state, viewModel)
        header("h-app", R.string.settings_section_app)
        app(state, viewModel)
    }
    Overlays(state, viewModel)
}

private fun LazyListScope.header(key: String, title: Int) {
    item(key = key) {
        HorizontalDivider()
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = UiTokens.Accent,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS).semantics { heading() },
        )
    }
}

@Composable
private fun Muted(text: String, color: Color = UiTokens.OnSurfaceMuted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun Messages(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding)) {
        state.message?.let { Banner(SettingsTexts.message(it).resolve(), viewModel::dismissMessage) }
        state.playlists.message?.let { Banner(SettingsTexts.playlistMessage(it).resolve(), viewModel::dismissPlaylistMessage) }
        if (state.parental.resetRequired) Muted(stringResource(R.string.settings_pin_reset_required), UiTokens.Error)
    }
}

@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(5_000)
        onDismiss()
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = UiTokens.SpaceXs)) {
        Row(modifier = Modifier.padding(UiTokens.SpaceS), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dismiss)) }
        }
    }
}

@Composable
private fun ClickRow(title: String, onClick: () -> Unit, supporting: String? = null, value: String? = null, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = value?.let { { Text(it, style = MaterialTheme.typography.labelLarge) } },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = MobileDimens.MinTouchTarget),
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit, supporting: String? = null, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Switch) { onToggle(!checked) }
            .heightIn(min = MobileDimens.MinTouchTarget),
    )
}

@Composable
private fun StepperRow(title: String, value: String, onDecrease: () -> Unit, onIncrease: () -> Unit, supporting: String? = null) {
    val decrease = stringResource(R.string.settings_decrease)
    val increase = stringResource(R.string.settings_increase)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDecrease, modifier = Modifier.semantics { contentDescription = "$decrease $title" }) { Text("−") }
                Text(value, style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onIncrease, modifier = Modifier.semantics { contentDescription = "$increase $title" }) { Text("+") }
            }
        },
    )
}

// ---- Playlists ----

private fun LazyListScope.playlists(state: SettingsUiState, viewModel: SettingsViewModel, onAddPlaylist: () -> Unit) {
    item(key = "add") {
        ListItem(
            leadingContent = { GlyphIcon("＋") },
            headlineContent = { Text(stringResource(R.string.settings_add_playlist)) },
            supportingContent = { Text(stringResource(R.string.settings_add_playlist_description)) },
            modifier = Modifier.clickable(role = Role.Button, onClick = onAddPlaylist).testTag(MobileSettingsTags.ADD_PLAYLIST),
        )
    }
    if (state.playlists.playlists.isNotEmpty()) {
        item(key = "refresh-all") { ClickRow(stringResource(R.string.settings_playlist_refresh_all), viewModel::refreshAllPlaylists) }
    } else if (state.playlists.isEmpty) {
        item(key = "empty") { Column(Modifier.padding(MobileDimens.ScreenPadding)) { Muted(stringResource(R.string.settings_playlists_empty)) } }
    }
    items(state.playlists.playlists, key = { "p-${it.id}" }) { PlaylistCard(it, it.id in state.playlists.busy, viewModel) }
}

@Composable
private fun PlaylistCard(summary: PlaylistSummary, busy: Boolean, viewModel: SettingsViewModel) {
    var renaming by rememberSaveable(summary.id) { mutableStateOf(false) }
    var editing by rememberSaveable(summary.id) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceXs)) {
        Column(modifier = Modifier.padding(UiTokens.SpaceM), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            Text(summary.name, style = MaterialTheme.typography.titleMedium)
            val color = when (summary.health) {
                SyncHealth.Ok -> UiTokens.Success
                SyncHealth.NeverSynced -> UiTokens.OnSurfaceMuted
                SyncHealth.UsingCachedCopy -> UiTokens.Warning
                SyncHealth.Failed -> UiTokens.Error
            }
            Muted(SettingsTexts.health(summary).resolve(), color)
            summary.warning?.let { Muted(it, UiTokens.Warning) }
            SettingsTexts.account(summary).forEach { Muted(it.resolve(), if (summary.accountExpired) UiTokens.Error else UiTokens.OnSurfaceMuted) }
            Muted(SettingsTexts.counts(summary).resolve())
            if (busy) Muted(stringResource(R.string.settings_working))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_playlist_xtream), style = MaterialTheme.typography.bodyLarge)
                    Muted(
                        stringResource(
                            if (summary.xtreamAvailable || summary.xtreamEnabled) R.string.settings_playlist_xtream_description else R.string.settings_playlist_xtream_unavailable,
                        ),
                    )
                }
                Switch(
                    checked = summary.xtreamEnabled,
                    onCheckedChange = { viewModel.setXtreamEnabled(summary.id, it) },
                    enabled = !busy && (summary.xtreamAvailable || summary.xtreamEnabled),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                TextButton(onClick = { viewModel.refreshPlaylist(summary.id) }, enabled = !busy) { Text(stringResource(R.string.settings_playlist_refresh)) }
                TextButton(onClick = { renaming = true }, enabled = !busy) { Text(stringResource(R.string.settings_playlist_rename)) }
                TextButton(onClick = { editing = true }, enabled = !busy) { Text(stringResource(R.string.settings_playlist_edit_source)) }
                TextButton(onClick = { viewModel.requestRemovePlaylist(summary.id) }, enabled = !busy) { Text(stringResource(R.string.settings_playlist_remove)) }
            }
        }
    }
    if (renaming) {
        TextEntryDialog(
            title = stringResource(R.string.settings_playlist_rename),
            label = stringResource(R.string.settings_playlist_name_label),
            initial = summary.name,
            onSave = {
                renaming = false
                viewModel.renamePlaylist(summary.id, it)
            },
            onCancel = { renaming = false },
        )
    }
    if (editing) {
        EditSourceDialog(onSave = {
            editing = false
            viewModel.editPlaylistSource(summary.id, it)
        }, onCancel = { editing = false })
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    extra: (@Composable (String) -> Unit)? = null,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
                supporting?.let { Muted(it) }
                extra?.invoke(value)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun EditSourceDialog(onSave: (SourceInput) -> Unit, onCancel: () -> Unit) {
    var drive by rememberSaveable { mutableStateOf(true) }
    var value by rememberSaveable { mutableStateOf("") }
    val label = stringResource(if (drive) R.string.settings_playlist_source_drive else R.string.settings_playlist_source_url)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_playlist_edit_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                Muted(stringResource(R.string.settings_playlist_source_hint))
                OutlinedButton(onClick = { drive = !drive }) { Text(stringResource(R.string.settings_playlist_source_type) + ": " + label) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (drive) KeyboardType.Ascii else KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (drive) SourceInput.DriveId(value.trim()) else SourceInput.M3uUrl(value.trim())) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

// ---- Guide ----

private fun LazyListScope.guide(state: SettingsUiState, viewModel: SettingsViewModel) {
    item(key = "offset") {
        val offset = state.preferences.playback.catchupOffsetHours
        StepperRow(
            title = stringResource(R.string.settings_guide_catchup_offset),
            supporting = stringResource(R.string.settings_guide_catchup_offset_description),
            value = stringResource(R.string.settings_hours_signed, SettingsTexts.signedHours(offset)),
            onDecrease = { viewModel.setCatchupOffsetHours(offset - 1) },
            onIncrease = { viewModel.setCatchupOffsetHours(offset + 1) },
        )
    }
    items(state.playlists.playlists, key = { "g-${it.id}" }) { summary ->
        GuideRow(summary, state.guide[summary.id] ?: GuideUiState(summary.id), viewModel)
    }
}

@Composable
private fun GuideRow(summary: PlaylistSummary, guide: GuideUiState, viewModel: SettingsViewModel) {
    var editing by rememberSaveable(summary.id) { mutableStateOf(false) }
    val lines = SettingsTexts.guideStatus(guide.status).map { it.resolve() }
    ListItem(
        headlineContent = { Text(summary.name) },
        supportingContent = { Text(lines.joinToString("\n")) },
        trailingContent = if (guide.status?.overrideSet == true) {
            { TextButton(onClick = { viewModel.clearOverride(summary.id) }) { Text(stringResource(R.string.settings_guide_clear)) } }
        } else {
            null
        },
        modifier = Modifier.clickable(role = Role.Button) {
            viewModel.openGuide(summary.id)
            editing = true
        },
    )
    if (editing && guide.overrideLoaded) {
        val testText = when (val test = guide.test) {
            OverrideTestState.Idle -> null
            OverrideTestState.Running -> stringResource(R.string.settings_guide_testing)
            is OverrideTestState.Done -> SettingsTexts.testResult(test.result).resolve()
        }
        TextEntryDialog(
            title = stringResource(R.string.settings_guide_override_label),
            label = stringResource(R.string.settings_guide_override_label),
            initial = guide.overrideUrl.orEmpty(),
            supporting = testText,
            keyboardType = KeyboardType.Uri,
            onSave = {
                editing = false
                viewModel.saveOverride(summary.id, it)
            },
            onCancel = { editing = false },
            extra = { value ->
                OutlinedButton(onClick = { viewModel.testOverride(summary.id, value) }, enabled = value.isNotBlank() && guide.test != OverrideTestState.Running) {
                    Text(stringResource(R.string.settings_guide_test))
                }
            },
        )
    }
}

// ---- Playback ----

private fun LazyListScope.playback(state: SettingsUiState, viewModel: SettingsViewModel) {
    val playback = state.preferences.playback
    item(key = "format") {
        val next = if (playback.liveFormat == LiveFormatPreference.TS) LiveFormatPreference.HLS else LiveFormatPreference.TS
        ClickRow(
            title = stringResource(R.string.settings_playback_live_format),
            value = stringResource(SettingsTexts.liveFormat(playback.liveFormat)),
            supporting = if (!state.hlsAllowed) stringResource(R.string.settings_playback_hls_unavailable) else null,
            onClick = { viewModel.setLiveFormat(next) },
        )
    }
    item(key = "mfr") {
        ToggleRow(
            stringResource(R.string.settings_playback_match_frame_rate),
            playback.matchFrameRate,
            viewModel::setMatchFrameRate,
            stringResource(R.string.settings_playback_match_frame_rate_description),
        )
    }
    item(key = "tunnel") {
        ToggleRow(
            stringResource(R.string.settings_playback_tunneling),
            playback.tunneling,
            viewModel::setTunneling,
            stringResource(R.string.settings_playback_tunneling_description),
        )
    }
}

// ---- Content ----

private fun LazyListScope.content(state: SettingsUiState, viewModel: SettingsViewModel) {
    val languages = state.preferences.content.languages
    item(key = "lang-h") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_content_languages)) },
            supportingContent = { Text(stringResource(R.string.settings_content_languages_description)) },
        )
    }
    itemsIndexed(languages, key = { _, code -> "l-$code" }) { index, code ->
        val up = stringResource(R.string.settings_content_move_up)
        val down = stringResource(R.string.settings_content_move_down)
        val remove = stringResource(R.string.settings_content_remove_language)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_content_language_position, index + 1, LanguageCatalog.label(code))) },
            trailingContent = {
                Row {
                    TextButton(onClick = { viewModel.moveLanguageUp(index) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = up }) { Text("▲") }
                    TextButton(onClick = { viewModel.moveLanguageDown(index) }, enabled = index < languages.lastIndex, modifier = Modifier.semantics { contentDescription = down }) { Text("▼") }
                    TextButton(onClick = { viewModel.removeLanguage(code) }, enabled = languages.size > 1, modifier = Modifier.semantics { contentDescription = remove }) { Text("✕") }
                }
            },
        )
    }
    item(key = "lang-add") {
        var adding by rememberSaveable { mutableStateOf(false) }
        ClickRow(stringResource(R.string.settings_content_add_language), { adding = true })
        if (adding) {
            AddLanguageDialog(languages, onAdd = {
                adding = false
                viewModel.addLanguage(it)
            }, onCancel = { adding = false })
        }
    }
    item(key = "quality") {
        val quality = state.preferences.content.quality
        val next = QualityPreference.entries[(quality.ordinal + 1) % QualityPreference.entries.size]
        ClickRow(stringResource(R.string.settings_content_quality), { viewModel.setQuality(next) }, value = stringResource(SettingsTexts.quality(quality)))
    }
    item(key = "pin") { PinRow(state, viewModel) }
    if (state.suggestions.isNotEmpty()) {
        item(key = "suggestions") { Suggestions(state, viewModel) }
    }
    items(state.playlists.playlists, key = { "vis-${it.id}" }) { summary ->
        ClickRow(
            title = stringResource(R.string.settings_content_group_visibility),
            supporting = summary.name,
            onClick = { viewModel.openGroupVisibility(summary.id) },
            enabled = !state.parental.resetRequired,
        )
    }
}

private enum class PinMode { Set, Change, Remove }

@Composable
private fun PinRow(state: SettingsUiState, viewModel: SettingsViewModel) {
    var mode by rememberSaveable { mutableStateOf<PinMode?>(null) }
    Column(modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS)) {
        Text(stringResource(R.string.settings_content_pin), style = MaterialTheme.typography.bodyLarge)
        Muted(SettingsTexts.parental(state.parental).resolve(), if (state.parental.resetRequired) UiTokens.Error else UiTokens.OnSurfaceMuted)
        if (!state.parental.resetRequired) {
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                if (!state.parental.hasPin) {
                    TextButton(onClick = { mode = PinMode.Set }) { Text(stringResource(R.string.settings_pin_set)) }
                } else {
                    TextButton(onClick = { mode = PinMode.Change }) { Text(stringResource(R.string.settings_pin_change)) }
                    TextButton(onClick = { mode = PinMode.Remove }) { Text(stringResource(R.string.settings_pin_remove)) }
                    if (state.parental.state is ParentalState.Unlocked) {
                        TextButton(onClick = viewModel::lockNow) { Text(stringResource(R.string.settings_pin_lock_now)) }
                    }
                }
            }
        }
    }
    mode?.let { m ->
        PinEditorDialog(m, onDone = { current, new, confirm ->
            mode = null
            when (m) {
                PinMode.Set -> viewModel.setPin(new, confirm)
                PinMode.Change -> viewModel.changePin(current, new, confirm)
                PinMode.Remove -> viewModel.removePin(current)
            }
        }, onCancel = { mode = null })
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it in '0'..'9' }.take(PinFormat.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PinEditorDialog(mode: PinMode, onDone: (String, String, String) -> Unit, onCancel: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val ready = when (mode) {
        PinMode.Set -> new.length >= PinFormat.MIN_LENGTH && confirm.isNotEmpty()
        PinMode.Change -> current.isNotEmpty() && new.length >= PinFormat.MIN_LENGTH && confirm.isNotEmpty()
        PinMode.Remove -> current.isNotEmpty()
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    when (mode) {
                        PinMode.Set -> R.string.settings_pin_set
                        PinMode.Change -> R.string.settings_pin_change
                        PinMode.Remove -> R.string.settings_pin_remove
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                if (mode != PinMode.Set) PinField(current, { current = it }, stringResource(R.string.settings_pin_enter_current))
                if (mode != PinMode.Remove) {
                    PinField(new, { new = it }, stringResource(R.string.settings_pin_enter_new))
                    PinField(confirm, { confirm = it }, stringResource(R.string.settings_pin_enter_confirm))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(current, new, confirm) }, enabled = ready) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun Suggestions(state: SettingsUiState, viewModel: SettingsViewModel) {
    val names = state.playlists.playlists.associate { it.id to it.name }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceXs)) {
        Column(modifier = Modifier.padding(UiTokens.SpaceM), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            Text(stringResource(R.string.settings_suggestions_title), style = MaterialTheme.typography.titleSmall)
            Muted(stringResource(R.string.settings_suggestions_description))
            state.suggestions.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_suggestion_item, names[s.group.playlistId].orEmpty(), s.displayName), modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.confirmSuggestions(setOf(s.group)) }) { Text(stringResource(R.string.settings_suggestion_lock)) }
                }
            }
            Row {
                TextButton(onClick = { viewModel.confirmSuggestions() }) { Text(stringResource(R.string.settings_suggestions_lock_all)) }
                TextButton(onClick = viewModel::dismissSuggestions) { Text(stringResource(R.string.settings_suggestions_dismiss)) }
            }
        }
    }
}

@Composable
private fun AddLanguageDialog(existing: List<String>, onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var custom by rememberSaveable { mutableStateOf("") }
    val options = remember(existing) { LanguageCatalog.options.filter { it.code !in existing } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_content_add_language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(options, key = { it.code }) { option ->
                        ListItem(
                            headlineContent = { Text(option.label) },
                            trailingContent = { Text(option.code) },
                            modifier = Modifier.clickable(role = Role.Button) { onAdd(option.code) },
                        )
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.settings_content_language_code)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(custom) }, enabled = custom.isNotBlank()) { Text(stringResource(R.string.settings_content_add_language)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

// ---- App ----

private fun LazyListScope.app(state: SettingsUiState, viewModel: SettingsViewModel) {
    val sync = state.preferences.sync
    item(key = "refresh") {
        StepperRow(
            title = stringResource(R.string.settings_app_refresh_interval),
            value = stringResource(R.string.settings_app_hours, sync.refreshIntervalHours),
            onDecrease = { viewModel.setRefreshInterval(SettingsTexts.step(SettingsTexts.REFRESH_PRESETS_HOURS, sync.refreshIntervalHours, -1)) },
            onIncrease = { viewModel.setRefreshInterval(SettingsTexts.step(SettingsTexts.REFRESH_PRESETS_HOURS, sync.refreshIntervalHours, +1)) },
        )
    }
    item(key = "stale") {
        StepperRow(
            title = stringResource(R.string.settings_app_stale_warning),
            value = stringResource(R.string.settings_app_days, sync.staleWarningDays),
            onDecrease = { viewModel.setStaleWarningDays(SettingsTexts.step(SettingsTexts.STALE_PRESETS_DAYS, sync.staleWarningDays, -1)) },
            onIncrease = { viewModel.setStaleWarningDays(SettingsTexts.step(SettingsTexts.STALE_PRESETS_DAYS, sync.staleWarningDays, +1)) },
        )
    }
    item(key = "reset") {
        ClickRow(
            title = stringResource(R.string.settings_app_reset),
            supporting = stringResource(R.string.settings_app_reset_description),
            onClick = viewModel::requestResetApp,
            enabled = !state.resetting,
        )
    }
}

// ---- Overlays ----

@Composable
private fun Overlays(state: SettingsUiState, viewModel: SettingsViewModel) {
    when (val dialog = state.dialog) {
        is SettingsDialog.ConfirmRemovePlaylist -> ConfirmDialog(
            stringResource(R.string.settings_confirm_remove_title, dialog.name),
            stringResource(R.string.settings_confirm_remove_message),
            stringResource(R.string.settings_remove),
            viewModel::confirmDialog,
            viewModel::dismissDialog,
        )
        SettingsDialog.ConfirmResetApp -> ConfirmDialog(
            stringResource(R.string.settings_confirm_reset_title),
            stringResource(R.string.settings_confirm_reset_message),
            stringResource(R.string.settings_reset),
            viewModel::confirmDialog,
            viewModel::dismissDialog,
        )
        null -> Unit
    }
    state.groupVisibility?.let { visibility ->
        val name = state.playlists.playlists.firstOrNull { it.id == visibility.playlistId }?.name.orEmpty()
        GroupVisibilityDialog(name, visibility, viewModel)
    }
    state.pinPrompt?.let { MobilePinPromptDialog(it, viewModel::submitPin, viewModel::cancelPin) }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = UiTokens.Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun GroupVisibilityDialog(playlistName: String, state: GroupVisibilityState, viewModel: SettingsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeGroupVisibility,
        title = { Text(stringResource(R.string.settings_content_group_visibility) + " · " + playlistName) },
        text = {
            when {
                state.loading -> Muted(stringResource(R.string.common_loading))
                state.rows.isEmpty() -> Muted(stringResource(R.string.settings_groups_empty))
                else -> LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(state.rows, key = { it.group.key }) { row ->
                        ListItem(
                            headlineContent = { Text(row.group.name) },
                            supportingContent = { Text(stringResource(SettingsTexts.groupKind(row.group.kind))) },
                            trailingContent = { Switch(checked = row.locked, onCheckedChange = null) },
                            modifier = Modifier.clickable(role = Role.Switch) { viewModel.setGroupLocked(row.group, !row.locked) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::closeGroupVisibility) { Text(stringResource(R.string.settings_groups_close)) } },
    )
}

/** The parental gate prompt (Settings, and the root's global prompt). */
@Composable
internal fun MobilePinPromptDialog(request: PinPromptRequest, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember(request.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_pin_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                Text(stringResource(SettingsTexts.action(request.action)))
                SettingsTexts.authorization(request.reason)?.let { Muted(it.resolve(), UiTokens.Error) }
                PinField(pin, { pin = it }, stringResource(R.string.settings_pin_prompt_title), Modifier.testTag(MobileSettingsTags.PIN_FIELD))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(pin) }, enabled = pin.length >= PinFormat.MIN_LENGTH) { Text(stringResource(R.string.settings_pin_ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

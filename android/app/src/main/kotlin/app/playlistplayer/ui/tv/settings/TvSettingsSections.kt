package app.playlistplayer.ui.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.player.url.LiveFormatPreference
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.playlists.PlaylistSummary
import app.playlistplayer.feature.playlists.SyncHealth
import app.playlistplayer.feature.settings.ContentGroup
import app.playlistplayer.feature.settings.GroupVisibilityState
import app.playlistplayer.feature.settings.GuideUiState
import app.playlistplayer.feature.settings.LanguageCatalog
import app.playlistplayer.feature.settings.OverrideTestState
import app.playlistplayer.feature.settings.ParentalState
import app.playlistplayer.feature.settings.QualityPreference
import app.playlistplayer.feature.settings.SettingsTexts
import app.playlistplayer.feature.settings.SettingsUiState
import app.playlistplayer.feature.settings.SettingsViewModel
import app.playlistplayer.feature.vod.TrackLanguageCatalog
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.components.TvTextField
import app.playlistplayer.ui.tv.theme.TvDimens

// ---------------------------------------------------------------- Playlists

internal fun LazyListScope.playlistsSection(state: SettingsUiState, viewModel: SettingsViewModel, onAddPlaylist: () -> Unit) {
    item(key = "playlists-actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM), modifier = Modifier.padding(vertical = UiTokens.SpaceS)) {
            TvButton(text = stringResource(R.string.settings_add_playlist), onClick = onAddPlaylist)
            TvButton(text = stringResource(R.string.settings_playlist_refresh_all), onClick = viewModel::refreshAllPlaylists, enabled = state.playlists.playlists.isNotEmpty())
        }
    }
    if (state.playlists.isEmpty) {
        item(key = "playlists-empty") { TvMutedText(stringResource(R.string.settings_playlists_empty)) }
    }
    items(state.playlists.playlists, key = { "playlist-${it.id}" }) { summary ->
        TvPlaylistCard(summary, busy = summary.id in state.playlists.busy, viewModel = viewModel)
    }
}

@Composable
private fun TvPlaylistCard(summary: PlaylistSummary, busy: Boolean, viewModel: SettingsViewModel) {
    var renaming by rememberSaveable(summary.id) { mutableStateOf(false) }
    var editingSource by rememberSaveable(summary.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UiTokens.Surface, TvDimens.Shape)
            .padding(UiTokens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
    ) {
        Text(summary.name, style = MaterialTheme.typography.titleLarge, color = UiTokens.OnBackground)
        val healthColor = when (summary.health) {
            SyncHealth.Ok -> UiTokens.Success
            SyncHealth.NeverSynced -> UiTokens.OnSurfaceMuted
            SyncHealth.UsingCachedCopy -> UiTokens.Warning
            SyncHealth.Failed -> UiTokens.Error
        }
        TvMutedText(SettingsTexts.health(summary).resolve(), color = healthColor)
        summary.warning?.let { TvMutedText(it, color = UiTokens.Warning) }
        SettingsTexts.account(summary).forEach { TvMutedText(it.resolve(), color = if (summary.accountExpired) UiTokens.Error else UiTokens.OnSurfaceMuted) }
        TvMutedText(SettingsTexts.counts(summary).resolve())
        if (busy) TvMutedText(stringResource(R.string.settings_working))
        TvToggleRow(
            title = stringResource(R.string.settings_playlist_xtream),
            supporting = stringResource(if (summary.xtreamAvailable || summary.xtreamEnabled) R.string.settings_playlist_xtream_description else R.string.settings_playlist_xtream_unavailable),
            checked = summary.xtreamEnabled,
            enabled = !busy && (summary.xtreamAvailable || summary.xtreamEnabled),
            onToggle = { viewModel.setXtreamEnabled(summary.id, it) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            TvButton(text = stringResource(R.string.settings_playlist_refresh), onClick = { viewModel.refreshPlaylist(summary.id) }, enabled = !busy)
            TvButton(text = stringResource(R.string.settings_playlist_rename), onClick = { renaming = true }, enabled = !busy)
            TvButton(text = stringResource(R.string.settings_playlist_edit_source), onClick = { editingSource = true }, enabled = !busy)
            TvButton(text = stringResource(R.string.settings_playlist_remove), onClick = { viewModel.requestRemovePlaylist(summary.id) }, enabled = !busy)
        }
    }
    if (renaming) {
        TvTextEntryDialog(
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
    if (editingSource) {
        TvEditSourceDialog(
            onSave = {
                editingSource = false
                viewModel.editPlaylistSource(summary.id, it)
            },
            onCancel = { editingSource = false },
        )
    }
}

@Composable
internal fun TvTextEntryDialog(
    title: String,
    label: String,
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    extraActions: (@Composable (String) -> Unit)? = null,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    TvSettingsDialog(title = title, onDismiss = onCancel) { focus ->
        TvTextField(
            value = value,
            onValueChange = { value = it },
            label = label,
            supportingText = supporting,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.focusRequester(focus),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            extraActions?.invoke(value)
            TvButton(text = stringResource(R.string.settings_save), onClick = { onSave(value) })
            TvButton(text = stringResource(R.string.settings_cancel), onClick = onCancel)
        }
    }
}

/** New source for a playlist (the current one is a secret and is not shown). */
@Composable
private fun TvEditSourceDialog(onSave: (SourceInput) -> Unit, onCancel: () -> Unit) {
    var drive by rememberSaveable { mutableStateOf(true) }
    var value by rememberSaveable { mutableStateOf("") }
    TvSettingsDialog(title = stringResource(R.string.settings_playlist_edit_source), onDismiss = onCancel) { focus ->
        TvMutedText(stringResource(R.string.settings_playlist_source_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_playlist_source_type), style = MaterialTheme.typography.titleSmall, color = UiTokens.OnSurfaceMuted)
            TvButton(
                text = stringResource(if (drive) R.string.settings_playlist_source_drive else R.string.settings_playlist_source_url),
                onClick = { drive = !drive },
                modifier = Modifier.focusRequester(focus),
            )
        }
        TvTextField(
            value = value,
            onValueChange = { value = it },
            label = stringResource(if (drive) R.string.settings_playlist_source_drive else R.string.settings_playlist_source_url),
            keyboardOptions = KeyboardOptions(keyboardType = if (drive) KeyboardType.Ascii else KeyboardType.Uri),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            TvButton(
                text = stringResource(R.string.settings_save),
                onClick = { onSave(if (drive) SourceInput.DriveId(value.trim()) else SourceInput.M3uUrl(value.trim())) },
                enabled = value.isNotBlank(),
            )
            TvButton(text = stringResource(R.string.settings_cancel), onClick = onCancel)
        }
    }
}

// ---------------------------------------------------------------- Guide

internal fun LazyListScope.guideSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    item(key = "catchup-offset") {
        val offset = state.preferences.playback.catchupOffsetHours
        TvStepperRow(
            title = stringResource(R.string.settings_guide_catchup_offset),
            supporting = stringResource(R.string.settings_guide_catchup_offset_description),
            value = stringResource(R.string.settings_hours_signed, SettingsTexts.signedHours(offset)),
            onDecrease = { viewModel.setCatchupOffsetHours(offset - 1) },
            onIncrease = { viewModel.setCatchupOffsetHours(offset + 1) },
        )
    }
    if (state.playlists.isEmpty) {
        item(key = "guide-empty") { TvMutedText(stringResource(R.string.settings_playlists_empty)) }
    } else {
        item(key = "guide-apply-curated") {
            TvButton(text = stringResource(R.string.settings_guide_apply_curated), onClick = viewModel::applyCuratedGuideToAll)
        }
    }
    items(state.playlists.playlists, key = { "guide-${it.id}" }) { summary ->
        TvGuideCard(summary, state.guide[summary.id] ?: GuideUiState(summary.id), viewModel)
    }
}

@Composable
private fun TvGuideCard(summary: PlaylistSummary, guide: GuideUiState, viewModel: SettingsViewModel) {
    var editing by rememberSaveable(summary.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().background(UiTokens.Surface, TvDimens.Shape).padding(UiTokens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
    ) {
        TvSectionHeader(summary.name)
        SettingsTexts.guideStatus(guide.status).forEach { TvMutedText(it.resolve()) }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            TvButton(
                text = stringResource(R.string.settings_guide_edit),
                onClick = {
                    viewModel.openGuide(summary.id)
                    editing = true
                },
            )
            if (guide.status?.overrideSet == true) {
                TvButton(text = stringResource(R.string.settings_guide_clear), onClick = { viewModel.clearOverride(summary.id) })
            }
            TvButton(text = stringResource(R.string.settings_guide_refresh), onClick = { viewModel.refreshGuide(summary.id) })
        }
    }
    if (editing && guide.overrideLoaded) {
        TvOverrideDialog(guide, onTest = { viewModel.testOverride(summary.id, it) }, onSave = {
            editing = false
            viewModel.saveOverride(summary.id, it)
        }, onCancel = { editing = false })
    }
}

@Composable
private fun TvOverrideDialog(guide: GuideUiState, onTest: (String) -> Unit, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val testText = when (val test = guide.test) {
        OverrideTestState.Idle -> null
        OverrideTestState.Running -> stringResource(R.string.settings_guide_testing)
        is OverrideTestState.Done -> SettingsTexts.testResult(test.result).resolve()
    }
    TvTextEntryDialog(
        title = stringResource(R.string.settings_guide_override_label),
        label = stringResource(R.string.settings_guide_override_label),
        initial = guide.overrideUrl.orEmpty(),
        supporting = testText,
        keyboardType = KeyboardType.Uri,
        onSave = onSave,
        onCancel = onCancel,
        extraActions = { value ->
            TvButton(text = stringResource(R.string.settings_guide_test), onClick = { if (guide.test != OverrideTestState.Running) onTest(value) }, enabled = value.isNotBlank())
        },
    )
}

// ---------------------------------------------------------------- Playback

internal fun LazyListScope.playbackSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val playback = state.preferences.playback
    item(key = "live-format") {
        val next = if (playback.liveFormat == LiveFormatPreference.TS) LiveFormatPreference.HLS else LiveFormatPreference.TS
        TvSettingRow(
            title = stringResource(R.string.settings_playback_live_format),
            value = stringResource(SettingsTexts.liveFormat(playback.liveFormat)),
            supporting = if (!state.hlsAllowed) stringResource(R.string.settings_playback_hls_unavailable) else null,
            onClick = { viewModel.setLiveFormat(next) },
        )
    }
    item(key = "match-frame-rate") {
        TvToggleRow(
            title = stringResource(R.string.settings_playback_match_frame_rate),
            supporting = stringResource(R.string.settings_playback_match_frame_rate_description),
            checked = playback.matchFrameRate,
            onToggle = viewModel::setMatchFrameRate,
        )
    }
    item(key = "show-clock") {
        TvToggleRow(
            title = stringResource(R.string.settings_playback_show_clock),
            supporting = stringResource(R.string.settings_playback_show_clock_description),
            checked = playback.showClock,
            onToggle = viewModel::setShowClock,
        )
    }
    item(key = "show-video-info") {
        TvToggleRow(
            title = stringResource(R.string.settings_playback_show_video_info),
            supporting = stringResource(R.string.settings_playback_show_video_info_description),
            checked = playback.showVideoInfo,
            onToggle = viewModel::setShowVideoInfo,
        )
    }
    item(key = "vod-diagnostics") {
        TvToggleRow(
            title = stringResource(R.string.settings_playback_vod_diagnostics),
            supporting = stringResource(R.string.settings_playback_vod_diagnostics_description),
            checked = playback.showVodDiagnostics,
            onToggle = viewModel::setShowVodDiagnostics,
        )
    }
    item(key = "tunneling") {
        TvToggleRow(
            title = stringResource(R.string.settings_playback_tunneling),
            supporting = stringResource(R.string.settings_playback_tunneling_description),
            checked = playback.tunneling,
            onToggle = viewModel::setTunneling,
        )
    }
    item(key = "audio-language") {
        var picking by rememberSaveable { mutableStateOf(false) }
        val device = stringResource(R.string.settings_playback_audio_device)
        TvSettingRow(
            title = stringResource(R.string.settings_playback_audio_language),
            value = playback.audioLanguage?.let(TrackLanguageCatalog::label) ?: device,
            supporting = stringResource(R.string.settings_playback_audio_language_description),
            onClick = { picking = true },
        )
        if (picking) {
            TvTrackLanguageDialog(
                title = stringResource(R.string.settings_playback_audio_language),
                noneLabel = device,
                selected = playback.audioLanguage,
                onPick = { picking = false; viewModel.setAudioLanguage(it) },
                onCancel = { picking = false },
            )
        }
    }
    item(key = "subtitle-language") {
        var picking by rememberSaveable { mutableStateOf(false) }
        val off = stringResource(R.string.settings_playback_subtitles_off)
        TvSettingRow(
            title = stringResource(R.string.settings_playback_subtitles),
            value = playback.subtitleLanguage?.let(TrackLanguageCatalog::label) ?: off,
            supporting = stringResource(R.string.settings_playback_subtitles_description),
            onClick = { picking = true },
        )
        if (picking) {
            TvTrackLanguageDialog(
                title = stringResource(R.string.settings_playback_subtitles),
                noneLabel = off,
                selected = playback.subtitleLanguage,
                onPick = { picking = false; viewModel.setSubtitleLanguage(it) },
                onCancel = { picking = false },
            )
        }
    }
}

/** Audio / subtitle language picker: [noneLabel] (device language / Off) first, then the catalog. */
@Composable
private fun TvTrackLanguageDialog(title: String, noneLabel: String, selected: String?, onPick: (String?) -> Unit, onCancel: () -> Unit) {
    val codes = remember(selected) { (listOfNotNull(selected) + TrackLanguageCatalog.codes).distinct() }
    TvSettingsDialog(title = title, onDismiss = onCancel) { focus ->
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            item(key = "none") {
                ListItem(
                    selected = selected == null,
                    onClick = { onPick(null) },
                    headlineContent = { Text(noneLabel) },
                    modifier = if (selected == null) Modifier.focusRequester(focus) else Modifier,
                    scale = ListItemDefaults.scale(focusedScale = 1.02f),
                    border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder),
                )
            }
            items(codes, key = { it }) { code ->
                ListItem(
                    selected = code == selected,
                    onClick = { onPick(code) },
                    headlineContent = { Text(TrackLanguageCatalog.label(code)) },
                    trailingContent = { Text(code, style = MaterialTheme.typography.labelMedium) },
                    modifier = if (code == selected) Modifier.focusRequester(focus) else Modifier,
                    scale = ListItemDefaults.scale(focusedScale = 1.02f),
                    border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder),
                )
            }
        }
        TvButton(text = stringResource(R.string.settings_cancel), onClick = onCancel)
    }
}

// ---------------------------------------------------------------- Content

internal fun LazyListScope.contentSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val languages = state.preferences.content.languages
    item(key = "languages-header") {
        TvSectionHeader(stringResource(R.string.settings_content_languages))
        TvMutedText(stringResource(R.string.settings_content_languages_description))
    }
    itemsIndexed(languages, key = { _, code -> "language-$code" }) { index, code ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = UiTokens.SpaceXs),
            horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_content_language_position, index + 1, LanguageCatalog.label(code)),
                style = MaterialTheme.typography.titleSmall,
                color = UiTokens.OnBackground,
                modifier = Modifier.weight(1f),
            )
            TvButton(text = stringResource(R.string.settings_content_move_up), onClick = { viewModel.moveLanguageUp(index) }, enabled = index > 0)
            TvButton(text = stringResource(R.string.settings_content_move_down), onClick = { viewModel.moveLanguageDown(index) }, enabled = index < languages.lastIndex)
            TvButton(text = stringResource(R.string.settings_content_remove_language), onClick = { viewModel.removeLanguage(code) }, enabled = languages.size > 1)
        }
    }
    item(key = "language-add") {
        var adding by rememberSaveable { mutableStateOf(false) }
        TvButton(text = stringResource(R.string.settings_content_add_language), onClick = { adding = true })
        if (adding) {
            TvAddLanguageDialog(
                existing = languages,
                onAdd = {
                    adding = false
                    viewModel.addLanguage(it)
                },
                onCancel = { adding = false },
            )
        }
    }
    item(key = "quality") {
        val quality = state.preferences.content.quality
        val next = QualityPreference.entries[(quality.ordinal + 1) % QualityPreference.entries.size]
        TvSettingRow(
            title = stringResource(R.string.settings_content_quality),
            value = stringResource(SettingsTexts.quality(quality)),
            onClick = { viewModel.setQuality(next) },
        )
    }
    item(key = "pin") { TvPinSection(state, viewModel) }
    if (state.suggestions.isNotEmpty()) {
        item(key = "suggestions") { TvSuggestions(state, viewModel) }
    }
    item(key = "groups-header") {
        TvSectionHeader(stringResource(R.string.settings_content_group_visibility))
        TvMutedText(stringResource(R.string.settings_content_group_visibility_description))
    }
    items(state.playlists.playlists, key = { "groups-${it.id}" }) { summary ->
        TvSettingRow(
            title = summary.name,
            supporting = stringResource(R.string.settings_content_group_visibility),
            onClick = { viewModel.openGroupVisibility(summary.id) },
            enabled = !state.parental.resetRequired,
        )
    }
}

@Composable
private fun TvPinSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var editor by rememberSaveable { mutableStateOf<PinEditorMode?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        TvSectionHeader(stringResource(R.string.settings_content_pin))
        TvMutedText(
            SettingsTexts.parental(state.parental).resolve(),
            color = if (state.parental.resetRequired) UiTokens.Error else UiTokens.OnSurfaceMuted,
        )
        if (!state.parental.resetRequired) {
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                if (!state.parental.hasPin) {
                    TvButton(text = stringResource(R.string.settings_pin_set), onClick = { editor = PinEditorMode.Set })
                } else {
                    TvButton(text = stringResource(R.string.settings_pin_change), onClick = { editor = PinEditorMode.Change })
                    TvButton(text = stringResource(R.string.settings_pin_remove), onClick = { editor = PinEditorMode.Remove })
                    if (state.parental.state is ParentalState.Unlocked) {
                        TvButton(text = stringResource(R.string.settings_pin_lock_now), onClick = viewModel::lockNow)
                    }
                }
            }
        }
    }
    editor?.let { mode ->
        TvPinEditorDialog(
            mode = mode,
            onDone = { current, new, confirm ->
                editor = null
                when (mode) {
                    PinEditorMode.Set -> viewModel.setPin(new, confirm)
                    PinEditorMode.Change -> viewModel.changePin(current, new, confirm)
                    PinEditorMode.Remove -> viewModel.removePin(current)
                }
            },
            onCancel = { editor = null },
        )
    }
}

@Composable
private fun TvSuggestions(state: SettingsUiState, viewModel: SettingsViewModel) {
    val names = state.playlists.playlists.associate { it.id to it.name }
    Column(
        modifier = Modifier.fillMaxWidth().background(UiTokens.SurfaceVariant, TvDimens.Shape).padding(UiTokens.SpaceM),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
    ) {
        TvSectionHeader(stringResource(R.string.settings_suggestions_title))
        TvMutedText(stringResource(R.string.settings_suggestions_description))
        state.suggestions.forEach { suggestion ->
            TvSettingRow(
                title = stringResource(R.string.settings_suggestion_item, names[suggestion.group.playlistId].orEmpty(), suggestion.displayName),
                value = stringResource(R.string.settings_suggestion_lock),
                onClick = { viewModel.confirmSuggestions(setOf(suggestion.group)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            TvButton(text = stringResource(R.string.settings_suggestions_lock_all), onClick = { viewModel.confirmSuggestions() })
            TvButton(text = stringResource(R.string.settings_suggestions_dismiss), onClick = viewModel::dismissSuggestions)
        }
    }
}

@Composable
private fun TvAddLanguageDialog(existing: List<String>, onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var custom by rememberSaveable { mutableStateOf("") }
    val options = remember(existing) { LanguageCatalog.options.filter { it.code !in existing } }
    TvSettingsDialog(title = stringResource(R.string.settings_content_add_language), onDismiss = onCancel) { focus ->
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            itemsIndexed(options, key = { _, o -> o.code }) { index, option ->
                ListItem(
                    selected = false,
                    onClick = { onAdd(option.code) },
                    headlineContent = { Text(option.label) },
                    trailingContent = { Text(option.code, style = MaterialTheme.typography.labelMedium) },
                    modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    scale = ListItemDefaults.scale(focusedScale = 1.02f),
                    border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder),
                )
            }
        }
        TvTextField(value = custom, onValueChange = { custom = it }, label = stringResource(R.string.settings_content_language_code))
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            TvButton(text = stringResource(R.string.settings_content_add_language), onClick = { onAdd(custom) }, enabled = custom.isNotBlank())
            TvButton(text = stringResource(R.string.settings_cancel), onClick = onCancel)
        }
    }
}

/** Per-playlist group/category visibility; each toggle is PIN-gated by the view model. */
@Composable
internal fun TvGroupVisibilityDialog(
    playlistName: String,
    state: GroupVisibilityState,
    onToggle: (ContentGroup, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    TvSettingsDialog(title = stringResource(R.string.settings_content_group_visibility) + " · " + playlistName, onDismiss = onClose) { focus ->
        when {
            state.loading -> TvMutedText(stringResource(R.string.common_loading))
            state.rows.isEmpty() -> TvMutedText(stringResource(R.string.settings_groups_empty))
            else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                itemsIndexed(state.rows, key = { _, row -> row.group.key }) { index, row ->
                    val status = stringResource(if (row.locked) R.string.settings_group_hidden else R.string.settings_group_visible)
                    ListItem(
                        selected = false,
                        onClick = { onToggle(row.group, !row.locked) },
                        headlineContent = { Text(row.group.name) },
                        supportingContent = { Text(stringResource(SettingsTexts.groupKind(row.group.kind)) + " · " + status, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = { Switch(checked = row.locked, onCheckedChange = null) },
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                        scale = ListItemDefaults.scale(focusedScale = 1.02f),
                        border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder),
                    )
                }
            }
        }
        TvButton(
            text = stringResource(R.string.settings_groups_close),
            onClick = onClose,
            modifier = if (state.loading || state.rows.isEmpty()) Modifier.focusRequester(focus) else Modifier,
        )
        // The rows arrive after the first frame; move focus to the first one when they do.
        LaunchedEffect(state.loading) { runCatching { focus.requestFocus() } }
    }
}

// ---------------------------------------------------------------- App

internal fun LazyListScope.appSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val sync = state.preferences.sync
    item(key = "theme") {
        val theme = state.preferences.theme
        TvSettingRow(
            title = stringResource(R.string.settings_app_theme),
            value = stringResource(SettingsTexts.theme(theme)),
            supporting = stringResource(R.string.settings_app_theme_description),
            onClick = {
                viewModel.setTheme(theme.next())
            },
        )
    }
    item(key = "refresh-interval") {
        TvStepperRow(
            title = stringResource(R.string.settings_app_refresh_interval),
            value = stringResource(R.string.settings_app_hours, sync.refreshIntervalHours),
            onDecrease = { viewModel.setRefreshInterval(SettingsTexts.step(SettingsTexts.REFRESH_PRESETS_HOURS, sync.refreshIntervalHours, -1)) },
            onIncrease = { viewModel.setRefreshInterval(SettingsTexts.step(SettingsTexts.REFRESH_PRESETS_HOURS, sync.refreshIntervalHours, +1)) },
        )
    }
    item(key = "stale-warning") {
        TvStepperRow(
            title = stringResource(R.string.settings_app_stale_warning),
            value = stringResource(R.string.settings_app_days, sync.staleWarningDays),
            onDecrease = { viewModel.setStaleWarningDays(SettingsTexts.step(SettingsTexts.STALE_PRESETS_DAYS, sync.staleWarningDays, -1)) },
            onIncrease = { viewModel.setStaleWarningDays(SettingsTexts.step(SettingsTexts.STALE_PRESETS_DAYS, sync.staleWarningDays, +1)) },
        )
    }
    item(key = "reset") {
        TvSettingRow(
            title = stringResource(R.string.settings_app_reset),
            supporting = stringResource(R.string.settings_app_reset_description),
            onClick = viewModel::requestResetApp,
            enabled = !state.resetting,
        )
    }
}

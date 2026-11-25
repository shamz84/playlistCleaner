package app.playlistplayer.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.BuildConfig
import app.playlistplayer.R
import app.playlistplayer.feature.settings.SettingsDialog
import app.playlistplayer.feature.settings.SettingsTexts
import app.playlistplayer.feature.settings.SettingsUiState
import app.playlistplayer.feature.settings.SettingsViewModel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.theme.TvDimens

/** Settings sections, in rail order. */
enum class SettingsSection(val titleRes: Int) {
    Playlists(R.string.settings_section_playlists),
    Guide(R.string.settings_section_guide),
    Playback(R.string.settings_section_playback),
    Content(R.string.settings_section_content),
    App(R.string.settings_section_app),
}

/**
 * TV Settings (U12): a section list on the left and the section on the right, all D-pad
 * reachable. Dialogs: PIN prompt (numeric pad), PIN editor, confirmations, text entry.
 *
 * @param onAddPlaylist opens the onboarding flow ("Add playlist").
 */
@Composable
fun TvSettingsScreen(viewModel: SettingsViewModel, onAddPlaylist: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(SettingsSection.Playlists) }
    Row(modifier = modifier.fillMaxSize().padding(TvDimens.ScreenPadding), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceL)) {
        SettingsSectionRail(section, onSelect = { section = it })
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            Messages(state, viewModel)
            LazyColumn(
                modifier = Modifier.weight(1f).testTag(TvSettingsTags.CONTENT),
                verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
            ) {
                when (section) {
                    SettingsSection.Playlists -> playlistsSection(state, viewModel, onAddPlaylist)
                    SettingsSection.Guide -> guideSection(state, viewModel)
                    SettingsSection.Playback -> playbackSection(state, viewModel)
                    SettingsSection.Content -> contentSection(state, viewModel)
                    SettingsSection.App -> appSection(state, viewModel)
                }
            }
        }
    }
    Overlays(state, viewModel)
}

@Composable
internal fun SettingsSectionRail(section: SettingsSection, onSelect: (SettingsSection) -> Unit) {
    Column(
        modifier = Modifier.width(240.dp).fillMaxHeight().testTag(TvSettingsTags.SECTIONS),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, color = UiTokens.OnBackground)
        SettingsSection.entries.forEach { entry ->
            ListItem(
                selected = entry == section,
                onClick = { onSelect(entry) },
                headlineContent = { Text(stringResource(entry.titleRes)) },
                modifier = Modifier.onFocusChanged { if (it.isFocused) onSelect(entry) },
                scale = ListItemDefaults.scale(focusedScale = 1.03f, focusedSelectedScale = 1.03f),
                border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder, focusedSelectedBorder = TvDimens.FocusBorder),
            )
        }
        Text(
            text = stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = UiTokens.OnSurfaceMuted,
            modifier = Modifier.padding(horizontal = UiTokens.SpaceM),
        )
    }
}

@Composable
private fun Messages(state: SettingsUiState, viewModel: SettingsViewModel) {
    state.message?.let { TvMessageBanner(SettingsTexts.message(it).resolve(), onDismiss = viewModel::dismissMessage) }
    state.playlists.message?.let { TvMessageBanner(SettingsTexts.playlistMessage(it).resolve(), onDismiss = viewModel::dismissPlaylistMessage) }
    if (state.parental.resetRequired) {
        TvMutedText(stringResource(R.string.settings_pin_reset_required), color = UiTokens.Error)
    }
}

@Composable
private fun Overlays(state: SettingsUiState, viewModel: SettingsViewModel) {
    when (val dialog = state.dialog) {
        is SettingsDialog.ConfirmRemovePlaylist -> TvConfirmDialog(
            title = stringResource(R.string.settings_confirm_remove_title, dialog.name),
            message = stringResource(R.string.settings_confirm_remove_message),
            confirmLabel = stringResource(R.string.settings_remove),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        SettingsDialog.ConfirmResetApp -> TvConfirmDialog(
            title = stringResource(R.string.settings_confirm_reset_title),
            message = stringResource(R.string.settings_confirm_reset_message),
            confirmLabel = stringResource(R.string.settings_reset),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }
    state.groupVisibility?.let { visibility ->
        val name = state.playlists.playlists.firstOrNull { it.id == visibility.playlistId }?.name.orEmpty()
        TvGroupVisibilityDialog(name, visibility, onToggle = viewModel::setGroupLocked, onClose = viewModel::closeGroupVisibility)
    }
    // The PIN prompt goes last so it draws above any dialog that triggered it.
    state.pinPrompt?.let { request ->
        TvPinPromptDialog(request, onSubmit = viewModel::submitPin, onCancel = viewModel::cancelPin)
    }
}

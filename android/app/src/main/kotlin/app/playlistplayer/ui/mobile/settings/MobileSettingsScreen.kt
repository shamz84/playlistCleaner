package app.playlistplayer.ui.mobile.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.playlistplayer.R
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.GlyphIcon
import app.playlistplayer.ui.mobile.MobileDimens

object MobileSettingsTags {
    const val ADD_PLAYLIST = "mobile.settings.addPlaylist"
    const val LIST = "mobile.settings.list"
    const val PIN_FIELD = "mobile.settings.pinField"
}

/**
 * Settings on phone before a [app.playlistplayer.feature.settings.SettingsViewModel] is wired:
 * "Add playlist" plus a note. The full screen is the `MobileSettingsScreen(viewModel, …)`
 * overload in `MobileSettingsContent.kt`; the wiring unit switches `MobileRoot` to it.
 */
@Composable
fun MobileSettingsScreen(onAddPlaylist: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.mobile_settings_playlists),
            style = MaterialTheme.typography.titleSmall,
            color = UiTokens.Accent,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS).semantics { heading() },
        )
        if (onAddPlaylist != null) {
            ListItem(
                leadingContent = { GlyphIcon("＋") },
                headlineContent = { Text(stringResource(R.string.settings_add_playlist)) },
                supportingContent = { Text(stringResource(R.string.settings_add_playlist_description)) },
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onAddPlaylist)
                    .testTag(MobileSettingsTags.ADD_PLAYLIST),
            )
        }
        HorizontalDivider()
        Text(
            text = stringResource(R.string.placeholder_settings_message),
            style = MaterialTheme.typography.bodyMedium,
            color = UiTokens.OnSurfaceMuted,
            modifier = Modifier.padding(MobileDimens.ScreenPadding),
        )
    }
}

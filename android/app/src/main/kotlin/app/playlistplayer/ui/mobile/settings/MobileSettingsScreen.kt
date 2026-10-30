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
}

/**
 * Settings on phone. Matches TV today: "Add playlist" plus the note that playlist, playback, guide
 * and parental settings arrive with the settings screens (U12 logic exists in `feature/settings`
 * but has no settings view model yet on either form factor).
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

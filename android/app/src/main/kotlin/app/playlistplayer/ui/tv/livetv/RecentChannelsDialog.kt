package app.playlistplayer.ui.tv.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.live.ChannelRef
import app.playlistplayer.feature.live.RecentChannel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.theme.TvDimens

/**
 * Menu of Live TV: the last watched channels (newest first; OK plays one) and, with several
 * playlists, a shortcut to the playlist order dialog.
 */
@Composable
fun RecentChannelsDialog(
    recent: List<RecentChannel>,
    onPlay: (ChannelRef) -> Unit,
    onOrderPlaylists: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = TvDimens.Shape,
            colors = SurfaceDefaults.colors(containerColor = UiTokens.SurfaceVariant),
        ) {
            Column(modifier = Modifier.padding(UiTokens.SpaceXl), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                Text(stringResource(R.string.livetv_recent_title), style = MaterialTheme.typography.headlineSmall, color = UiTokens.OnBackground)
                if (recent.isEmpty()) {
                    Text(stringResource(R.string.livetv_recent_empty), style = MaterialTheme.typography.bodyMedium, color = UiTokens.OnSurfaceMuted)
                }
                recent.forEachIndexed { index, channel ->
                    ListItem(
                        selected = false,
                        onClick = {
                            onDismiss()
                            onPlay(channel.ref)
                        },
                        headlineContent = { Text(channel.name, maxLines = 1) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM), modifier = Modifier.padding(top = 8.dp)) {
                    if (onOrderPlaylists != null) {
                        TvButton(
                            text = stringResource(R.string.livetv_order_title),
                            onClick = {
                                onDismiss()
                                onOrderPlaylists()
                            },
                            modifier = if (recent.isEmpty()) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                    TvButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
                }
            }
        }
    }
}

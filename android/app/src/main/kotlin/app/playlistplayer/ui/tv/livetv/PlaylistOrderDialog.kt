package app.playlistplayer.ui.tv.livetv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.playlistplayer.ui.tv.components.TvDialog as Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.theme.TvDimens

/** A playlist offered for ordering. */
data class OrderablePlaylist(val id: Long, val name: String)

/**
 * Lets the user order the playlists shown in Live TV. OK picks a playlist up, Up/Down moves it,
 * OK drops it; "Done" saves through [onSave] and closes.
 */
@Composable
fun PlaylistOrderDialog(
    playlists: List<OrderablePlaylist>,
    onSave: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var order by remember { mutableStateOf(playlists) }
    var grabbed by remember { mutableStateOf<Long?>(null) }
    val requesters = remember { mutableMapOf<Long, FocusRequester>() }
    var refocus by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(refocus, order) {
        refocus?.let { id -> runCatching { requesters[id]?.requestFocus() } }
    }
    LaunchedEffect(Unit) { runCatching { requesters[playlists.firstOrNull()?.id]?.requestFocus() } }

    fun move(id: Long, delta: Int) {
        val from = order.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in order.indices) return
        order = order.toMutableList().also { it.add(to, it.removeAt(from)) }
        refocus = id
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = TvDimens.Shape,
            colors = SurfaceDefaults.colors(containerColor = UiTokens.SurfaceVariant),
        ) {
            Column(modifier = Modifier.padding(UiTokens.SpaceXl), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                Text(stringResource(R.string.livetv_order_title), style = MaterialTheme.typography.headlineSmall, color = UiTokens.OnBackground)
                Text(stringResource(R.string.livetv_order_hint), style = MaterialTheme.typography.bodyMedium, color = UiTokens.OnSurfaceMuted)
                order.forEach { playlist ->
                    val requester = requesters.getOrPut(playlist.id) { FocusRequester() }
                    val picked = grabbed == playlist.id
                    ListItem(
                        selected = picked,
                        onClick = { grabbed = if (picked) null else playlist.id },
                        headlineContent = { Text(playlist.name, maxLines = 1) },
                        trailingContent = { if (picked) Text("↑↓") },
                        modifier = Modifier
                            .focusRequester(requester)
                            .onPreviewKeyEvent { event ->
                                if (!picked || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { move(playlist.id, -1); true }
                                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { move(playlist.id, 1); true }
                                    else -> false
                                }
                            },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM), modifier = Modifier.padding(top = 8.dp)) {
                    TvButton(text = stringResource(R.string.livetv_order_done), onClick = { onSave(order.map { it.id }); onDismiss() })
                    TvButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
                }
            }
        }
    }
}

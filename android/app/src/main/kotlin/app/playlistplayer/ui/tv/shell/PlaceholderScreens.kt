package app.playlistplayer.ui.tv.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.playlistplayer.R
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.ui.tv.components.TvEmptyState
import app.playlistplayer.ui.tv.theme.TvDimens

/**
 * Placeholder for destinations not built yet (Home U11, Live TV without a guide, Movies/Series U10, Search U11,
 * Settings U12). Uses the shared empty state; it is focusable so Back-to-rail and screen readers work.
 * Settings offers "Add playlist" when [onAddPlaylist] is set.
 */
@Composable
fun PlaceholderDestination(destination: Destination, modifier: Modifier = Modifier, onAddPlaylist: (() -> Unit)? = null) {
    val (title, message) = when (destination) {
        Destination.LiveTv -> R.string.nav_live to R.string.live_no_playlists
        Destination.Home -> R.string.placeholder_home_title to R.string.placeholder_home_message
        Destination.Movies -> R.string.placeholder_movies_title to R.string.placeholder_movies_message
        Destination.Series -> R.string.placeholder_series_title to R.string.placeholder_series_message
        Destination.Search -> R.string.placeholder_search_title to R.string.placeholder_search_message
        Destination.Settings -> R.string.placeholder_settings_title to R.string.placeholder_settings_message
    }
    val addPlaylist = onAddPlaylist.takeIf { destination == Destination.Settings }
    TvEmptyState(
        title = stringResource(title),
        message = stringResource(message),
        modifier = modifier.fillMaxSize().padding(TvDimens.ScreenPadding),
        actionLabel = addPlaylist?.let { stringResource(R.string.settings_add_playlist) },
        onAction = addPlaylist,
    )
}

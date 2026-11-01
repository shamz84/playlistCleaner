package app.playlistplayer.ui.mobile

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.playlistplayer.R
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.ui.common.EmptyState
import app.playlistplayer.ui.common.ErrorState
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.ui.common.UiStateContent
import app.playlistplayer.ui.common.UiTokens

@StringRes
internal fun Destination.mobileLabelRes(): Int = when (this) {
    Destination.LiveTv -> R.string.nav_live
    Destination.Home -> R.string.nav_home
    Destination.Movies -> R.string.nav_movies
    Destination.Series -> R.string.nav_series
    Destination.Search -> R.string.nav_search
    Destination.Settings -> R.string.nav_settings
}

/** Navigation glyphs (plain Unicode, no icon library dependency; same set as the TV rail). */
internal fun Destination.glyph(): String = when (this) {
    Destination.LiveTv -> "▶"
    Destination.Home -> "⌂"
    Destination.Movies -> "🎬"
    Destination.Series -> "🎞"
    Destination.Search -> "🔍"
    Destination.Settings -> "⚙"
}

/** A glyph used as an icon. Decorative: the enclosing control carries the content description. */
@Composable
internal fun GlyphIcon(glyph: String, modifier: Modifier = Modifier) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.clearAndSetSemantics { },
    )
}

@Composable
internal fun MobileLoading(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.common_loading)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
internal fun MobileEmpty(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        message = message,
        modifier = modifier,
        titleStyle = MaterialTheme.typography.titleLarge.copy(color = UiTokens.OnBackground),
        messageStyle = MaterialTheme.typography.bodyLarge.copy(color = UiTokens.OnSurfaceMuted),
        action = if (actionLabel != null && onAction != null) {
            { Button(onClick = onAction, modifier = Modifier.sizeIn(minHeight = MobileDimens.MinTouchTarget)) { Text(actionLabel) } }
        } else {
            null
        },
    )
}

@Composable
internal fun MobileError(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    ErrorState(
        message = message,
        modifier = modifier,
        titleStyle = MaterialTheme.typography.titleLarge.copy(color = UiTokens.Error),
        messageStyle = MaterialTheme.typography.bodyLarge.copy(color = UiTokens.OnSurfaceMuted),
        action = onRetry?.let { retry ->
            { Button(onClick = retry, modifier = Modifier.sizeIn(minHeight = MobileDimens.MinTouchTarget)) { Text(stringResource(R.string.common_retry)) } }
        },
    )
}

/** Renders a shared [UiState] with the phone's loading / empty / error views. */
@Composable
internal fun <T> MobileUiState(state: UiState<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    UiStateContent(
        state = state,
        loading = { MobileLoading(modifier) },
        empty = { reason -> MobileEmpty(title = reason, modifier = modifier) },
        error = { message, retry -> MobileError(message, modifier, retry) },
        content = content,
    )
}

/**
 * Destinations not built yet on either form factor (Home U11, Guide U7/U9, Search U11): the same
 * placeholder texts as TV, so parity holds until the shared feature lands.
 */
@Composable
internal fun MobilePlaceholder(destination: Destination, modifier: Modifier = Modifier) {
    val (title, message) = when (destination) {
        Destination.LiveTv -> R.string.nav_live to R.string.live_no_playlists
        Destination.Home -> R.string.placeholder_home_title to R.string.placeholder_home_message
        Destination.Movies -> R.string.placeholder_movies_title to R.string.placeholder_movies_message
        Destination.Series -> R.string.placeholder_series_title to R.string.placeholder_series_message
        Destination.Search -> R.string.placeholder_search_title to R.string.placeholder_search_message
        Destination.Settings -> R.string.placeholder_settings_title to R.string.placeholder_settings_message
    }
    MobileEmpty(title = stringResource(title), message = stringResource(message), modifier = modifier)
}

package app.playlistplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centered message for an empty screen or list. Library-neutral (foundation only) so the TV and
 * mobile trees share it; each tree supplies its own focusable [action] (e.g. a tv-material
 * Button) and text styles.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    titleStyle: TextStyle = DefaultTitleStyle,
    messageStyle: TextStyle = DefaultMessageStyle,
    action: (@Composable () -> Unit)? = null,
) {
    StateMessage(title, message, titleStyle, messageStyle, modifier, action)
}

/**
 * Centered error message with an optional retry [action]. The message is announced to
 * accessibility services when it appears.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleStyle: TextStyle = DefaultTitleStyle.copy(color = UiTokens.Error),
    messageStyle: TextStyle = DefaultMessageStyle,
    action: (@Composable () -> Unit)? = null,
) {
    StateMessage(
        title = title ?: message,
        message = if (title == null) null else message,
        titleStyle = titleStyle,
        messageStyle = messageStyle,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        action = action,
    )
}

/**
 * Renders [state]: [loading], [empty] and [error] slots for the non-content states, [content] for
 * the value.
 */
@Composable
fun <T> UiStateContent(
    state: UiState<T>,
    loading: @Composable () -> Unit,
    empty: @Composable (reason: String) -> Unit,
    error: @Composable (message: String, retry: (() -> Unit)?) -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> loading()
        is UiState.Empty -> empty(state.reason)
        is UiState.Error -> error(state.message, state.retry)
        is UiState.Content -> content(state.value)
    }
}

@Composable
private fun StateMessage(
    title: String,
    message: String?,
    titleStyle: TextStyle,
    messageStyle: TextStyle,
    modifier: Modifier,
    action: (@Composable () -> Unit)?,
) {
    Box(modifier = modifier.fillMaxSize().padding(UiTokens.SpaceXl), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            BasicText(text = title, style = titleStyle.copy(textAlign = TextAlign.Center))
            if (message != null) {
                BasicText(text = message, style = messageStyle.copy(textAlign = TextAlign.Center))
            }
            if (action != null) action()
        }
    }
}

private val DefaultTitleStyle: TextStyle @Composable get() = TextStyle(color = UiTokens.OnBackground, fontSize = 28.sp, lineHeight = 36.sp)
private val DefaultMessageStyle: TextStyle @Composable get() = TextStyle(color = UiTokens.OnSurfaceMuted, fontSize = 20.sp, lineHeight = 28.sp)

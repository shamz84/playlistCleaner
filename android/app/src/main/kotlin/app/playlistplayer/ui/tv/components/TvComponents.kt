package app.playlistplayer.ui.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.ui.common.EmptyState
import app.playlistplayer.ui.common.ErrorState
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.ui.common.UiStateContent
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.theme.TvDimens

/** tv-material Button with the app's focus treatment (scale + solid border) and a content description. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = text,
) {
    Button(
        onClick = onClick,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = UiTokens.FocusScale),
        border = ButtonDefaults.border(focusedBorder = TvDimens.FocusBorder),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Single-line text field for the on-screen keyboard: foundation [BasicTextField] (tv-material has
 * no text field) with a label, a visible focus border and IME action wiring.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle? = null,
    supportingText: String? = null,
    supportingColor: Color = UiTokens.OnSurfaceMuted,
    contentDescription: String = label,
    emptyPlaceholder: String = stringResource(R.string.onboarding_field_hint_empty),
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = (textStyle ?: MaterialTheme.typography.bodyLarge).copy(color = UiTokens.OnBackground)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall, color = UiTokens.OnSurfaceMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .scale(if (focused) 1.02f else 1f)
                .background(if (focused) UiTokens.SurfaceFocused else UiTokens.Surface, TvDimens.Shape)
                .border(
                    BorderStroke(
                        if (focused) UiTokens.FocusBorderWidth else UiTokens.FocusBorderWidth / 3,
                        if (focused) UiTokens.FocusBorder else UiTokens.SurfaceVariant,
                    ),
                    TvDimens.Shape,
                )
                .padding(horizontal = UiTokens.SpaceM, vertical = UiTokens.SpaceM)
                .semantics { this.contentDescription = contentDescription },
            textStyle = style,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            cursorBrush = SolidColor(UiTokens.Accent),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(text = emptyPlaceholder, style = style.copy(color = UiTokens.OnSurfaceMuted))
                }
                inner()
            },
        )
        if (supportingText != null) {
            Text(text = supportingText, style = MaterialTheme.typography.bodySmall, color = supportingColor)
        }
    }
}

/** Shared [EmptyState] with TV typography and an optional tv-material action button. */
@Composable
fun TvEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    focusable: Boolean = actionLabel == null,
) {
    // Without an action the message itself takes focus, so the screen is reachable by D-pad and
    // read out by screen readers.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val base = if (focusable) {
        modifier
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, message).joinToString(". ") }
            .focusable(interactionSource = interaction)
            .padding(UiTokens.SpaceM)
            .border(
                BorderStroke(UiTokens.FocusBorderWidth, if (focused) UiTokens.FocusBorder else Color.Transparent),
                TvDimens.Shape,
            )
    } else {
        modifier
    }
    EmptyState(
        title = title,
        message = message,
        modifier = base,
        titleStyle = MaterialTheme.typography.headlineSmall.copy(color = UiTokens.OnBackground),
        messageStyle = MaterialTheme.typography.bodyLarge.copy(color = UiTokens.OnSurfaceMuted),
        action = if (actionLabel != null && onAction != null) {
            { TvButton(text = actionLabel, onClick = onAction) }
        } else {
            null
        },
    )
}

/** Shared [ErrorState] with TV typography and a Retry button when [onRetry] is set. */
@Composable
fun TvErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    ErrorState(
        message = message,
        modifier = modifier,
        titleStyle = MaterialTheme.typography.headlineSmall.copy(color = UiTokens.Error),
        messageStyle = MaterialTheme.typography.bodyLarge.copy(color = UiTokens.OnSurfaceMuted),
        action = onRetry?.let { retry -> { TvButton(text = stringResource(R.string.common_retry), onClick = retry) } },
    )
}

@Composable
fun TvLoadingState(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.common_loading),
        modifier = modifier,
        titleStyle = MaterialTheme.typography.titleLarge.copy(color = UiTokens.OnSurfaceMuted),
    )
}

/** Renders a [UiState] with the TV loading / empty / error components. */
@Composable
fun <T> TvUiState(state: UiState<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    UiStateContent(
        state = state,
        loading = { TvLoadingState(modifier) },
        empty = { reason -> TvEmptyState(title = reason, modifier = modifier) },
        error = { message, retry -> TvErrorState(message = message, modifier = modifier, onRetry = retry) },
        content = content,
    )
}

/** Small rounded label, e.g. "Catch-up". */
@Composable
fun TvBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(UiTokens.SurfaceVariant, TvDimens.Shape)
            .padding(horizontal = UiTokens.SpaceS, vertical = UiTokens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = UiTokens.OnBackground)
    }
}

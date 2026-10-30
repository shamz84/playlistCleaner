package app.playlistplayer.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.settings.PinFormat
import app.playlistplayer.feature.settings.PinPromptRequest
import app.playlistplayer.feature.settings.SettingsTexts
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton

object TvSettingsTags {
    const val PIN_PAD = "tv.settings.pinPad"
    const val SECTIONS = "tv.settings.sections"
    const val CONTENT = "tv.settings.content"
}

private val DIGIT_KEYS: Map<Key, Char> = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)

/**
 * Numeric PIN pad for the remote: a 3×4 grid of focusable keys (1–9, Delete, 0, OK) plus
 * hardware digit keys. The PIN is shown as dots only and lives in composition state.
 */
@Composable
internal fun TvPinPad(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester? = null,
    resetKey: Any? = null,
) {
    var pin by remember(resetKey) { mutableStateOf("") }
    fun add(c: Char) {
        if (pin.length < PinFormat.MAX_LENGTH) pin += c
    }
    val entered = stringResource(R.string.settings_pin_entered, pin.length)
    Column(
        modifier = modifier
            .testTag(TvSettingsTags.PIN_PAD)
            .onPreviewKeyEvent { event ->
                val digit = DIGIT_KEYS[event.key]
                when {
                    event.type != KeyEventType.KeyDown -> false
                    digit != null -> {
                        add(digit)
                        true
                    }
                    event.key == Key.Backspace || event.key == Key.Delete -> {
                        pin = pin.dropLast(1)
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
    ) {
        Text(
            text = if (pin.isEmpty()) "–" else "●".repeat(pin.length),
            style = MaterialTheme.typography.headlineMedium,
            color = UiTokens.OnBackground,
            modifier = Modifier.semantics { contentDescription = entered },
        )
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                row.forEachIndexed { c, digit ->
                    val focusModifier = if (r == 0 && c == 0 && initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier
                    TvButton(text = digit.toString(), onClick = { add(digit) }, modifier = focusModifier.width(KEY_WIDTH))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            TvButton(text = "⌫", onClick = { pin = pin.dropLast(1) }, modifier = Modifier.width(KEY_WIDTH), contentDescription = stringResource(R.string.settings_pin_delete))
            TvButton(text = "0", onClick = { add('0') }, modifier = Modifier.width(KEY_WIDTH))
            TvButton(
                text = stringResource(R.string.settings_pin_ok),
                onClick = {
                    val value = pin
                    pin = ""
                    onSubmit(value)
                },
                enabled = pin.length >= PinFormat.MIN_LENGTH,
                modifier = Modifier.width(KEY_WIDTH),
            )
        }
    }
}

private val KEY_WIDTH = 88.dp

/** The parental gate prompt raised by [app.playlistplayer.feature.settings.ParentalPlaylistAuthorizer]. */
@Composable
internal fun TvPinPromptDialog(request: PinPromptRequest, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    TvSettingsDialog(title = stringResource(R.string.settings_pin_prompt_title), onDismiss = onCancel) { focus ->
        Text(stringResource(SettingsTexts.action(request.action)), style = MaterialTheme.typography.bodyLarge, color = UiTokens.OnSurfaceMuted)
        SettingsTexts.authorization(request.reason)?.let { TvMutedText(it.resolve(), color = UiTokens.Error) }
        TvPinPad(onSubmit = onSubmit, initialFocus = focus, resetKey = request.id)
        TvButton(text = stringResource(R.string.settings_cancel), onClick = onCancel)
    }
}

/** Which PIN flow the editor runs. */
internal enum class PinEditorMode { Set, Change, Remove }

/**
 * Set / change / remove the PIN in steps (current → new → confirm), one PIN pad per step.
 * [onDone] receives (current, new, confirm); unused values are empty.
 */
@Composable
internal fun TvPinEditorDialog(mode: PinEditorMode, onDone: (current: String, new: String, confirm: String) -> Unit, onCancel: () -> Unit) {
    val steps = when (mode) {
        PinEditorMode.Set -> listOf(R.string.settings_pin_enter_new, R.string.settings_pin_enter_confirm)
        PinEditorMode.Change -> listOf(R.string.settings_pin_enter_current, R.string.settings_pin_enter_new, R.string.settings_pin_enter_confirm)
        PinEditorMode.Remove -> listOf(R.string.settings_pin_enter_current)
    }
    var step by rememberSaveable(mode) { mutableStateOf(0) }
    val values = remember(mode) { ArrayList<String>() }
    val title = stringResource(
        when (mode) {
            PinEditorMode.Set -> R.string.settings_pin_set
            PinEditorMode.Change -> R.string.settings_pin_change
            PinEditorMode.Remove -> R.string.settings_pin_remove
        },
    )
    TvSettingsDialog(title = title, onDismiss = onCancel) { focus ->
        Text(stringResource(steps[step]), style = MaterialTheme.typography.bodyLarge, color = UiTokens.OnSurfaceMuted)
        TvPinPad(
            initialFocus = focus,
            resetKey = step,
            onSubmit = { pin ->
                values += pin
                if (step < steps.lastIndex) {
                    step++
                } else {
                    when (mode) {
                        PinEditorMode.Set -> onDone("", values[0], values[1])
                        PinEditorMode.Change -> onDone(values[0], values[1], values[2])
                        PinEditorMode.Remove -> onDone(values[0], "", "")
                    }
                }
            },
        )
        TvButton(text = stringResource(R.string.settings_cancel), onClick = onCancel)
    }
}

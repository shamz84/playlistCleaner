package app.playlistplayer.ui.mobile.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.feature.onboarding.DriveIdValidation
import app.playlistplayer.feature.onboarding.OnboardingPhase
import app.playlistplayer.feature.onboarding.OnboardingStage
import app.playlistplayer.feature.onboarding.OnboardingText
import app.playlistplayer.feature.onboarding.OnboardingUiState
import app.playlistplayer.feature.onboarding.OnboardingViewModel
import app.playlistplayer.feature.onboarding.SourceType
import app.playlistplayer.feature.onboarding.StageStatus
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import kotlinx.coroutines.launch

object MobileOnboardingTags {
    const val NAME = "mobile.onboarding.name"
    const val SOURCE = "mobile.onboarding.source"
    const val FIELD = "mobile.onboarding.field"
    const val PASTE = "mobile.onboarding.paste"
    const val XTREAM = "mobile.onboarding.xtream"
    const val CONTINUE = "mobile.onboarding.continue"
    const val READBACK = "mobile.onboarding.readback"
    const val START_WATCHING = "mobile.onboarding.startWatching"
}

/**
 * Phone onboarding / "Add playlist" (R1, R23, R24) over the shared [OnboardingViewModel]: the same
 * form, validation, read-back and progress semantics as TV, with touch input and a Paste button
 * (phones have a clipboard, so a Drive share link or M3U URL can be pasted whole; the view model
 * extracts the file ID from a link).
 *
 * @param onCancel set for "Add playlist" (Back / Cancel on the form closes it); null on first run,
 *   where Back on the form is left to the system (the app closes).
 */
@Composable
fun MobileOnboardingScreen(
    viewModel: OnboardingViewModel,
    isFirstRun: Boolean,
    onFinished: (playlistId: Long?) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.phase == OnboardingPhase.Progress) { viewModel.onBack() }
    BackHandler(enabled = state.phase == OnboardingPhase.Form && onCancel != null) { onCancel?.invoke() }
    LaunchedEffect(state.phase) {
        if (state.phase == OnboardingPhase.Finished) onFinished(state.playlistId)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(UiTokens.Background)
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(MobileDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            when (state.phase) {
                OnboardingPhase.Form -> Form(state, isFirstRun, viewModel, onCancel)
                OnboardingPhase.Progress, OnboardingPhase.Finished -> Progress(state, viewModel)
            }
        }
    }
}

@Composable
private fun Form(state: OnboardingUiState, isFirstRun: Boolean, viewModel: OnboardingViewModel, onCancel: (() -> Unit)?) {
    Text(
        text = stringResource(if (isFirstRun) R.string.onboarding_welcome_title else R.string.onboarding_add_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(text = stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyLarge, color = UiTokens.OnSurfaceMuted)

    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::onNameChanged,
        label = { Text(stringResource(R.string.onboarding_name_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(MobileOnboardingTags.NAME),
    )

    SourceTypeSelector(state.sourceType, viewModel::onSourceTypeChanged)

    when (state.sourceType) {
        SourceType.DriveId -> DriveField(state, viewModel)
        SourceType.M3uUrl -> M3uField(state, viewModel)
    }

    XtreamToggle(state, viewModel::onXtreamToggled)

    state.error?.let { error ->
        Text(
            text = stringResource(OnboardingText.errorMessage(error)),
            style = MaterialTheme.typography.bodyLarge,
            color = UiTokens.Error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
        val continueDescription = stringResource(
            if (state.canSubmit) R.string.onboarding_continue else R.string.onboarding_continue_disabled_description,
        )
        Button(
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            modifier = Modifier
                .sizeIn(minHeight = MobileDimens.MinTouchTarget)
                .testTag(MobileOnboardingTags.CONTINUE)
                .semantics { contentDescription = continueDescription },
        ) { Text(stringResource(R.string.onboarding_continue)) }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.sizeIn(minHeight = MobileDimens.MinTouchTarget)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceTypeSelector(selected: SourceType, onChange: (SourceType) -> Unit) {
    val options = listOf(SourceType.DriveId to R.string.onboarding_source_drive, SourceType.M3uUrl to R.string.onboarding_source_m3u)
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        Text(stringResource(R.string.onboarding_source_label), style = MaterialTheme.typography.titleSmall, color = UiTokens.OnSurfaceMuted)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag(MobileOnboardingTags.SOURCE)) {
            options.forEachIndexed { index, (type, label) ->
                SegmentedButton(
                    selected = selected == type,
                    onClick = { onChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.sizeIn(minHeight = MobileDimens.MinTouchTarget),
                ) { Text(stringResource(label)) }
            }
        }
    }
}

/** Reads plain text from the clipboard into [onText]. */
@Composable
private fun PasteButton(onText: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val description = stringResource(R.string.mobile_paste_description)
    TextButton(
        onClick = {
            scope.launch {
                val clip = runCatching { clipboard.getClipEntry()?.clipData }.getOrNull()
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrBlank()) onText(text.trim())
            }
        },
        modifier = Modifier
            .sizeIn(minWidth = MobileDimens.MinTouchTarget, minHeight = MobileDimens.MinTouchTarget)
            .testTag(MobileOnboardingTags.PASTE)
            .semantics { contentDescription = description },
    ) { Text(stringResource(R.string.mobile_paste)) }
}

@Composable
private fun DriveField(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val idLength = state.driveId?.length ?: 0
    val hint = if (OnboardingText.driveHintTakesLength(state.driveValidation)) {
        stringResource(OnboardingText.driveHint(state.driveValidation), idLength)
    } else {
        stringResource(OnboardingText.driveHint(state.driveValidation))
    }
    val hintColor = when (state.driveValidation) {
        DriveIdValidation.Valid -> UiTokens.Success
        DriveIdValidation.Empty -> UiTokens.OnSurfaceMuted
        else -> UiTokens.Warning
    }
    OutlinedTextField(
        value = state.driveText,
        onValueChange = viewModel::onDriveTextChanged,
        label = { Text(stringResource(R.string.onboarding_drive_label)) },
        supportingText = { Text(hint, color = hintColor) },
        trailingIcon = { PasteButton(viewModel::onDriveTextChanged) },
        isError = state.driveValidation != DriveIdValidation.Empty && !state.driveValidation.isValid,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().testTag(MobileOnboardingTags.FIELD),
    )
    state.driveIdForDisplay?.let { grouped ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UiTokens.Surface, MaterialTheme.shapes.medium)
                .padding(UiTokens.SpaceM)
                .testTag(MobileOnboardingTags.READBACK),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
        ) {
            Text(stringResource(R.string.onboarding_drive_readback_label), style = MaterialTheme.typography.labelMedium, color = UiTokens.OnSurfaceMuted)
            // Large, unmasked, monospace and grouped so the ID can be checked character by character.
            Text(
                text = grouped,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (state.driveIdFromLink) {
                Text(stringResource(R.string.onboarding_drive_from_link), style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
            }
            if (state.hasResourceKey) {
                Text(stringResource(R.string.onboarding_drive_resource_key), style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
            }
        }
    }
}

@Composable
private fun M3uField(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    OutlinedTextField(
        value = state.m3uText,
        onValueChange = viewModel::onM3uTextChanged,
        label = { Text(stringResource(R.string.onboarding_m3u_label)) },
        supportingText = {
            Text(
                stringResource(OnboardingText.m3uHint(state.m3uValidation)),
                color = if (state.m3uValidation.isValid) UiTokens.Success else UiTokens.OnSurfaceMuted,
            )
        },
        trailingIcon = { PasteButton(viewModel::onM3uTextChanged) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().testTag(MobileOnboardingTags.FIELD),
    )
}

@Composable
private fun XtreamToggle(state: OnboardingUiState, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.onboarding_xtream_title)) },
        supportingContent = { Text(stringResource(OnboardingText.xtreamExplanation(state))) },
        // Display only; the whole row is the toggle target (with switch semantics).
        trailingContent = { Switch(checked = state.xtreamEnabled, onCheckedChange = null, enabled = state.xtreamToggleEnabled) },
        colors = ListItemDefaults.colors(containerColor = UiTokens.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = state.xtreamEnabled,
                enabled = state.xtreamToggleEnabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .testTag(MobileOnboardingTags.XTREAM),
    )
}

@Composable
private fun Progress(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text(text = stringResource(R.string.onboarding_progress_title, state.effectiveName), style = MaterialTheme.typography.headlineMedium)
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
        OnboardingStage.entries.forEach { stage -> StageRow(stage, state.stages[stage] ?: StageStatus.Pending) }
    }
    if (state.canContinue) {
        Text(stringResource(R.string.onboarding_channels_ready), style = MaterialTheme.typography.bodyLarge)
    }
    if (state.xtreamTurnedOff) {
        Text(stringResource(R.string.onboarding_xtream_turned_off), style = MaterialTheme.typography.bodyLarge, color = UiTokens.Warning)
    }
    state.error?.let { error ->
        Text(
            text = stringResource(OnboardingText.errorMessage(error)),
            style = MaterialTheme.typography.bodyLarge,
            color = UiTokens.Warning,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
        Button(
            onClick = { viewModel.continueToApp() },
            enabled = state.canContinue,
            modifier = Modifier.sizeIn(minHeight = MobileDimens.MinTouchTarget).testTag(MobileOnboardingTags.START_WATCHING),
        ) { Text(stringResource(R.string.onboarding_start_watching)) }
        if (!state.canContinue) {
            OutlinedButton(onClick = { viewModel.onBack() }, modifier = Modifier.sizeIn(minHeight = MobileDimens.MinTouchTarget)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@Composable
private fun StageRow(stage: OnboardingStage, status: StageStatus) {
    val name = stringResource(OnboardingText.stageName(stage))
    val statusText = stringResource(OnboardingText.stageStatus(status))
    val description = stringResource(R.string.onboarding_stage_description, name, statusText)
    val (glyph, color) = when (status) {
        StageStatus.Pending -> "○" to UiTokens.OnSurfaceMuted
        StageStatus.Running -> "◐" to UiTokens.Accent
        StageStatus.Done -> "✓" to UiTokens.Success
        StageStatus.Skipped -> "–" to UiTokens.OnSurfaceMuted
        StageStatus.Failed -> "✕" to UiTokens.Error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MobileDimens.MinTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.width(28.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(statusText, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

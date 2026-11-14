package app.playlistplayer.ui.tv.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
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
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.components.TvTextField
import app.playlistplayer.ui.tv.theme.TvDimens

/** Test tags used by UI tests. */
object OnboardingTags {
    const val NAME = "onboarding.name"
    const val SOURCE_SWITCH = "onboarding.source"
    const val SOURCE_FIELD = "onboarding.field"
    const val XTREAM_TOGGLE = "onboarding.xtream"
    const val EPG_TOGGLE = "onboarding.epg"
    const val CONTINUE = "onboarding.continue"
    const val ID_READBACK = "onboarding.readback"
    const val START_WATCHING = "onboarding.startWatching"
}

/** Callbacks of the onboarding UI, so [OnboardingContent] stays stateless. */
data class OnboardingActions(
    val onNameChanged: (String) -> Unit,
    val onSourceTypeChanged: (SourceType) -> Unit,
    val onDriveTextChanged: (String) -> Unit,
    val onM3uTextChanged: (String) -> Unit,
    val onXtreamToggled: (Boolean) -> Unit,
    val onSubmit: () -> Unit,
    val onContinue: () -> Unit,
    val onCancelProgress: () -> Unit,
    val onCuratedEpgToggled: (Boolean) -> Unit = {},
)

/**
 * Onboarding / "Add playlist" screen (R1, R23, R24).
 *
 * @param isFirstRun shows the welcome title; Back on the form is left to the host (the activity
 *   exits on first run).
 * @param onCancel when set (Add playlist from Settings), Back on the form calls it.
 * @param onFinished called once the user continues into the app.
 */
@Composable
fun OnboardingScreen(
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
    OnboardingContent(
        state = state,
        isFirstRun = isFirstRun,
        modifier = modifier,
        actions = remember(viewModel) {
            OnboardingActions(
                onNameChanged = viewModel::onNameChanged,
                onSourceTypeChanged = viewModel::onSourceTypeChanged,
                onDriveTextChanged = viewModel::onDriveTextChanged,
                onM3uTextChanged = viewModel::onM3uTextChanged,
                onXtreamToggled = viewModel::onXtreamToggled,
                onSubmit = viewModel::submit,
                onContinue = { viewModel.continueToApp() },
                onCancelProgress = { viewModel.onBack() },
                onCuratedEpgToggled = viewModel::onCuratedEpgToggled,
            )
        },
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    isFirstRun: Boolean,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(UiTokens.Background)
            .padding(TvDimens.ScreenPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (state.phase) {
            OnboardingPhase.Form -> OnboardingForm(state, isFirstRun, actions)
            OnboardingPhase.Progress, OnboardingPhase.Finished -> OnboardingProgressView(state, actions)
        }
    }
}

@Composable
private fun OnboardingForm(state: OnboardingUiState, isFirstRun: Boolean, actions: OnboardingActions) {
    val focusManager = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
    val moveDown = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })

    Column(
        modifier = Modifier
            .widthIn(max = 960.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceL),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            Text(
                text = stringResource(if (isFirstRun) R.string.onboarding_welcome_title else R.string.onboarding_add_title),
                style = MaterialTheme.typography.headlineLarge,
                color = UiTokens.OnBackground,
            )
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = UiTokens.OnSurfaceMuted,
            )
        }

        // 1. Name
        TvTextField(
            value = state.name,
            onValueChange = actions.onNameChanged,
            label = stringResource(R.string.onboarding_name_label),
            modifier = Modifier.focusRequester(nameFocus).testTag(OnboardingTags.NAME),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = moveDown,
        )

        // 2. Source type switch
        SourceTypeSwitch(state.sourceType, actions.onSourceTypeChanged)

        // 3. Source field (Drive ID / share link, or M3U URL)
        when (state.sourceType) {
            SourceType.DriveId -> DriveField(state, actions, moveDown)
            SourceType.M3uUrl -> M3uField(state, actions, moveDown)
        }

        // 4. Xtream toggle
        XtreamToggle(state, actions.onXtreamToggled)
        CuratedEpgToggle(state, actions.onCuratedEpgToggled)

        // Error from the last attempt (the user stays on the form to fix the input).
        state.error?.let { error ->
            Text(
                text = stringResource(OnboardingText.errorMessage(error)),
                style = MaterialTheme.typography.bodyLarge,
                color = UiTokens.Error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        // 5. Continue
        TvButton(
            text = stringResource(R.string.onboarding_continue),
            onClick = actions.onSubmit,
            enabled = state.canSubmit,
            contentDescription = stringResource(
                if (state.canSubmit) R.string.onboarding_continue else R.string.onboarding_continue_disabled_description,
            ),
            modifier = Modifier.testTag(OnboardingTags.CONTINUE),
        )
        Spacer(Modifier.padding(bottom = UiTokens.SpaceL))
    }
}

@Composable
private fun SourceTypeSwitch(selected: SourceType, onChange: (SourceType) -> Unit) {
    val driveLabel = stringResource(R.string.onboarding_source_drive)
    val m3uLabel = stringResource(R.string.onboarding_source_m3u)
    val selectedLabel = if (selected == SourceType.DriveId) driveLabel else m3uLabel
    val description = stringResource(R.string.onboarding_source_description, selectedLabel)
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        Text(stringResource(R.string.onboarding_source_label), style = MaterialTheme.typography.titleSmall, color = UiTokens.OnSurfaceMuted)
        Surface(
            onClick = { onChange(if (selected == SourceType.DriveId) SourceType.M3uUrl else SourceType.DriveId) },
            modifier = Modifier
                .testTag(OnboardingTags.SOURCE_SWITCH)
                .semantics(mergeDescendants = true) { contentDescription = description },
            shape = ClickableSurfaceDefaults.shape(TvDimens.Shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = UiTokens.FocusScale),
            border = ClickableSurfaceDefaults.border(focusedBorder = TvDimens.FocusBorder),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = UiTokens.Surface,
                focusedContainerColor = UiTokens.SurfaceFocused,
            ),
        ) {
            Row(
                modifier = Modifier.padding(UiTokens.SpaceS),
                horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
            ) {
                SegmentLabel(driveLabel, selected == SourceType.DriveId)
                SegmentLabel(m3uLabel, selected == SourceType.M3uUrl)
            }
        }
    }
}

@Composable
private fun SegmentLabel(text: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .background(if (selected) UiTokens.Accent else UiTokens.Surface, TvDimens.Shape)
            .padding(horizontal = UiTokens.SpaceL, vertical = UiTokens.SpaceS),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) UiTokens.OnAccent else UiTokens.OnSurfaceMuted,
        )
    }
}

@Composable
private fun DriveField(state: OnboardingUiState, actions: OnboardingActions, moveDown: KeyboardActions) {
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
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
        TvTextField(
            value = state.driveText,
            onValueChange = actions.onDriveTextChanged,
            label = stringResource(R.string.onboarding_drive_label),
            modifier = Modifier.testTag(OnboardingTags.SOURCE_FIELD),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = moveDown,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            supportingText = hint,
            supportingColor = hintColor,
        )
        state.driveIdForDisplay?.let { grouped ->
            Column(
                modifier = Modifier
                    .background(UiTokens.Surface, TvDimens.Shape)
                    .padding(UiTokens.SpaceM)
                    .testTag(OnboardingTags.ID_READBACK),
                verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_drive_readback_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = UiTokens.OnSurfaceMuted,
                )
                // Large, unmasked, monospace and grouped so the ID can be checked character by character.
                Text(
                    text = grouped,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 32.sp,
                        lineHeight = 42.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = UiTokens.OnBackground,
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
}

@Composable
private fun M3uField(state: OnboardingUiState, actions: OnboardingActions, moveDown: KeyboardActions) {
    TvTextField(
        value = state.m3uText,
        onValueChange = actions.onM3uTextChanged,
        label = stringResource(R.string.onboarding_m3u_label),
        modifier = Modifier.testTag(OnboardingTags.SOURCE_FIELD),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = moveDown,
        supportingText = stringResource(OnboardingText.m3uHint(state.m3uValidation)),
        supportingColor = if (state.m3uValidation.isValid) UiTokens.Success else UiTokens.OnSurfaceMuted,
    )
}

@Composable
private fun XtreamToggle(state: OnboardingUiState, onToggle: (Boolean) -> Unit) {
    val title = stringResource(R.string.onboarding_xtream_title)
    val explanation = stringResource(OnboardingText.xtreamExplanation(state))
    Surface(
        onClick = { onToggle(!state.xtreamRequested) },
        enabled = state.xtreamToggleEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTags.XTREAM_TOGGLE)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $explanation"
                stateDescription = if (state.xtreamEnabled) "on" else "off"
            },
        shape = ClickableSurfaceDefaults.shape(TvDimens.Shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(focusedBorder = TvDimens.FocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = UiTokens.Surface,
            focusedContainerColor = UiTokens.SurfaceFocused,
            disabledContainerColor = UiTokens.Surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(UiTokens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = UiTokens.OnBackground)
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
            }
            // Display only; the whole row is the focus target.
            Switch(checked = state.xtreamEnabled, onCheckedChange = null, enabled = state.xtreamToggleEnabled)
        }
    }
}

@Composable
private fun CuratedEpgToggle(state: OnboardingUiState, onToggle: (Boolean) -> Unit) {
    val title = stringResource(R.string.onboarding_epg_title)
    val explanation = stringResource(if (state.useCuratedEpg) R.string.onboarding_epg_on else R.string.onboarding_epg_off)
    Surface(
        onClick = { onToggle(!state.useCuratedEpg) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTags.EPG_TOGGLE)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $explanation"
                stateDescription = if (state.useCuratedEpg) "on" else "off"
            },
        shape = ClickableSurfaceDefaults.shape(TvDimens.Shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(focusedBorder = TvDimens.FocusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = UiTokens.Surface,
            focusedContainerColor = UiTokens.SurfaceFocused,
        ),
    ) {
        Row(
            modifier = Modifier.padding(UiTokens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = UiTokens.OnBackground)
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
            }
            Switch(checked = state.useCuratedEpg, onCheckedChange = null)
        }
    }
}

@Composable
private fun OnboardingProgressView(state: OnboardingUiState, actions: OnboardingActions) {
    val startFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(state.canContinue) {
        runCatching { if (state.canContinue) startFocus.requestFocus() else cancelFocus.requestFocus() }
    }
    Column(
        modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceL),
    ) {
        Text(
            text = stringResource(R.string.onboarding_progress_title, state.effectiveName),
            style = MaterialTheme.typography.headlineLarge,
            color = UiTokens.OnBackground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM), verticalAlignment = Alignment.CenterVertically) {
            if (!state.allStagesTerminal && state.error == null) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), color = UiTokens.Accent, strokeWidth = 3.dp)
                Text(
                    text = stringResource(R.string.onboarding_please_wait),
                    style = MaterialTheme.typography.bodyLarge,
                    color = UiTokens.OnBackground,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            OnboardingStage.entries.forEach { stage ->
                StageRow(stage, state.stages[stage] ?: StageStatus.Pending)
            }
        }
        if (state.canContinue) {
            Text(stringResource(R.string.onboarding_channels_ready), style = MaterialTheme.typography.bodyLarge, color = UiTokens.OnBackground)
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
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            TvButton(
                text = stringResource(R.string.onboarding_start_watching),
                onClick = actions.onContinue,
                enabled = state.canContinue,
                modifier = Modifier.focusRequester(startFocus).testTag(OnboardingTags.START_WATCHING),
            )
            if (!state.canContinue) {
                TvButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = actions.onCancelProgress,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
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
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.width(32.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = UiTokens.OnBackground, modifier = Modifier.width(320.dp))
        Text(statusText, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

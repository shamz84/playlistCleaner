package app.playlistplayer.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.model.Playlist
import app.playlistplayer.core.sync.epg.EpgOverrideSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SourceType { DriveId, M3uUrl }

/** Whether the "Process Xtream account" toggle can be on (R24). */
enum class XtreamAvailability {
    /** The M3U URL is Xtream-shaped (`/live/u/p/` or `get.php?username=&password=`). */
    Available,

    /** Drive source (or an M3U URL not yet valid): shape unknown until download; the sync turns it off if needed. */
    UnknownUntilDownload,

    /** The M3U URL is not Xtream-shaped: toggle disabled and off, with an explanation. */
    NotXtreamShaped,
}

enum class OnboardingPhase {
    /** Entering name, source and options. Failures before channels are usable return here. */
    Form,

    /** Add in flight; per-stage progress shown. */
    Progress,

    /** The user continued into the app. */
    Finished,
}

data class OnboardingUiState(
    val name: String = Playlist.DEFAULT_NAME,
    val sourceType: SourceType = SourceType.DriveId,
    val driveText: String = "",
    val m3uText: String = "",
    val driveInput: DriveFieldInput = DriveFieldInput.Empty,
    val driveValidation: DriveIdValidation = DriveIdValidation.Empty,
    val m3uValidation: M3uUrlValidation = M3uUrlValidation.Empty,
    /** The user's toggle choice; the effective value is [xtreamEnabled]. */
    val xtreamRequested: Boolean = true,
    val xtreamAvailability: XtreamAvailability = XtreamAvailability.UnknownUntilDownload,
    val phase: OnboardingPhase = OnboardingPhase.Form,
    val stages: Map<OnboardingStage, StageStatus> = initialStages(xtreamEnabled = true),
    val error: OnboardingError? = null,
    val playlistId: Long? = null,
    /** True when Xtream was requested but the downloaded playlist is not Xtream-shaped (sync turned it off). */
    val xtreamTurnedOff: Boolean = false,
    /** Use the curated guide as this playlist's EPG override instead of the provider guide. */
    val useCuratedEpg: Boolean = true,
) {
    /** The Drive file ID read from the field (bare or extracted from a link), unvalidated. */
    val driveId: String? get() = (driveInput as? DriveFieldInput.Parsed)?.id

    val driveIdFromLink: Boolean get() = (driveInput as? DriveFieldInput.Parsed)?.fromLink == true

    val hasResourceKey: Boolean get() = (driveInput as? DriveFieldInput.Parsed)?.resourceKey != null

    /** [driveId] grouped for large monospace read-back, or null when there is none. */
    val driveIdForDisplay: String? get() = driveId?.let { DriveIdRules.groupForDisplay(it) }

    val xtreamToggleEnabled: Boolean
        get() = phase == OnboardingPhase.Form && xtreamAvailability != XtreamAvailability.NotXtreamShaped

    val xtreamEnabled: Boolean
        get() = xtreamRequested && xtreamAvailability != XtreamAvailability.NotXtreamShaped

    val isSourceValid: Boolean
        get() = when (sourceType) {
            SourceType.DriveId -> driveValidation.isValid
            SourceType.M3uUrl -> m3uValidation.isValid
        }

    val canSubmit: Boolean get() = phase == OnboardingPhase.Form && isSourceValid

    /** Channels are usable once the Channels stage is done; the catalog and guide continue in the background. */
    val canContinue: Boolean
        get() = phase == OnboardingPhase.Progress && stages[OnboardingStage.Channels] == StageStatus.Done

    val allStagesTerminal: Boolean get() = stages.values.all { it.isTerminal }

    /** The playlist name submitted: the trimmed field, or the default when blank. */
    val effectiveName: String get() = name.trim().ifEmpty { Playlist.DEFAULT_NAME }

    fun sourceInput(): SourceInput? = when (sourceType) {
        SourceType.DriveId -> (driveInput as? DriveFieldInput.Parsed)
            ?.takeIf { driveValidation.isValid }
            ?.let { SourceInput.DriveId(it.id, it.resourceKey) }
        SourceType.M3uUrl -> m3uText.trim().takeIf { m3uValidation.isValid }?.let { SourceInput.M3uUrl(it) }
    }

    override fun toString(): String =
        "OnboardingUiState(sourceType=$sourceType, driveValidation=$driveValidation, m3uValidation=$m3uValidation, " +
            "xtreamEnabled=$xtreamEnabled, phase=$phase, stages=$stages, error=$error, playlistId=$playlistId)"
}

internal fun initialStages(xtreamEnabled: Boolean): Map<OnboardingStage, StageStatus> =
    OnboardingStage.entries.associateWith { stage ->
        if (!xtreamEnabled && (stage == OnboardingStage.Catalog || stage == OnboardingStage.Guide)) {
            StageStatus.Skipped
        } else {
            StageStatus.Pending
        }
    }

/**
 * Onboarding / "Add playlist" state machine (R1, R23, R24; U9, U15). Pure Kotlin apart from the
 * `ViewModel` base; [externalScope] is for tests.
 *
 * @param isXtreamShaped whether an M3U URL has an Xtream shape; the wiring passes
 *   `{ AccountExtractor.fromUrl(it) != null }` from `:core:parsing`.
 */
class OnboardingViewModel(
    private val onboarder: PlaylistOnboarder,
    private val isXtreamShaped: (String) -> Boolean,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = externalScope ?: viewModelScope
    private val _state = MutableStateFlow(OnboardingUiState())
    private var addJob: Job? = null

    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onNameChanged(name: String) {
        if (_state.value.phase != OnboardingPhase.Form) return
        _state.update { it.copy(name = name.take(MAX_NAME_LENGTH), error = null) }
    }

    fun onSourceTypeChanged(type: SourceType) {
        if (_state.value.phase != OnboardingPhase.Form) return
        _state.update { withAvailability(it.copy(sourceType = type, error = null)) }
    }

    fun onDriveTextChanged(text: String) {
        if (_state.value.phase != OnboardingPhase.Form) return
        val input = DriveLinkParser.parse(text)
        _state.update {
            it.copy(driveText = text, driveInput = input, driveValidation = DriveIdRules.validate(input), error = null)
        }
    }

    fun onM3uTextChanged(text: String) {
        if (_state.value.phase != OnboardingPhase.Form) return
        _state.update { withAvailability(it.copy(m3uText = text, m3uValidation = M3uUrlRules.validate(text), error = null)) }
    }

    fun onXtreamToggled(enabled: Boolean) {
        if (!_state.value.xtreamToggleEnabled) return
        _state.update { it.copy(xtreamRequested = enabled) }
    }

    fun onCuratedEpgToggled(enabled: Boolean) {
        if (_state.value.phase != OnboardingPhase.Form) return
        _state.update { it.copy(useCuratedEpg = enabled) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Starts the add. No-op unless [OnboardingUiState.canSubmit]. */
    fun submit() {
        val form = _state.value
        if (!form.canSubmit) return
        val source = form.sourceInput() ?: return
        val name = form.effectiveName
        val xtream = form.xtreamEnabled
        val epgUrl = if (form.useCuratedEpg) EpgOverrideSettings.DEFAULT_URL else null
        _state.update {
            it.copy(
                phase = OnboardingPhase.Progress,
                stages = initialStages(xtream),
                error = null,
                playlistId = null,
                xtreamTurnedOff = false,
            )
        }
        addJob = scope.launch {
            // Subscribe before starting the add so no progress update is missed; stop once every stage is terminal.
            val progressJob = launch(start = CoroutineStart.UNDISPATCHED) {
                if (_state.value.allStagesTerminal) return@launch
                onboarder.progress.first { update ->
                    applyProgress(update)
                    _state.value.allStagesTerminal
                }
            }
            val result = try {
                onboarder.addPlaylist(name, source, xtream, epgUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                OnboardingResult.Failure(OnboardingError.Other)
            }
            when (result) {
                is OnboardingResult.Success -> onSuccess(result, requestedXtream = xtream)
                is OnboardingResult.Failure -> onFailure(result.error)
            }
            if (_state.value.allStagesTerminal || _state.value.phase == OnboardingPhase.Form) progressJob.cancel()
        }
    }

    /** Leaves onboarding for the app once channels are usable. Returns false when not yet possible. */
    fun continueToApp(): Boolean {
        if (!_state.value.canContinue) return false
        _state.update { it.copy(phase = OnboardingPhase.Finished) }
        return true
    }

    /**
     * Back press. During progress before channels are usable it cancels the add and returns to the
     * form; once channels are usable it continues into the app. Returns false on the form, where
     * the host decides (exit on first run, close "Add playlist" otherwise).
     */
    fun onBack(): Boolean {
        val current = _state.value
        return when {
            current.phase != OnboardingPhase.Progress -> false
            current.canContinue -> continueToApp()
            else -> {
                addJob?.cancel()
                addJob = null
                _state.update { it.copy(phase = OnboardingPhase.Form, stages = initialStages(it.xtreamEnabled)) }
                true
            }
        }
    }

    private fun withAvailability(s: OnboardingUiState): OnboardingUiState {
        val availability = when (s.sourceType) {
            SourceType.DriveId -> XtreamAvailability.UnknownUntilDownload
            SourceType.M3uUrl -> when {
                !s.m3uValidation.isValid -> XtreamAvailability.UnknownUntilDownload
                isXtreamShaped(s.m3uText.trim()) -> XtreamAvailability.Available
                else -> XtreamAvailability.NotXtreamShaped
            }
        }
        return s.copy(xtreamAvailability = availability)
    }

    private fun applyProgress(update: OnboardingProgress) {
        _state.update { s ->
            if (s.phase != OnboardingPhase.Progress) return@update s
            val stages = s.stages.toMutableMap()
            stages[update.stage] = update.status
            // A later stage running or done implies every earlier stage finished.
            if (update.status == StageStatus.Running || update.status == StageStatus.Done) {
                OnboardingStage.entries.filter { it.ordinal < update.stage.ordinal }.forEach { earlier ->
                    if (stages[earlier]?.isTerminal != true) stages[earlier] = StageStatus.Done
                }
            }
            s.copy(stages = stages)
        }
    }

    private fun onSuccess(result: OnboardingResult.Success, requestedXtream: Boolean) {
        _state.update { s ->
            if (s.phase != OnboardingPhase.Progress) return@update s
            val stages = s.stages.toMutableMap()
            stages[OnboardingStage.Playlist] = StageStatus.Done
            stages[OnboardingStage.Channels] = StageStatus.Done
            if (!result.xtreamEnabled) {
                stages[OnboardingStage.Catalog] = StageStatus.Skipped
                stages[OnboardingStage.Guide] = StageStatus.Skipped
            }
            s.copy(
                stages = stages,
                playlistId = result.playlistId,
                xtreamTurnedOff = requestedXtream && !result.xtreamEnabled,
            )
        }
    }

    private fun onFailure(error: OnboardingError) {
        _state.update { s ->
            when {
                s.phase != OnboardingPhase.Progress -> s
                // Channels are already usable (e.g. the account expired during the catalog stage): warn, stay.
                s.canContinue -> s.copy(error = error, stages = s.stages.mapValues { (_, v) -> if (v.isTerminal) v else StageStatus.Failed })
                // Keep the user on the form with the entered values so they can fix them.
                else -> s.copy(phase = OnboardingPhase.Form, error = error, stages = initialStages(s.xtreamEnabled))
            }
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

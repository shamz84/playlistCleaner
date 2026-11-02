package app.playlistplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.player.url.LiveFormatPreference
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.playlists.PlaylistRepository
import app.playlistplayer.feature.playlists.PlaylistsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Allowed output formats of a playlist's Xtream account (`m3u8`, `ts`, …); empty when unknown. */
fun interface AllowedFormatsSource {
    suspend fun allowedFormats(playlistId: Long): List<String>
}

/**
 * Everything [SettingsViewModel] needs. App-scoped objects ([parental], [pinPrompts],
 * [preferences]) must be the same instances the rest of the app uses.
 *
 * @property resetApp wipes every playlist, its rows, stream URLs, secrets and scheduled work
 *   ("Reset app"). The view model resets [parental] and [preferences] itself afterwards.
 */
class SettingsDependencies(
    val playlists: PlaylistRepository,
    val preferences: PreferencesRepository,
    val parental: ParentalControl,
    val pinPrompts: PinPromptBroker,
    val guide: GuideSettingsStore,
    val overrideTester: EpgOverrideTester,
    val groups: ContentGroupSource,
    val allowedFormats: AllowedFormatsSource,
    val resetApp: suspend () -> Unit,
    val suggester: AdultGroupSuggester = AdultGroupSuggester(),
)

/**
 * Settings for both form factors (U12): Playlists, Guide, Playback, Content and App sections.
 *
 * - Playlist actions go through [playlists] (U15's [PlaylistsViewModel]) with the parental gate;
 *   removal asks for confirmation first ([requestRemovePlaylist] → [confirmDialog]).
 * - Every protected action goes through [ParentalControl.authorize] via
 *   [ParentalPlaylistAuthorizer]; a needed PIN shows as [SettingsUiState.pinPrompt], answered
 *   with [submitPin] / [cancelPin].
 * - The override "Test" never saves; [saveOverride] tests first and saves only a reachable XMLTV.
 *
 * Pure Kotlin apart from the ViewModel base ([externalScope] is for tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val deps: SettingsDependencies,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = externalScope ?: viewModelScope
    private val parental = deps.parental
    private val preferences = deps.preferences
    private val authorizer = ParentalPlaylistAuthorizer(parental, deps.pinPrompts)

    /** Playlists section (list, health, account, counts; rename/edit/Xtream/refresh/remove). */
    val playlists: PlaylistsViewModel = PlaylistsViewModel(deps.playlists, authorize = { authorizer(it) }, externalScope = scope)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val playlistIds = MutableStateFlow<List<Long>>(emptyList())
    private val dismissedSuggestions = MutableStateFlow<Set<LockedGroup>>(emptySet())

    init {
        scope.launch {
            playlists.state.collect { list ->
                _state.update { it.copy(playlists = list) }
                playlistIds.value = list.playlists.map { it.id }
            }
        }
        scope.launch {
            preferences.preferences
                .catch { _state.update { it.copy(preferencesLoaded = true, message = SettingsMessage.Failed) } }
                .collect { prefs -> _state.update { it.copy(preferences = prefs, preferencesLoaded = true) } }
        }
        scope.launch {
            combine(parental.state, parental.lockedGroups) { s, groups -> s to groups }.collect { refreshParental() }
        }
        scope.launch {
            // A prompt opens or re-opens after each attempt: refresh the lockout time with it.
            deps.pinPrompts.request.collect { request ->
                _state.update { it.copy(pinPrompt = request) }
                refreshParental()
            }
        }
        // Guide status per playlist (match rate after an import).
        scope.launch {
            playlistIds.flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(ids.map { id -> deps.guide.observeStatus(id).map { id to it } }) { it.toList() }
                }
            }.catch { emit(emptyList()) }.collect { statuses ->
                _state.update { ui ->
                    val guide = statuses.associate { (id, status) ->
                        id to (ui.guide[id] ?: GuideUiState(id)).copy(status = status)
                    }
                    ui.copy(guide = guide)
                }
            }
        }
        // HLS is offered when an Xtream-enabled playlist's account allows m3u8 (re-checked when an
        // account status changes).
        scope.launch {
            playlists.state
                .map { s -> s.playlists.map { Triple(it.id, it.xtreamEnabled, it.account?.checkedAt) } }
                .distinctUntilChanged()
                .collect { entries ->
                    val hls = entries.any { (id, xtream, _) -> xtream && safeFormats(id).any { it.equals("m3u8", ignoreCase = true) } }
                    _state.update { it.copy(hlsAllowed = hls) }
                }
        }
        // Adult-group suggestions follow the playlists (and their group lists) and the locks.
        scope.launch {
            combine(
                playlists.state.map { s -> s.playlists.map { Triple(it.id, it.contentUpdatedAt, it.counts) } }.distinctUntilChanged(),
                parental.lockedGroups,
                dismissedSuggestions,
            ) { entries, locked, dismissed -> Triple(entries.map { it.first }, locked, dismissed) }
                .collect { (ids, locked, dismissed) ->
                    val suggestions = suggestionsFor(ids, locked, dismissed)
                    _state.update { it.copy(suggestions = suggestions) }
                }
        }
    }

    // ---- Playlists ----

    fun renamePlaylist(playlistId: Long, name: String) = playlists.rename(playlistId, name)

    fun editPlaylistSource(playlistId: Long, source: SourceInput) = playlists.editSource(playlistId, source)

    fun setXtreamEnabled(playlistId: Long, enabled: Boolean) = playlists.setXtreamEnabled(playlistId, enabled)

    fun refreshPlaylist(playlistId: Long) = playlists.refresh(playlistId)

    fun refreshAllPlaylists() = playlists.refreshAll()

    /** Opens the removal confirmation; [confirmDialog] removes (PIN-gated). */
    fun requestRemovePlaylist(playlistId: Long) {
        val name = _state.value.playlists.playlists.firstOrNull { it.id == playlistId }?.name ?: return
        _state.update { it.copy(dialog = SettingsDialog.ConfirmRemovePlaylist(playlistId, name)) }
    }

    fun dismissPlaylistMessage() = playlists.dismissMessage()

    // ---- Dialogs ----

    fun confirmDialog() {
        val dialog = _state.value.dialog ?: return
        _state.update { it.copy(dialog = null) }
        when (dialog) {
            is SettingsDialog.ConfirmRemovePlaylist -> playlists.remove(dialog.playlistId)
            SettingsDialog.ConfirmResetApp -> resetApp()
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialog = null) }
    }

    // ---- PIN prompt ----

    fun submitPin(pin: String) {
        val request = _state.value.pinPrompt ?: return
        deps.pinPrompts.submit(request.id, pin)
    }

    fun cancelPin() {
        val request = _state.value.pinPrompt ?: return
        deps.pinPrompts.cancel(request.id)
    }

    // ---- Guide ----

    /** Loads the stored override URL for the field. */
    fun openGuide(playlistId: Long) {
        scope.launch {
            val url = runCatchingNonCancel { deps.guide.overrideUrl(playlistId) }
            updateGuide(playlistId) { it.copy(overrideUrl = url, overrideLoaded = true) }
        }
    }

    /** Checks [url] (reachability, XMLTV, match-rate preview) without saving it. */
    fun testOverride(playlistId: Long, url: String) {
        updateGuide(playlistId) { it.copy(test = OverrideTestState.Running) }
        scope.launch {
            val result = runTest(playlistId, url)
            updateGuide(playlistId) { it.copy(test = OverrideTestState.Done(result)) }
        }
    }

    /** Tests [url] and saves it only when it serves XMLTV; the import then reports the real match rate. */
    fun saveOverride(playlistId: Long, url: String) {
        if (url.isBlank()) return clearOverride(playlistId)
        updateGuide(playlistId) { it.copy(saving = true, test = OverrideTestState.Running) }
        scope.launch {
            val result = runTest(playlistId, url)
            val message = if (result is OverrideTestResult.Reachable) {
                val saved = runCatchingNonCancel { deps.guide.setOverride(playlistId, url.trim()) } == true
                if (saved) SettingsMessage.OverrideSaved(playlistId) else SettingsMessage.OverrideNotSaved(playlistId, OverrideTestResult.InvalidUrl)
            } else {
                SettingsMessage.OverrideNotSaved(playlistId, result)
            }
            updateGuide(playlistId) {
                it.copy(
                    saving = false,
                    test = OverrideTestState.Done(result),
                    overrideUrl = if (message is SettingsMessage.OverrideSaved) url.trim() else it.overrideUrl,
                )
            }
            _state.update { it.copy(message = message) }
        }
    }

    /** Back to the provider guide. */
    fun clearOverride(playlistId: Long) {
        scope.launch {
            val ok = runCatchingNonCancel { deps.guide.setOverride(playlistId, null) } == true
            updateGuide(playlistId) { it.copy(overrideUrl = null, test = OverrideTestState.Idle) }
            _state.update { it.copy(message = if (ok) SettingsMessage.OverrideCleared(playlistId) else SettingsMessage.Failed) }
        }
    }

    // ---- Playback ----

    fun setLiveFormat(format: LiveFormatPreference) {
        if (format == LiveFormatPreference.HLS && !_state.value.hlsAllowed) {
            _state.update { it.copy(message = SettingsMessage.HlsNotAllowed) }
            return
        }
        launchPrefs { preferences.setLiveFormat(format) }
    }

    fun setMatchFrameRate(enabled: Boolean) = launchPrefs { preferences.setMatchFrameRate(enabled) }

    fun setTunneling(enabled: Boolean) = launchPrefs { preferences.setTunneling(enabled) }

    /** Preferred audio language of movies and episodes; null = device language. */
    fun setAudioLanguage(code: String?) = launchPrefs {
        if (!preferences.setAudioLanguage(code)) post(SettingsMessage.InvalidLanguage)
    }

    /** Preferred subtitle language of movies and episodes; null = subtitles off. */
    fun setSubtitleLanguage(code: String?) = launchPrefs {
        if (!preferences.setSubtitleLanguage(code)) post(SettingsMessage.InvalidLanguage)
    }

    fun setCatchupOffsetHours(hours: Int) = launchPrefs {
        val applied = preferences.setCatchupOffsetHours(hours)
        if (applied != hours) _state.update { it.copy(message = SettingsMessage.ValueClamped(applied)) }
    }

    // ---- Content: languages and quality ----

    fun addLanguage(code: String) = launchPrefs {
        if (!preferences.addLanguage(code)) _state.update { it.copy(message = SettingsMessage.InvalidLanguage) }
    }

    fun removeLanguage(code: String) = launchPrefs { preferences.removeLanguage(code) }

    fun moveLanguage(from: Int, to: Int) = launchPrefs { preferences.moveLanguage(from, to) }

    /** D-pad friendly reorder: one step towards the top. */
    fun moveLanguageUp(index: Int) = moveLanguage(index, index - 1)

    fun moveLanguageDown(index: Int) = moveLanguage(index, index + 1)

    fun setQuality(quality: QualityPreference) = launchPrefs { preferences.setQuality(quality) }

    // ---- Content: group visibility ----

    fun openGroupVisibility(playlistId: Long) {
        _state.update { it.copy(groupVisibility = GroupVisibilityState(playlistId)) }
        scope.launch {
            val groups = runCatchingNonCancel { deps.groups.groups(playlistId) }.orEmpty()
            val rows = groups.map { GroupVisibilityRow(it, parental.isGroupLocked(it.playlistId, it.key)) }
            _state.update { ui ->
                if (ui.groupVisibility?.playlistId != playlistId) ui else ui.copy(groupVisibility = GroupVisibilityState(playlistId, loading = false, rows = rows))
            }
        }
    }

    fun closeGroupVisibility() {
        _state.update { it.copy(groupVisibility = null) }
    }

    /** Locks or unlocks one group (needs a PIN to exist; asks for it when the session is locked). */
    fun setGroupLocked(group: ContentGroup, locked: Boolean) = changeLockedGroups { current ->
        if (locked) current + group.lockedGroup else current - group.lockedGroup
    }

    /** Locks the confirmed suggestions (default: all shown). */
    fun confirmSuggestions(selection: Set<LockedGroup> = _state.value.suggestions.mapTo(mutableSetOf()) { it.group }) {
        if (selection.isEmpty()) return
        changeLockedGroups { current -> current + selection }
    }

    /** Hides the current suggestions for this session without locking anything. */
    fun dismissSuggestions() {
        dismissedSuggestions.update { it + _state.value.suggestions.map { s -> s.group } }
    }

    private fun changeLockedGroups(change: (Set<LockedGroup>) -> Set<LockedGroup>) {
        scope.launch {
            val message = when {
                parental.isDamaged -> SettingsMessage.ResetRequired
                !parental.hasPin -> SettingsMessage.NoPinSet
                !authorizer(ProtectedAction.ChangeGroupVisibility) -> SettingsMessage.NotAuthorized
                else -> when (val result = parental.setLockedGroups(change(parental.lockedGroups.value))) {
                    ParentalResult.Done -> SettingsMessage.GroupsSaved
                    else -> result.toMessage(SettingsMessage.GroupsSaved)
                }
            }
            _state.update { it.copy(message = message) }
        }
    }

    // ---- Content: parental PIN ----

    fun setPin(newPin: String, confirmPin: String) {
        if (newPin != confirmPin) return post(SettingsMessage.PinMismatch)
        scope.launch {
            post(parental.setPin(newPin).toMessage(SettingsMessage.PinSet))
            refreshParental()
        }
    }

    fun changePin(currentPin: String, newPin: String, confirmPin: String) {
        if (newPin != confirmPin) return post(SettingsMessage.PinMismatch)
        scope.launch {
            post(parental.changePin(currentPin, newPin).toMessage(SettingsMessage.PinChanged))
            refreshParental()
        }
    }

    fun removePin(currentPin: String) {
        scope.launch {
            post(parental.removePin(currentPin).toMessage(SettingsMessage.PinRemoved))
            refreshParental()
        }
    }

    /** Ends the unlock session now (locked groups hide again). */
    fun lockNow() {
        parental.lock()
        refreshParental()
    }

    // ---- App ----

    fun setRefreshInterval(hours: Int) = launchPrefs {
        val applied = preferences.setRefreshIntervalHours(hours)
        deps.playlists.setRefreshInterval(applied)
        if (applied != hours) _state.update { it.copy(message = SettingsMessage.ValueClamped(applied)) }
    }

    fun setStaleWarningDays(days: Int) = launchPrefs {
        val applied = preferences.setStaleWarningDays(days)
        if (applied != days) _state.update { it.copy(message = SettingsMessage.ValueClamped(applied)) }
    }

    /** Opens the "Reset app" confirmation (no PIN: this is the forgotten-PIN path). */
    fun requestResetApp() {
        _state.update { it.copy(dialog = SettingsDialog.ConfirmResetApp) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---- internals ----

    private fun refreshParental() {
        val s = parental.currentState()
        _state.update { ui ->
            ui.copy(
                parental = ParentalUiState(s, parental.hasPin, parental.lockedOutUntil(), parental.lockedGroups.value.size),
                groupVisibility = ui.groupVisibility?.let { v ->
                    v.copy(rows = v.rows.map { row -> row.copy(locked = parental.isGroupLocked(row.group.playlistId, row.group.key)) })
                },
            )
        }
    }

    private fun resetApp() {
        if (_state.value.resetting) return
        _state.update { it.copy(resetting = true) }
        scope.launch {
            val message = try {
                deps.resetApp()
                // Only after the data is gone: unlocking parental control earlier would expose it.
                parental.resetAll()
                preferences.reset()
                dismissedSuggestions.value = emptySet()
                SettingsMessage.ResetDone
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SettingsMessage.Failed
            }
            _state.update { it.copy(resetting = false, message = message, groupVisibility = null, guide = emptyMap()) }
        }
    }

    private suspend fun runTest(playlistId: Long, url: String): OverrideTestResult =
        runCatchingNonCancel { deps.overrideTester.test(playlistId, url) } ?: OverrideTestResult.Unreachable

    private suspend fun safeFormats(playlistId: Long): List<String> =
        runCatchingNonCancel { deps.allowedFormats.allowedFormats(playlistId) }.orEmpty()

    private suspend fun suggestionsFor(ids: List<Long>, locked: Set<LockedGroup>, dismissed: Set<LockedGroup>): List<GroupSuggestion> {
        if (ids.isEmpty()) return emptyList()
        val candidates = ids.flatMap { id -> runCatchingNonCancel { deps.groups.groups(id) }.orEmpty() }.map { it.candidate() }
        return deps.suggester.suggest(candidates, locked).filter { it.group !in dismissed }
    }

    private fun updateGuide(playlistId: Long, transform: (GuideUiState) -> GuideUiState) {
        _state.update { ui -> ui.copy(guide = ui.guide + (playlistId to transform(ui.guide[playlistId] ?: GuideUiState(playlistId)))) }
    }

    private fun launchPrefs(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                post(SettingsMessage.Failed)
            }
        }
    }

    private fun post(message: SettingsMessage) {
        _state.update { it.copy(message = message) }
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

private fun ParentalResult.toMessage(success: SettingsMessage): SettingsMessage = when (this) {
    ParentalResult.Done -> success
    ParentalResult.InvalidPinFormat -> SettingsMessage.PinInvalidFormat
    ParentalResult.NoPinSet -> SettingsMessage.NoPinSet
    is ParentalResult.Rejected ->
        if (authorization == Authorization.ResetRequired) SettingsMessage.ResetRequired else SettingsMessage.PinRejected(authorization)
}

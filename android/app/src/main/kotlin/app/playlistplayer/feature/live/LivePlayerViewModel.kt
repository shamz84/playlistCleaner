package app.playlistplayer.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.core.player.catchup.CatchupDecision
import app.playlistplayer.core.player.catchup.ProgrammeWindow
import app.playlistplayer.core.sync.epg.GuideProgramme
import app.playlistplayer.core.sync.epg.NowNext
import app.playlistplayer.ui.common.live.ChannelNavigator
import app.playlistplayer.ui.tv.live.NumberEntryController
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Now/next for the overlay; [progress] is the airing programme's elapsed fraction. */
data class NowNextUi(
    val now: GuideProgramme?,
    val next: GuideProgramme?,
    val progress: Float?,
)

/** A version row in the overlay's "Other versions" list. */
data class VersionItem(val version: OtherVersion, val isCurrent: Boolean)

enum class LiveStartError {
    /** The channel is no longer in the playlist. */
    NOT_FOUND,

    /** A header, or no stream id and no URL. */
    NOT_PLAYABLE,

    /** An Xtream entry whose playlist has no stored account. */
    NO_ACCOUNT,
}

data class LivePlayerUiState(
    val loading: Boolean = true,
    /** The channel the user tuned to (number and name in the overlay). */
    val channel: LiveChannel? = null,
    /** 1-based position among the group's playable entries. */
    val number: Int? = null,
    val playableCount: Int = 0,
    val nowNext: NowNextUi? = null,
    val qualityBadge: String? = null,
    /** Label of the version playing (differs from the channel name after a switch or failover). */
    val currentVersionLabel: String? = null,
    val currentVersionId: String? = null,
    /** The channel and its other versions in comparator order (at most six rows). */
    val versions: List<VersionItem> = emptyList(),
    val versionsOpen: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus(),
    val overlayVisible: Boolean = true,
    /** Digits typed so far (number entry), or empty. */
    val numberDigits: String = "",
    /** Typed digits that name no channel in this group (shown briefly). */
    val numberNotFound: String? = null,
    /** The airing programme can be restarted from the archive (R8). */
    val canRestart: Boolean = false,
    val startError: LiveStartError? = null,
) {
    val hasOtherVersions: Boolean get() = versions.size > 1
}

sealed interface LivePlayerEvent {
    data class StartCatchup(val request: CatchupRequest) : LivePlayerEvent
}

/**
 * Live player (U9): plays the tuned channel through [driver], with now/next, quality badge,
 * "Other versions" (switch manually; failover uses the same list), playback status from the U6
 * state machine, channel up/down and number entry within the current group, and "Restart
 * programme" when the airing programme is catch-up eligible.
 *
 * The UI calls [tick] about every 30 s to refresh now/next and the restart offer.
 */
class LivePlayerViewModel(
    private val playlistId: Long,
    private val initialOrdinal: Int,
    private val channels: LiveChannelSource,
    private val coordinator: LivePlaybackCoordinator,
    private val nowNextSource: NowNextSource,
    private val driver: PlayerDriver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val overlayTimeoutMs: Long = DEFAULT_OVERLAY_TIMEOUT_MS,
) : ViewModel() {

    private val mutableState = MutableStateFlow(LivePlayerUiState())
    val state: StateFlow<LivePlayerUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<LivePlayerEvent>(Channel.BUFFERED)
    val events: Flow<LivePlayerEvent> = eventChannel.receiveAsFlow()

    private val numberEntry = NumberEntryController()
    private var group: List<LiveChannel> = emptyList()
    private var navigator = ChannelNavigator(group) { it.isPlayable }
    private var plan: LivePlan? = null
    private var tuneJob: Job? = null
    private var numberJob: Job? = null
    private var overlayJob: Job? = null
    private var notFoundJob: Job? = null

    init {
        viewModelScope.launch { driver.state.collect(::onPlaybackState) }
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val channel = channels.channel(playlistId, initialOrdinal)
        if (channel == null) {
            mutableState.update { it.copy(loading = false, startError = LiveStartError.NOT_FOUND) }
            return
        }
        loadGroup(channel)
        tune(channel)
    }

    private suspend fun loadGroup(channel: LiveChannel) {
        group = channels.groupChannels(channel.playlistId, channel.groupOrdinal).filter { it.playlistId == channel.playlistId }
        navigator = ChannelNavigator(group) { it.isPlayable }
    }

    // ---- tuning ----

    private fun tune(channel: LiveChannel) {
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = true,
                    channel = channel,
                    number = numberOf(channel),
                    playableCount = group.count { c -> c.isPlayable },
                    nowNext = null,
                    canRestart = false,
                    versionsOpen = false,
                    startError = null,
                )
            }
            showOverlay()
            when (val result = coordinator.tune(channel)) {
                is LivePlanResult.Ready -> play(result.plan)
                LivePlanResult.NoAccount -> fail(LiveStartError.NO_ACCOUNT)
                LivePlanResult.NotPlayable -> fail(LiveStartError.NOT_PLAYABLE)
            }
            refreshNowNext()
        }
    }

    private fun play(newPlan: LivePlan) {
        plan = newPlan
        val primary = newPlan.sources.first()
        mutableState.update {
            it.copy(
                loading = false,
                currentVersionId = primary.id,
                currentVersionLabel = primary.label,
                qualityBadge = primary.versionTier.badge(),
                versions = items(newPlan, primary.id),
            )
        }
        driver.play(newPlan.sources)
    }

    private fun fail(error: LiveStartError) {
        plan = null
        driver.stop()
        mutableState.update { it.copy(loading = false, startError = error, versions = emptyList(), qualityBadge = null) }
    }

    private fun items(plan: LivePlan, currentId: String?) = plan.versions.map { VersionItem(it, it.id == currentId) }

    private fun numberOf(channel: LiveChannel): Int? {
        var n = 0
        for (c in group) {
            if (c.isPlayable) n++
            if (c.ordinal == channel.ordinal) return if (c.isPlayable) n else null
        }
        return null
    }

    private fun currentIndex(): Int {
        val ordinal = mutableState.value.channel?.ordinal ?: return -1
        return group.indexOfFirst { it.ordinal == ordinal }
    }

    /** Next playable entry in the group (higher number); stays put at the end. */
    fun channelUp() {
        navigator.channelUp(currentIndex())?.let { tune(group[it]) }
    }

    /** Previous playable entry in the group; stays put at the start. */
    fun channelDown() {
        navigator.channelDown(currentIndex())?.let { tune(group[it]) }
    }

    // ---- number entry ----

    fun onDigit(digit: Int) {
        val count = group.count { it.isPlayable }
        val outcome = numberEntry.onDigit(digit, clock(), count)
        numberJob?.cancel()
        if (outcome != null) {
            handle(outcome)
        } else {
            mutableState.update { it.copy(numberDigits = numberEntry.state.digits) }
            numberJob = viewModelScope.launch {
                delay(numberEntry.timeoutMillis)
                handle(numberEntry.commit(count))
            }
        }
    }

    /** OK while digits are pending commits them; returns false when nothing was pending. */
    fun commitNumber(): Boolean {
        if (!numberEntry.state.isActive) return false
        numberJob?.cancel()
        handle(numberEntry.commit(group.count { it.isPlayable }))
        return true
    }

    fun cancelNumber() {
        numberJob?.cancel()
        numberEntry.cancel()
        mutableState.update { it.copy(numberDigits = "") }
    }

    private fun handle(outcome: NumberEntryController.Outcome?) {
        mutableState.update { it.copy(numberDigits = "") }
        when (outcome) {
            is NumberEntryController.Outcome.Jump -> {
                val target = group.filter { it.isPlayable }.getOrNull(outcome.number - 1)
                if (target != null && target.ordinal != mutableState.value.channel?.ordinal) tune(target)
            }
            is NumberEntryController.Outcome.Ignored -> {
                mutableState.update { it.copy(numberNotFound = outcome.digits) }
                notFoundJob?.cancel()
                notFoundJob = viewModelScope.launch {
                    delay(NOT_FOUND_VISIBLE_MS)
                    mutableState.update { it.copy(numberNotFound = null) }
                }
            }
            null -> Unit
        }
    }

    // ---- other versions ----

    fun openVersions() {
        if (!mutableState.value.hasOtherVersions) return
        overlayJob?.cancel()
        mutableState.update { it.copy(versionsOpen = true, overlayVisible = true) }
    }

    fun closeVersions() {
        mutableState.update { it.copy(versionsOpen = false) }
        showOverlay()
    }

    /** Plays [versionId] (one of [LivePlayerUiState.versions]) with the others as failover. */
    fun switchTo(versionId: String) {
        val current = plan ?: return
        val version = current.versions.firstOrNull { it.id == versionId } ?: return
        mutableState.update { it.copy(versionsOpen = false) }
        if (versionId == mutableState.value.currentVersionId) return
        tuneJob?.cancel()
        tuneJob = viewModelScope.launch {
            when (val result = coordinator.switchTo(current, version)) {
                is LivePlanResult.Ready -> play(result.plan)
                LivePlanResult.NoAccount -> fail(LiveStartError.NO_ACCOUNT)
                LivePlanResult.NotPlayable -> fail(LiveStartError.NOT_PLAYABLE)
            }
        }
    }

    // ---- playback status ----

    private fun onPlaybackState(state: PlaybackState) {
        val status = PlaybackStatus.from(state)
        val source = state.source
        val current = plan
        mutableState.update { s ->
            if (source != null && current != null && current.sources.any { it.id == source.id } && source.id != s.currentVersionId) {
                // Failover moved to another version.
                s.copy(
                    status = status,
                    currentVersionId = source.id,
                    currentVersionLabel = source.label,
                    qualityBadge = source.versionTier.badge(),
                    versions = items(current, source.id),
                )
            } else {
                s.copy(status = status)
            }
        }
    }

    fun retry() {
        val s = mutableState.value
        val channel = s.channel
        if (s.startError != null && channel != null) tune(channel) else driver.retry()
    }

    fun stop() = driver.stop()

    // ---- overlay ----

    fun showOverlay() {
        mutableState.update { it.copy(overlayVisible = true) }
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(overlayTimeoutMs)
            if (!mutableState.value.versionsOpen) mutableState.update { it.copy(overlayVisible = false) }
        }
    }

    fun hideOverlay() {
        overlayJob?.cancel()
        mutableState.update { it.copy(overlayVisible = false, versionsOpen = false) }
    }

    fun toggleOverlay() {
        if (mutableState.value.overlayVisible) hideOverlay() else showOverlay()
    }

    // ---- now/next and restart ----

    /** Refreshes now/next when the airing programme ended, and the progress and restart offer. */
    fun tick() {
        viewModelScope.launch {
            val now = mutableState.value.nowNext?.now
            if (now == null || clock() >= now.stopMillis) refreshNowNext() else updateGuideDerived()
        }
    }

    private suspend fun refreshNowNext() {
        val channel = mutableState.value.channel ?: return
        if (!channel.isPlayable) return
        val nowNext = try {
            nowNextSource.nowNext(channel.playlistId, channel.ordinal)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            NowNext.NONE
        }
        if (mutableState.value.channel?.ordinal != channel.ordinal) return
        mutableState.update { it.copy(nowNext = NowNextUi(nowNext.now, nowNext.next, null)) }
        updateGuideDerived()
    }

    private fun updateGuideDerived() {
        val s = mutableState.value
        val nowProgramme = s.nowNext?.now
        val channel = s.channel
        val nowMillis = clock()
        val progress = nowProgramme?.let { p ->
            val length = p.stopMillis - p.startMillis
            if (length > 0) ((nowMillis - p.startMillis).toFloat() / length).coerceIn(0f, 1f) else null
        }
        val canRestart = nowProgramme != null && channel != null && nowProgramme.stopMillis > nowProgramme.startMillis &&
            CatchupPlaybackController.decide(channel, otherChannels(), window(nowProgramme), nowMillis) is CatchupDecision.RestartLive
        mutableState.update { it.copy(nowNext = it.nowNext?.copy(progress = progress), canRestart = canRestart) }
    }

    /** "Restart programme": asks the host to open catch-up for the airing programme. */
    fun restart() {
        val s = mutableState.value
        val channel = s.channel ?: return
        val programme = s.nowNext?.now ?: return
        if (!s.canRestart) return
        eventChannel.trySend(
            LivePlayerEvent.StartCatchup(
                CatchupRequest(
                    channel = channel,
                    otherVersions = otherChannels(),
                    programmeStartMillis = programme.startMillis,
                    programmeStopMillis = programme.stopMillis,
                    title = programme.title,
                    programmeId = programme.programmeId,
                ),
            ),
        )
    }

    private fun otherChannels(): List<LiveChannel> {
        val p = plan ?: return emptyList()
        return p.versions.map { it.channel }.filter { it != p.group }
    }

    private fun window(p: GuideProgramme) = ProgrammeWindow(Instant.ofEpochMilli(p.startMillis), Instant.ofEpochMilli(p.stopMillis))

    companion object {
        const val DEFAULT_OVERLAY_TIMEOUT_MS = 5_000L
        const val NOT_FOUND_VISIBLE_MS = 2_000L
    }
}

package app.playlistplayer.feature.vod

/** D-pad seek direction. */
enum class SeekDirection { BACK, FORWARD }

/**
 * D-pad seek steps: left = −10 s, right = +30 s per press; holding the key (key repeats)
 * accelerates the step ×2 after [TIER_1] repeats, ×4 after [TIER_2], ×8 after [TIER_3].
 */
object SeekAccelerator {
    const val BACK_STEP_MS: Long = 10_000
    const val FORWARD_STEP_MS: Long = 30_000
    const val TIER_1: Int = 3
    const val TIER_2: Int = 8
    const val TIER_3: Int = 15

    fun multiplier(repeatCount: Int): Int = when {
        repeatCount >= TIER_3 -> 8
        repeatCount >= TIER_2 -> 4
        repeatCount >= TIER_1 -> 2
        else -> 1
    }

    /** Signed step for one key event with Android's `KeyEvent.repeatCount`. */
    fun stepMs(direction: SeekDirection, repeatCount: Int): Long {
        val base = if (direction == SeekDirection.FORWARD) FORWARD_STEP_MS else -BACK_STEP_MS
        return base * multiplier(repeatCount.coerceAtLeast(0))
    }
}

enum class TrackType { AUDIO, TEXT }

/** One selectable audio or subtitle track. [id] is opaque to this model (the UI maps it to Media3). */
data class TrackOption(
    val id: String,
    val type: TrackType,
    val label: String,
    /** Normalised language tag (`TrackLanguage.normalize`), null when unknown. */
    val language: String? = null,
    val selected: Boolean = false,
    /** Forced subtitles (foreign-language parts only). */
    val forced: Boolean = false,
)

/**
 * Audio and subtitle selection. Input: the player's track list (with the player's current
 * selection flags). Output: [selectedAudioId] and [selectedSubtitleId] (null = subtitles off).
 *
 * With a [preference] (on-demand playback, R40) the tracks matching it are chosen
 * ([TrackPreferenceResolver]) until the user picks a track ([manual]); each pick also updates
 * [preference] to the picked track's language. Without one (live), the player's selection is kept.
 */
data class TrackSelectionModel(
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val selectedAudioId: String? = null,
    val selectedSubtitleId: String? = null,
    val preference: TrackPreference? = null,
    /** The user picked a track for the current item; the preference no longer overrides it. */
    val manual: Boolean = false,
) {
    val hasChoices: Boolean get() = audio.size > 1 || subtitles.isNotEmpty()

    /**
     * New track list from the player. Before a manual pick with a [preference], the preferred
     * tracks are chosen. Otherwise a user choice that still exists is kept, else the player's
     * selected track (or the first audio track) is used.
     */
    fun withTracks(tracks: List<TrackOption>): TrackSelectionModel {
        val a = tracks.filter { it.type == TrackType.AUDIO }
        val t = tracks.filter { it.type == TrackType.TEXT }
        val pref = preference
        val audioId: String?
        val textId: String?
        if (pref != null && !manual) {
            audioId = TrackPreferenceResolver.audio(a, pref)
            textId = TrackPreferenceResolver.subtitle(t, pref)
        } else {
            audioId = selectedAudioId?.takeIf { id -> a.any { it.id == id } }
                ?: a.firstOrNull { it.selected }?.id
                ?: a.firstOrNull()?.id
            textId = selectedSubtitleId?.takeIf { id -> t.any { it.id == id } }
                ?: t.firstOrNull { it.selected }?.id
        }
        return copy(audio = a.markSelected(audioId), subtitles = t.markSelected(textId), selectedAudioId = audioId, selectedSubtitleId = textId)
    }

    fun selectAudio(id: String): TrackSelectionModel {
        val track = audio.firstOrNull { it.id == id } ?: return this
        return copy(audio = audio.markSelected(id), selectedAudioId = id, manual = true, preference = preference?.withAudioChoice(track))
    }

    /** [id] null turns subtitles off. */
    fun selectSubtitle(id: String?): TrackSelectionModel {
        val track = id?.let { i -> subtitles.firstOrNull { it.id == i } ?: return this }
        return copy(subtitles = subtitles.markSelected(id), selectedSubtitleId = id, manual = true, preference = preference?.withSubtitleChoice(track))
    }

    private fun List<TrackOption>.markSelected(id: String?) = map { it.copy(selected = it.id == id) }
}

data class ResumePrompt(val positionMs: Long)

data class SeekPreview(val targetMs: Long, val stepMs: Long)

data class NextEpisodeCard(val label: String, val secondsRemaining: Int)

enum class SkipKind { INTRO, RECAP, CREDITS }

/** A "Skip intro" / "Skip credits" offer; [targetMs] is where it jumps to (the end of the item: play next or finish). */
data class SkipPrompt(val kind: SkipKind, val targetMs: Long)

/** A real skippable span from a marker service; [endMs] null runs to the end of the item. */
data class SkipSpan(val kind: SkipKind, val startMs: Long, val endMs: Long?)

/**
 * Skip offers for on-demand items. IPTV providers carry no intro or credits markers, so the
 * offers are position-based: "Skip intro" (episodes only) for a short window early in the
 * episode, jumping [INTRO_SKIP_MS] ahead; "Skip credits" before the end of an item long enough
 * to have credits, jumping to its end (which plays the next episode when there is one).
 */
object SkipSegments {
    const val INTRO_WINDOW_START_MS: Long = 10_000
    const val INTRO_WINDOW_END_MS: Long = 45_000
    const val INTRO_SKIP_MS: Long = 85_000
    const val CREDITS_LEAD_MS: Long = 240_000
    const val MIN_DURATION_FOR_CREDITS_MS: Long = 10L * 60 * 1000
    const val MIN_DURATION_FOR_INTRO_MS: Long = 15L * 60 * 1000

    fun promptAt(positionMs: Long, durationMs: Long, isEpisode: Boolean): SkipPrompt? {
        if (durationMs <= 0) return null
        if (isEpisode && durationMs >= MIN_DURATION_FOR_INTRO_MS &&
            positionMs in INTRO_WINDOW_START_MS until INTRO_WINDOW_END_MS
        ) {
            return SkipPrompt(SkipKind.INTRO, (positionMs + INTRO_SKIP_MS).coerceAtMost(durationMs))
        }
        // The next-episode card takes over once inCredits; the overlay hides this offer while it shows.
        if (durationMs >= MIN_DURATION_FOR_CREDITS_MS && durationMs - positionMs <= CREDITS_LEAD_MS) {
            return SkipPrompt(SkipKind.CREDITS, durationMs)
        }
        return null
    }

    /**
     * The offer from real [spans]. Intro and recap are only offered from their markers; the
     * position-based credits guess still applies when the markers carry no credits span.
     */
    fun promptFromSpans(spans: List<SkipSpan>, positionMs: Long, durationMs: Long, isEpisode: Boolean): SkipPrompt? {
        if (durationMs <= 0) return null
        spans.firstOrNull { span ->
            val end = span.endMs ?: durationMs
            positionMs >= span.startMs && positionMs < end - MIN_REMAINING_MS
        }?.let { return SkipPrompt(it.kind, it.endMs ?: durationMs) }
        if (spans.none { it.kind == SkipKind.CREDITS }) {
            return promptAt(positionMs, durationMs, isEpisode)?.takeIf { it.kind == SkipKind.CREDITS }
        }
        return null
    }

    /** A span ending this close to the end of its window is not worth offering. */
    const val MIN_REMAINING_MS: Long = 1_500
}

data class VodOverlayState(
    /** Waiting for Resume / Start over; playback has not started. */
    val resumePrompt: ResumePrompt? = null,
    val started: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val controlsVisible: Boolean = false,
    /** Pending D-pad seek target, committed on key up. */
    val seek: SeekPreview? = null,
    val nextEpisode: NextEpisodeCard? = null,
    /** The user dismissed the next-episode card for this episode. */
    val nextCancelled: Boolean = false,
    val advancing: Boolean = false,
    val tracks: TrackSelectionModel = TrackSelectionModel(),
    val trackPanelOpen: Boolean = false,
    /** Skip offer for the current position, unless dismissed or already used. */
    val skip: SkipPrompt? = null,
)

sealed interface OverlayEvent {
    /**
     * New episode/movie opened. [resumeOfferMs] shows the prompt; [nextLabel] enables auto-advance.
     * [trackPreference] (R40) selects the preferred audio/subtitles when the tracks arrive; null
     * keeps the previous item's track model (no automatic selection).
     */
    data class Open(
        val resumeOfferMs: Long?,
        val startPositionMs: Long = 0,
        val nextLabel: String? = null,
        val trackPreference: TrackPreference? = null,
        val isEpisode: Boolean = false,
    ) : OverlayEvent
    data object ChooseResume : OverlayEvent
    data object ChooseStartOver : OverlayEvent
    data object PlayPause : OverlayEvent
    data class SeekKey(val direction: SeekDirection, val repeatCount: Int) : OverlayEvent
    data object SeekKeyUp : OverlayEvent

    /** Seek to an absolute position (scrub bar or drag release), committed at once. */
    data class SeekTo(val positionMs: Long) : OverlayEvent
    data class Progress(val positionMs: Long, val durationMs: Long, val isPlaying: Boolean, val nowMs: Long) : OverlayEvent
    data class Tick(val nowMs: Long) : OverlayEvent
    data object CancelNext : OverlayEvent
    data object PlayNextNow : OverlayEvent
    data object Ended : OverlayEvent
    data class TracksChanged(val tracks: List<TrackOption>) : OverlayEvent
    data object ToggleTrackPanel : OverlayEvent
    data class SelectAudio(val id: String) : OverlayEvent
    data class SelectSubtitle(val id: String?) : OverlayEvent
    data object ShowControls : OverlayEvent
    data object HideControls : OverlayEvent

    /** Take the current skip offer. */
    data object SkipNow : OverlayEvent

    /** Real skip spans for the open item (arrive after [Open]); they replace the position-based guesses. */
    data class Markers(val spans: List<SkipSpan>) : OverlayEvent
}

sealed interface OverlayCommand {
    data class Start(val positionMs: Long) : OverlayCommand
    data object Pause : OverlayCommand
    data object Resume : OverlayCommand
    data class SeekTo(val positionMs: Long) : OverlayCommand
    data object PlayNext : OverlayCommand
    data class ApplyTracks(val audioId: String?, val subtitleId: String?) : OverlayCommand

    /** A manual track choice changed the language preference: persist it and prefer it from now on. */
    data class SaveTrackPreference(val preference: TrackPreference) : OverlayCommand
}

/**
 * Pure state machine for the VOD playback overlay (R16, R19):
 * - Resume / Start over prompt at start when a resume position is offered;
 * - play/pause; D-pad seek with acceleration ([SeekAccelerator]), previewed and committed once
 *   on key up; absolute seeks ([OverlayEvent.SeekTo]) from a scrub bar;
 * - next-episode card during the credits (last [CREDITS_REMAINING_MS] or ≥ [CREDITS_FRACTION]),
 *   counting down [COUNTDOWN_MS] then playing the next episode, unless cancelled;
 * - audio and subtitle track selection.
 *
 * [handle] returns the commands the UI applies to the player. Time comes in through events.
 */
class VodPlayerOverlayState(initial: VodOverlayState = VodOverlayState()) {

    var state: VodOverlayState = initial
        private set

    private var nextLabel: String? = null
    private var isEpisode = false
    private var spans: List<SkipSpan> = emptyList()
    private val usedSkips = mutableSetOf<SkipKind>()
    private var countdownStartedAt: Long? = null

    fun handle(event: OverlayEvent): List<OverlayCommand> {
        val s = state
        return when (event) {
            is OverlayEvent.Open -> {
                nextLabel = event.nextLabel
                isEpisode = event.isEpisode
                spans = emptyList()
                usedSkips.clear()
                countdownStartedAt = null
                // A preference starts a fresh track model: the new item's tracks are chosen from it.
                val tracks = event.trackPreference?.let { TrackSelectionModel(preference = it) } ?: s.tracks
                if (event.resumeOfferMs != null && event.resumeOfferMs > 0) {
                    state = VodOverlayState(resumePrompt = ResumePrompt(event.resumeOfferMs), tracks = tracks)
                    emptyList()
                } else {
                    state = VodOverlayState(started = true, isPlaying = true, positionMs = event.startPositionMs, tracks = tracks)
                    listOf(OverlayCommand.Start(event.startPositionMs))
                }
            }
            OverlayEvent.ChooseResume -> startFromPrompt(s.resumePrompt?.positionMs ?: return emptyList())
            OverlayEvent.ChooseStartOver -> if (s.resumePrompt == null) emptyList() else startFromPrompt(0)
            OverlayEvent.PlayPause -> when {
                !s.started -> emptyList()
                s.isPlaying -> {
                    state = s.copy(isPlaying = false, controlsVisible = true)
                    listOf(OverlayCommand.Pause)
                }
                else -> {
                    state = s.copy(isPlaying = true)
                    listOf(OverlayCommand.Resume)
                }
            }
            is OverlayEvent.SeekKey -> {
                if (!s.started) return emptyList()
                val step = SeekAccelerator.stepMs(event.direction, event.repeatCount)
                val from = s.seek?.targetMs ?: s.positionMs
                state = s.copy(seek = SeekPreview(clamp(from + step, s.durationMs), step), controlsVisible = true)
                emptyList()
            }
            OverlayEvent.SeekKeyUp -> {
                val target = s.seek?.targetMs ?: return emptyList()
                state = s.copy(seek = null, positionMs = target).withCreditsReset(target)
                listOf(OverlayCommand.SeekTo(target))
            }
            is OverlayEvent.SeekTo -> {
                if (!s.started) return emptyList()
                val target = clamp(event.positionMs, s.durationMs)
                state = s.copy(seek = null, positionMs = target, controlsVisible = true).withCreditsReset(target)
                listOf(OverlayCommand.SeekTo(target))
            }
            is OverlayEvent.Progress -> onProgress(s, event)
            is OverlayEvent.Tick -> countdown(s, event.nowMs)
            OverlayEvent.CancelNext -> {
                countdownStartedAt = null
                state = s.copy(nextEpisode = null, nextCancelled = true)
                emptyList()
            }
            OverlayEvent.PlayNextNow -> advance(s)
            OverlayEvent.Ended -> if (nextLabel != null && !s.nextCancelled) advance(s) else {
                state = s.copy(isPlaying = false, nextEpisode = null, controlsVisible = true)
                emptyList()
            }
            is OverlayEvent.TracksChanged -> onTracksChanged(s, event.tracks)
            OverlayEvent.ToggleTrackPanel -> {
                state = s.copy(trackPanelOpen = !s.trackPanelOpen)
                emptyList()
            }
            is OverlayEvent.SelectAudio -> applyTracks(s, s.tracks.selectAudio(event.id))
            is OverlayEvent.SelectSubtitle -> applyTracks(s, s.tracks.selectSubtitle(event.id))
            OverlayEvent.ShowControls -> {
                state = s.copy(controlsVisible = true)
                emptyList()
            }
            OverlayEvent.HideControls -> {
                state = s.copy(controlsVisible = false, trackPanelOpen = false)
                emptyList()
            }
            OverlayEvent.SkipNow -> skipNow(s)
            is OverlayEvent.Markers -> {
                spans = event.spans
                emptyList()
            }
        }
    }

    private fun skipNow(s: VodOverlayState): List<OverlayCommand> {
        val prompt = s.skip ?: return emptyList()
        usedSkips += prompt.kind
        state = s.copy(skip = null)
        if (prompt.kind == SkipKind.CREDITS && nextLabel != null) return advance(state)
        val target = clamp(prompt.targetMs, s.durationMs)
        state = state.copy(positionMs = target)
        return listOf(OverlayCommand.SeekTo(target))
    }

    private fun startFromPrompt(position: Long): List<OverlayCommand> {
        state = state.copy(resumePrompt = null, started = true, isPlaying = true, positionMs = position)
        return listOf(OverlayCommand.Start(position))
    }

    private fun onProgress(s: VodOverlayState, e: OverlayEvent.Progress): List<OverlayCommand> {
        var next = s.copy(positionMs = e.positionMs, durationMs = e.durationMs, isPlaying = e.isPlaying)
        if (next.seek != null) next = next.copy(positionMs = s.positionMs) // keep the preview anchor
        val candidate = if (spans.isEmpty()) {
            SkipSegments.promptAt(e.positionMs, e.durationMs, isEpisode)
        } else {
            SkipSegments.promptFromSpans(spans, e.positionMs, e.durationMs, isEpisode)
        }
        val offer = candidate?.takeIf { it.kind !in usedSkips }
        next = next.copy(skip = if (next.started && next.resumePrompt == null && next.nextEpisode == null) offer else null)
        state = next
        val label = nextLabel
        if (label != null && !next.nextCancelled && next.nextEpisode == null && !next.advancing && inCredits(e.positionMs, e.durationMs)) {
            countdownStartedAt = e.nowMs
            state = next.copy(nextEpisode = NextEpisodeCard(label, (COUNTDOWN_MS / 1000).toInt()))
            return emptyList()
        }
        return countdown(state, e.nowMs)
    }

    private fun countdown(s: VodOverlayState, now: Long): List<OverlayCommand> {
        val card = s.nextEpisode ?: return emptyList()
        val started = countdownStartedAt ?: return emptyList()
        val remainingMs = COUNTDOWN_MS - (now - started)
        if (remainingMs <= 0) return advance(s)
        val seconds = ((remainingMs + 999) / 1000).toInt()
        if (seconds != card.secondsRemaining) state = s.copy(nextEpisode = card.copy(secondsRemaining = seconds))
        return emptyList()
    }

    private fun advance(s: VodOverlayState): List<OverlayCommand> {
        if (nextLabel == null || s.advancing) return emptyList()
        countdownStartedAt = null
        state = s.copy(nextEpisode = null, advancing = true)
        return listOf(OverlayCommand.PlayNext)
    }

    /**
     * Before a manual pick, a preference-driven model applies its choice when the player selected
     * something else (e.g. a default-flagged track, or a language the player's own rules missed).
     */
    private fun onTracksChanged(s: VodOverlayState, tracks: List<TrackOption>): List<OverlayCommand> {
        val next = s.tracks.withTracks(tracks)
        state = s.copy(tracks = next)
        if (next.preference == null || next.manual || tracks.isEmpty()) return emptyList()
        val playerAudio = tracks.firstOrNull { it.type == TrackType.AUDIO && it.selected }?.id
        val playerText = tracks.firstOrNull { it.type == TrackType.TEXT && it.selected }?.id
        if (next.selectedAudioId == playerAudio && next.selectedSubtitleId == playerText) return emptyList()
        return listOf(OverlayCommand.ApplyTracks(next.selectedAudioId, next.selectedSubtitleId))
    }

    /** A manual pick: applied at once; a changed language preference is saved too. */
    private fun applyTracks(s: VodOverlayState, tracks: TrackSelectionModel): List<OverlayCommand> {
        if (tracks == s.tracks) return emptyList()
        state = s.copy(tracks = tracks)
        val apply = OverlayCommand.ApplyTracks(tracks.selectedAudioId, tracks.selectedSubtitleId)
        val preference = tracks.preference
        return if (preference != null && preference != s.tracks.preference) {
            listOf(apply, OverlayCommand.SaveTrackPreference(preference))
        } else {
            listOf(apply)
        }
    }

    /** Seeking back out of the credits hides the card and re-arms it. */
    private fun VodOverlayState.withCreditsReset(target: Long): VodOverlayState =
        if (!inCredits(target, durationMs)) {
            countdownStartedAt = null
            copy(nextEpisode = null)
        } else {
            this
        }

    companion object {
        const val CREDITS_REMAINING_MS: Long = 30_000
        const val CREDITS_FRACTION: Double = 0.95
        const val COUNTDOWN_MS: Long = 10_000

        fun inCredits(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (durationMs - positionMs <= CREDITS_REMAINING_MS || positionMs >= durationMs * CREDITS_FRACTION)

        private fun clamp(target: Long, duration: Long): Long =
            if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0)
    }
}

package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.VodKind

/**
 * Playback progress rules (R19):
 * - saved every [SAVE_INTERVAL_MS] while playing and always on stop ([ProgressTracker]);
 * - resume is offered when [RESUME_MIN_FRACTION] < progress < [RESUME_MAX_FRACTION];
 * - an item counts as watched at [WATCHED_FRACTION] or more;
 * - continue watching lists resumable, unwatched items, one per title, newest first.
 */
class PlaybackProgressRepository(
    private val store: PlaybackProgressStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun get(key: ProgressKey): PlaybackProgress? = store.get(key)

    /** Position to offer "Resume" at, or null when resume is not offered. */
    suspend fun resumePosition(key: ProgressKey): Long? =
        store.get(key)?.takeIf { isResumable(it.positionMs, it.durationMs) }?.positionMs

    suspend fun isWatched(key: ProgressKey): Boolean =
        store.get(key)?.let { isWatched(it.positionMs, it.durationMs) } ?: false

    suspend fun save(
        key: ProgressKey,
        playlistId: Long,
        kind: VodKind,
        label: String,
        versionId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0 || positionMs < 0) return
        store.put(
            PlaybackProgress(
                key = key,
                playlistId = playlistId,
                kind = kind,
                label = label,
                versionId = versionId,
                positionMs = positionMs.coerceAtMost(durationMs),
                durationMs = durationMs,
                updatedAt = clock(),
            ),
        )
    }

    suspend fun clear(key: ProgressKey) = store.remove(key)

    /** Resumable, unwatched items, the latest per title, most recent first. */
    suspend fun continueWatching(limit: Int = 20): List<PlaybackProgress> =
        store.recent(limit * 4 + 20)
            .filter { isResumable(it.positionMs, it.durationMs) && !isWatched(it.positionMs, it.durationMs) }
            .distinctBy { it.key.titleKey }
            .take(limit)

    /** Every saved entry (watched ones too), most recently updated first. */
    suspend fun recent(limit: Int): List<PlaybackProgress> = store.recent(limit)

    /** A tracker for one playback session of [key]. */
    fun tracker(key: ProgressKey, playlistId: Long, kind: VodKind, label: String, versionId: String): ProgressTracker =
        ProgressTracker(this, clock, key, playlistId, kind, label, versionId)

    companion object {
        const val SAVE_INTERVAL_MS: Long = 10_000
        const val RESUME_MIN_FRACTION: Double = 0.05
        const val RESUME_MAX_FRACTION: Double = 0.95
        const val WATCHED_FRACTION: Double = 0.90

        fun fraction(positionMs: Long, durationMs: Long): Double =
            if (durationMs > 0) positionMs.toDouble() / durationMs else 0.0

        fun isResumable(positionMs: Long, durationMs: Long): Boolean {
            val f = fraction(positionMs, durationMs)
            return f > RESUME_MIN_FRACTION && f < RESUME_MAX_FRACTION
        }

        fun isWatched(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && fraction(positionMs, durationMs) >= WATCHED_FRACTION
    }
}

/**
 * Saves progress for one playback session: at most every
 * [PlaybackProgressRepository.SAVE_INTERVAL_MS] from [onPosition], and unconditionally from
 * [onStop]. The played version can change mid-session (failover) via [versionId].
 */
class ProgressTracker internal constructor(
    private val repository: PlaybackProgressRepository,
    private val clock: () -> Long,
    val key: ProgressKey,
    private val playlistId: Long,
    private val kind: VodKind,
    private val label: String,
    var versionId: String,
) {
    private var lastSavedAt: Long? = null

    /** Returns true when this call saved. */
    suspend fun onPosition(positionMs: Long, durationMs: Long): Boolean {
        val now = clock()
        val last = lastSavedAt
        if (last != null && now - last < PlaybackProgressRepository.SAVE_INTERVAL_MS) return false
        if (last == null) {
            // First report starts the interval; the first save is 10 s into the session.
            lastSavedAt = now
            return false
        }
        save(positionMs, durationMs, now)
        return true
    }

    suspend fun onStop(positionMs: Long, durationMs: Long) = save(positionMs, durationMs, clock())

    private suspend fun save(positionMs: Long, durationMs: Long, now: Long) {
        lastSavedAt = now
        repository.save(key, playlistId, kind, label, versionId, positionMs, durationMs)
    }
}

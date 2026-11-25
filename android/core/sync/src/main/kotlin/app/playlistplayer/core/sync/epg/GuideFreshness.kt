package app.playlistplayer.core.sync.epg

import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.network.RedactingLogger

/**
 * While the app is open, queues a guide import for every playlist whose guide is overdue: never
 * imported, or last imported at least [intervalHours] ago. The background periodic refresh needs an
 * idle device and an unmetered network, so on a TV that is in use it may not run for days.
 *
 * A playlist is skipped while an import of it is already queued or running ([isImportActive]), and
 * for [RETRY_AFTER_MS] after a failed attempt so a broken source is not downloaded every check.
 */
class GuideFreshness(
    private val epgStore: EpgStore,
    /** Playlists that have a guide source (Xtream provider guide or an override URL) and channels. */
    private val playlistIds: suspend () -> List<Long>,
    private val intervalHours: suspend () -> Long,
    private val isImportActive: suspend (playlistId: Long) -> Boolean,
    private val enqueueImport: suspend (playlistId: Long) -> Unit,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Queues imports for the overdue guides and returns their playlist ids. */
    suspend fun refreshOverdue(): List<Long> {
        val intervalMs = intervalHours().coerceAtLeast(1) * HOUR_MS
        val at = now()
        val queued = mutableListOf<Long>()
        for (id in playlistIds()) {
            val state = epgStore.state(id)
            val lastImport = state?.lastImportAt
            if (lastImport != null && at - lastImport < intervalMs) continue
            val lastAttempt = state?.lastAttemptAt
            val failedRecently = lastAttempt != null && (lastImport == null || lastAttempt > lastImport) &&
                at - lastAttempt < RETRY_AFTER_MS
            if (failedRecently) continue
            if (isImportActive(id)) continue
            enqueueImport(id)
            queued += id
        }
        if (queued.isNotEmpty()) logger.i("Guide overdue for playlist(s) ${queued.joinToString()}; queued a refresh")
        return queued
    }

    companion object {
        private const val HOUR_MS = 3_600_000L

        /** How often the app checks while it is open. */
        const val CHECK_INTERVAL_MS: Long = 15 * 60_000L

        /** Wait after a failed attempt before trying the same playlist again. */
        const val RETRY_AFTER_MS: Long = 60 * 60_000L
    }
}

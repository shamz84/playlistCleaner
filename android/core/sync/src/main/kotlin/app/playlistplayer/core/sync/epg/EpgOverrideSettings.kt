package app.playlistplayer.core.sync.epg

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.sync.secure.SecureStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Settings entry point for a playlist's XMLTV override (R6). The URL is a secret (it may carry a
 * token) and lives in [SecureStore]; Room only records that one is set. Changing or clearing it
 * queues an immediate re-import (connected network only), replacing any import in progress.
 */
class EpgOverrideSettings(
    private val secureStore: SecureStore,
    private val epgStore: EpgStore,
    /** The guide refresh interval chosen in Settings. */
    private val refreshIntervalHours: suspend () -> Long = { EpgSyncWorker.PERIODIC_INTERVAL_HOURS },
    private val workManager: () -> WorkManager,
) {
    /** Sets the override, or clears it (back to the provider guide) when [url] is null/blank. */
    suspend fun setOverride(playlistId: Long, url: String?): Result {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            secureStore.removeEpgOverrideUrl(playlistId)
            epgStore.updateState(playlistId) { it.copy(overrideUrlPresent = false) }
        } else {
            val parsed = trimmed.toHttpUrlOrNull() ?: return Result.InvalidUrl
            if (parsed.scheme != "http" && parsed.scheme != "https") return Result.InvalidUrl
            secureStore.putEpgOverrideUrl(playlistId, trimmed)
            epgStore.updateState(playlistId) { it.copy(overrideUrlPresent = true) }
        }
        val wm = workManager()
        EpgSyncWorker.enqueueImport(wm, playlistId, ExistingWorkPolicy.REPLACE)
        EpgSyncWorker.schedulePeriodic(wm, playlistId, refreshIntervalHours())
        return Result.Saved
    }

    suspend fun hasOverride(playlistId: Long): Boolean = secureStore.epgOverrideUrl(playlistId) != null

    /** The stored URL, for pre-filling the Settings field only. Never log it. */
    suspend fun overrideUrl(playlistId: Long): String? = secureStore.epgOverrideUrl(playlistId)

    enum class Result { Saved, InvalidUrl }

    companion object {
        /** Curated community guide offered (and on by default) when adding a playlist; it wins over the provider guide. */
        const val DEFAULT_URL = "https://github.com/ferteque/Curated-M3U-Repository/raw/refs/heads/main/epg6.xml.gz"
    }
}

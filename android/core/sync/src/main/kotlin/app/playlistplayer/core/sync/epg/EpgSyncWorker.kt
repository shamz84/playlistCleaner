package app.playlistplayer.core.sync.epg

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.sync.PlaylistSyncFollowUp
import app.playlistplayer.core.sync.SyncOutcome
import app.playlistplayer.core.sync.catalog.CatalogSyncFollowUp
import app.playlistplayer.core.sync.catalog.CatalogSyncOutcome
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * WorkManager entry point for one playlist's EPG import; all logic is in [EpgSyncer]. Retryable
 * failures (network, 5xx) retry with backoff up to [MAX_ATTEMPTS].
 *
 * **Scheduling** (U7 folded fix):
 * - The first import (after onboarding, a credential change or an override change) is a one-time
 *   request whose only constraint is a connected network ([importRequest]). It is deliberately
 *   *not* expedited: an expedited request needs `getForegroundInfo` (a foreground-service
 *   notification on API < 31) and is cut off by the expedited time quota, which a full provider
 *   import (86 MB of XML) can exceed. Without an idle or unmetered constraint it still starts as
 *   soon as the device is online.
 * - The daily refresh ([periodicRequest]) waits for an unmetered network and an idle device.
 */
class EpgSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val syncer: EpgSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, -1L)
        if (playlistId < 0) return Result.failure(workDataOf(KEY_OUTCOME to "InvalidInput"))
        val backfill = inputData.getBoolean(KEY_BACKFILL, true)
        return when (val outcome = syncer.sync(playlistId, backfill)) {
            is EpgSyncOutcome.Failed ->
                if (outcome.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(outputOf(playlistId, outcome).putString(KEY_FAILURE_KIND, outcome.kind).build())
                }
            else -> Result.success(outputOf(playlistId, outcome).build())
        }
    }

    private fun outputOf(playlistId: Long, outcome: EpgSyncOutcome): Data.Builder =
        Data.Builder().putLong(KEY_PLAYLIST_ID, playlistId).putString(KEY_OUTCOME, outcome.name)

    companion object {
        const val KEY_PLAYLIST_ID = "playlistId"
        const val KEY_BACKFILL = "backfill"
        const val KEY_OUTCOME = "outcome"
        const val KEY_FAILURE_KIND = "failureKind"
        const val MAX_ATTEMPTS = 3
        const val TAG = "epg-sync"
        const val PERIODIC_INTERVAL_HOURS = 24L

        fun uniqueName(playlistId: Long) = "epg-sync-$playlistId"
        fun periodicName(playlistId: Long) = "epg-sync-periodic-$playlistId"

        /** First import: connected network only, no idle/unmetered requirement. */
        val importConstraints: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Daily refresh: unmetered network and an idle device. */
        val periodicConstraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresDeviceIdle(true)
            .build()

        fun importRequest(playlistId: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<EpgSyncWorker>()
                .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId))
                .setConstraints(importConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

        fun periodicRequest(playlistId: Long, intervalHours: Long = PERIODIC_INTERVAL_HOURS): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<EpgSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId))
                .setConstraints(periodicConstraints)
                .addTag(TAG)
                .build()

        /**
         * Queues an import now. Use [ExistingWorkPolicy.REPLACE] when the source changed (override
         * set/cleared, credentials changed) so a running import of the old source is cancelled;
         * [ExistingWorkPolicy.KEEP] for chained "import if stale" requests.
         */
        fun enqueueImport(workManager: WorkManager, playlistId: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            workManager.enqueueUniqueWork(uniqueName(playlistId), policy, importRequest(playlistId))
        }

        fun schedulePeriodic(workManager: WorkManager, playlistId: Long, intervalHours: Long = PERIODIC_INTERVAL_HOURS) {
            workManager.enqueueUniquePeriodicWork(periodicName(playlistId), ExistingPeriodicWorkPolicy.KEEP, periodicRequest(playlistId, intervalHours))
        }

        fun cancel(workManager: WorkManager, playlistId: Long) {
            workManager.cancelUniqueWork(uniqueName(playlistId))
            workManager.cancelUniqueWork(periodicName(playlistId))
        }
    }
}

/** Creates [EpgSyncWorker]; add it to the app's DelegatingWorkerFactory. */
class EpgSyncWorkerFactory(private val syncer: () -> EpgSyncer) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            EpgSyncWorker::class.java.name -> EpgSyncWorker(appContext, workerParameters, syncer())
            else -> null
        }
}

/**
 * Chains the EPG import:
 * - after a successful catalog sync (`Updated`/`Fresh`) of an Xtream playlist, and
 * - after a playlist sync of a playlist with Xtream off but an override URL set,
 *
 * queuing a one-time import when the EPG is `epgStale` (first sync, credentials changed) or was
 * never imported, and making sure the daily refresh is scheduled.
 */
class EpgSyncChain(
    private val playlistStore: PlaylistStore,
    private val epgStore: EpgStore,
    private val hasOverride: suspend (playlistId: Long) -> Boolean,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val workManager: () -> WorkManager,
) : PlaylistSyncFollowUp, CatalogSyncFollowUp {

    override suspend fun afterSync(playlistId: Long, outcome: SyncOutcome) {
        if (outcome is SyncOutcome.Failed) return
        guard(playlistId) {
            val playlist = playlistStore.playlist(playlistId) ?: return@guard
            // Xtream playlists chain from the catalog sync instead.
            if (playlist.xtreamEnabled || !hasOverride(playlistId)) return@guard
            enqueueIfNeeded(playlistId)
        }
    }

    override suspend fun afterSync(playlistId: Long, outcome: CatalogSyncOutcome) {
        if (outcome !is CatalogSyncOutcome.Updated && outcome != CatalogSyncOutcome.Fresh) return
        guard(playlistId) { enqueueIfNeeded(playlistId) }
    }

    /** True when [playlistId] needs its first import or a re-import after a credential change. */
    suspend fun needsImport(playlistId: Long): Boolean =
        playlistStore.syncState(playlistId)?.epgStale == true || epgStore.state(playlistId)?.lastImportAt == null

    private suspend fun enqueueIfNeeded(playlistId: Long) {
        val wm = workManager()
        EpgSyncWorker.schedulePeriodic(wm, playlistId)
        if (needsImport(playlistId)) EpgSyncWorker.enqueueImport(wm, playlistId)
    }

    private suspend fun guard(playlistId: Long, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w("Could not queue the EPG import for playlist $playlistId", e)
        }
    }
}

/** Runs several playlist-sync follow-ups in order (e.g. catalog chain, then EPG chain). */
class CompositePlaylistSyncFollowUp(private val followUps: List<PlaylistSyncFollowUp>) : PlaylistSyncFollowUp {
    constructor(vararg followUps: PlaylistSyncFollowUp) : this(followUps.toList())

    override suspend fun afterSync(playlistId: Long, outcome: SyncOutcome) {
        followUps.forEach { it.afterSync(playlistId, outcome) }
    }
}

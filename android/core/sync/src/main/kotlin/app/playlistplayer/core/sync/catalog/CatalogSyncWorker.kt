package app.playlistplayer.core.sync.catalog

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.sync.PlaylistSyncFollowUp
import app.playlistplayer.core.sync.SyncOutcome
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * WorkManager entry point for one playlist's Xtream catalog sync; all logic is in
 * [CatalogSyncer]. Retryable failures (network, 5xx) retry with backoff up to [MAX_ATTEMPTS].
 * AccountExpired is a successful run with outcome `AccountExpired` (nothing to retry).
 */
class CatalogSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val syncer: CatalogSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, -1L)
        if (playlistId < 0) return Result.failure(workDataOf(KEY_OUTCOME to "InvalidInput"))
        val force = inputData.getBoolean(KEY_FORCE, false)
        return when (val outcome = syncer.sync(playlistId, force)) {
            is CatalogSyncOutcome.Failed ->
                if (outcome.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(outputOf(playlistId, outcome).putString(KEY_FAILURE_KIND, outcome.kind).build())
                }
            else -> Result.success(outputOf(playlistId, outcome).build())
        }
    }

    private fun outputOf(playlistId: Long, outcome: CatalogSyncOutcome): Data.Builder =
        Data.Builder().putLong(KEY_PLAYLIST_ID, playlistId).putString(KEY_OUTCOME, outcome.name)

    companion object {
        const val KEY_PLAYLIST_ID = "playlistId"
        const val KEY_FORCE = "force"
        const val KEY_OUTCOME = "outcome"
        const val KEY_FAILURE_KIND = "failureKind"
        const val MAX_ATTEMPTS = 3
        const val TAG = "catalog-sync"

        fun uniqueName(playlistId: Long) = "catalog-sync-$playlistId"

        private val networkConstraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Queues a catalog sync. [force] re-fetches even when the catalog is recent. With KEEP, a
         * sync that is already queued or running is not duplicated.
         */
        fun enqueue(workManager: WorkManager, playlistId: Long, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId, KEY_FORCE to force))
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(uniqueName(playlistId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(workManager: WorkManager, playlistId: Long) {
            workManager.cancelUniqueWork(uniqueName(playlistId))
        }
    }
}

/** Creates [CatalogSyncWorker]; add it to the app's DelegatingWorkerFactory. */
class CatalogSyncWorkerFactory(private val syncer: () -> CatalogSyncer) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            CatalogSyncWorker::class.java.name -> CatalogSyncWorker(appContext, workerParameters, syncer())
            else -> null
        }
}

/**
 * Chains a catalog sync after every playlist sync (one-time or periodic) that left usable
 * channels, when the playlist has Xtream processing enabled. The catalog syncer itself decides
 * whether the catalog is due, so an unchanged playlist costs one account-info request.
 */
class CatalogSyncChain(
    private val playlistStore: PlaylistStore,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val workManager: () -> WorkManager,
) : PlaylistSyncFollowUp {
    override suspend fun afterSync(playlistId: Long, outcome: SyncOutcome) {
        if (outcome is SyncOutcome.Failed) return
        try {
            val playlist = playlistStore.playlist(playlistId) ?: return
            if (!playlist.xtreamEnabled) return
            CatalogSyncWorker.enqueue(workManager(), playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w("Could not queue the catalog sync for playlist $playlistId", e)
        }
    }
}

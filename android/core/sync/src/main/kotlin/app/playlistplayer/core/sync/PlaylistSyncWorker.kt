package app.playlistplayer.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * WorkManager entry point for one playlist's sync. All logic lives in [PlaylistSyncer]; this class
 * only maps [SyncOutcome] to a WorkManager result. Network failures retry with backoff up to
 * [MAX_ATTEMPTS]; permanent failures (not shared, not found, quota) fail and wait for the next
 * scheduled run.
 */
class PlaylistSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val syncer: PlaylistSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, -1L)
        if (playlistId < 0) return Result.failure(workDataOf(KEY_OUTCOME to "InvalidInput"))
        return when (val outcome = syncer.sync(playlistId)) {
            is SyncOutcome.Failed ->
                if (outcome.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(outputOf(outcome).putString(KEY_FAILURE_KIND, outcome.kind).build())
                }
            else -> Result.success(outputOf(outcome).build())
        }
    }

    private fun outputOf(outcome: SyncOutcome): Data.Builder =
        Data.Builder().putLong(KEY_PLAYLIST_ID, inputData.getLong(KEY_PLAYLIST_ID, -1L)).putString(KEY_OUTCOME, outcome.name)

    companion object {
        const val KEY_PLAYLIST_ID = "playlistId"
        const val KEY_OUTCOME = "outcome"
        const val KEY_FAILURE_KIND = "failureKind"
        const val MAX_ATTEMPTS = 3
        const val TAG = "playlist-sync"

        fun uniqueName(playlistId: Long) = "playlist-sync-$playlistId"
        fun periodicName(playlistId: Long) = "playlist-sync-periodic-$playlistId"

        private val networkConstraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Runs a sync now (onboarding, manual refresh, launch-when-stale); replaces a queued one. */
        fun enqueue(workManager: WorkManager, playlistId: Long) {
            val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
                .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId))
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(uniqueName(playlistId), ExistingWorkPolicy.KEEP, request)
        }

        /** Scheduled refresh. The md5 gate makes an unchanged file cost one metadata request. */
        fun schedulePeriodic(workManager: WorkManager, playlistId: Long, intervalHours: Long = 6) {
            val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId))
                .setConstraints(networkConstraints)
                .addTag(TAG)
                .build()
            workManager.enqueueUniquePeriodicWork(periodicName(playlistId), ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(workManager: WorkManager, playlistId: Long) {
            workManager.cancelUniqueWork(uniqueName(playlistId))
            workManager.cancelUniqueWork(periodicName(playlistId))
        }
    }
}

/**
 * Creates [PlaylistSyncWorker] with its dependencies (manual DI). Register it in the app's
 * WorkManager configuration, typically inside a DelegatingWorkerFactory alongside other units'
 * factories. Returns null for other workers so delegation continues.
 */
class SyncWorkerFactory(private val syncer: () -> PlaylistSyncer) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            PlaylistSyncWorker::class.java.name -> PlaylistSyncWorker(appContext, workerParameters, syncer())
            else -> null
        }
}

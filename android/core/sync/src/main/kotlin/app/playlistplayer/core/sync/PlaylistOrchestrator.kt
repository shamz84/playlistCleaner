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
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.sync.catalog.CatalogSyncOutcome
import app.playlistplayer.core.sync.catalog.CatalogSyncWorker
import app.playlistplayer.core.sync.catalog.CatalogSyncer
import app.playlistplayer.core.sync.epg.EpgSyncOutcome
import app.playlistplayer.core.sync.epg.EpgSyncWorker
import app.playlistplayer.core.sync.epg.EpgSyncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** The per-playlist stages the orchestrator runs, in order. */
enum class PipelineStep { PLAYLIST, CATALOG, GUIDE }

/** Observable state of one queued [PipelineStep]. */
sealed interface StepState {
    /** Queued behind other work, waiting for a network, or backing off before a retry. */
    data object Pending : StepState

    data object Running : StepState

    /**
     * Finished. [outcome] is the step's outcome name: playlist `Updated`/`Unchanged`/`Rejected`/
     * `Failed`; catalog `Updated`/`Fresh`/`Skipped`/`AccountExpired`/`Failed`; guide `Imported`/
     * `Current` (already up to date)/`Skipped`/`Failed`. [failureKind] is set for `Failed`.
     */
    data class Finished(val outcome: String, val failureKind: String? = null, val reason: String? = null) : StepState

    /** Cancelled (playlist removed, Xtream turned off) or no longer known to WorkManager. */
    data object Cancelled : StepState

    val isTerminal: Boolean get() = this is Finished || this == Cancelled
}

/** The queued steps of one request for [playlistId]; observe with [PlaylistSyncScheduler.observe]. */
data class PipelineHandle(val playlistId: Long, val steps: Map<PipelineStep, UUID>)

/**
 * Schedules playlist → catalog → guide syncs. [PlaylistOrchestrator] is the WorkManager
 * implementation; features depend on this interface so they can be tested with a fake.
 */
interface PlaylistSyncScheduler {
    /** Queues a full refresh of [playlistId] (reuses one already waiting). */
    suspend fun refreshNow(playlistId: Long): PipelineHandle

    /** Queues a refresh of every playlist, in creation order. */
    suspend fun refreshAll(): List<PipelineHandle>

    /** Queues only the catalog and guide steps (e.g. after onboarding's inline playlist sync, or Xtream turned on). */
    suspend fun syncCatalogAndGuide(playlistId: Long, forceCatalog: Boolean = false, forceGuide: Boolean = false): PipelineHandle

    /** Cancels the playlist's queued and running catalog/guide work (Xtream turned off). */
    suspend fun cancelCatalogAndGuide(playlistId: Long)

    /** Cancels every queued, running and periodic job of [playlistId] (playlist removed). */
    suspend fun cancelAll(playlistId: Long)

    /** Sets the periodic "refresh all playlists" interval (Settings). */
    suspend fun schedulePeriodic(intervalHours: Long)

    fun observe(handle: PipelineHandle): Flow<Map<PipelineStep, StepState>>
}

/**
 * Per-playlist sync scheduling that runs **one playlist step at a time** across all playlists, to
 * stay within low-RAM budgets (a catalog sync and an 86 MB XMLTV import never overlap).
 *
 * **Queue design (chosen):** a single WorkManager unique chain [CHAIN_NAME] with
 * [ExistingWorkPolicy.APPEND_OR_REPLACE]. A refresh appends three [PlaylistPipelineWorker] steps
 * (PLAYLIST → CATALOG → GUIDE) for one playlist; WorkManager runs the chain strictly in order, so
 * work for different playlists is serialised and survives process death. Each step is its own
 * WorkManager execution (its own 10-minute budget). Every step is tagged with the playlist
 * (`pipeline-p<id>`) and its step, so it can be found, deduplicated and cancelled per playlist.
 *
 * Why not the existing per-playlist workers in that chain: they return `Result.failure` for
 * permanent failures, and WorkManager fails every dependent of a failed request, so one broken
 * playlist would fail every playlist queued after it. Pipeline steps therefore always finish with
 * `success` (the outcome is in their output data and in each store's sync state); retryable
 * failures retry with backoff a bounded number of times first. The steps call the same syncers
 * and chain rules as [PlaylistSyncWorker]/[CatalogSyncWorker]/[EpgSyncWorker] (catalog only when
 * Xtream processing is on and the playlist sync did not fail; guide after a usable catalog, or for
 * an override-only playlist, and only when [PipelineSteps.needsEpgImport] unless forced).
 *
 * **Cancellation.** Cancelling a request in the chain also cancels everything queued after it, and
 * the next APPEND_OR_REPLACE then drops the whole chain (WorkManager rules). So [cancelAll] and
 * [cancelCatalogAndGuide] only cancel the playlist's *running* step (stopping an in-flight
 * download), then re-append the other playlists' steps that were cancelled behind it. The
 * playlist's *waiting* steps stay queued and become no-ops: every step re-reads the playlist when it
 * starts ([PipelineSteps]: a removed playlist, or Xtream turned off, skips without a request).
 *
 * Periodic refresh: one periodic [PlaylistRefreshAllWorker] ([PERIODIC_NAME], interval from
 * Settings) appends every playlist to the chain; the md5/ETag gates make unchanged playlists cheap.
 * The daily idle/unmetered EPG refresh stays per-playlist ([EpgSyncWorker.schedulePeriodic]) and
 * is serialised in-process by the single [EpgSyncer]'s mutex.
 */
class PlaylistOrchestrator(
    private val playlistStore: PlaylistStore,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val workManager: () -> WorkManager,
) : PlaylistSyncScheduler {
    private val mutex = Mutex()

    override suspend fun refreshNow(playlistId: Long): PipelineHandle = mutex.withLock {
        pendingHandle(playlistId, PipelineStep.PLAYLIST, needForce = false)?.let { return@withLock it }
        append(playlistId, PipelineStep.entries.map { StepRequest(it, force = false) })
    }

    override suspend fun refreshAll(): List<PipelineHandle> =
        playlistStore.playlists().map { refreshNow(it.id) }

    override suspend fun syncCatalogAndGuide(playlistId: Long, forceCatalog: Boolean, forceGuide: Boolean): PipelineHandle =
        mutex.withLock {
            val needForce = forceCatalog || forceGuide
            pendingHandle(playlistId, PipelineStep.CATALOG, needForce)?.let { return@withLock it }
            append(playlistId, listOf(StepRequest(PipelineStep.CATALOG, forceCatalog), StepRequest(PipelineStep.GUIDE, forceGuide)))
        }

    override suspend fun cancelCatalogAndGuide(playlistId: Long) = mutex.withLock {
        cancelRunning { tags -> playlistTag(playlistId) in tags && (stepTag(PipelineStep.CATALOG) in tags || stepTag(PipelineStep.GUIDE) in tags) }
        val wm = workManager()
        CatalogSyncWorker.cancel(wm, playlistId)
        EpgSyncWorker.cancel(wm, playlistId)
    }

    override suspend fun cancelAll(playlistId: Long) = mutex.withLock {
        cancelRunning { tags -> playlistTag(playlistId) in tags }
        val wm = workManager()
        PlaylistSyncWorker.cancel(wm, playlistId)
        CatalogSyncWorker.cancel(wm, playlistId)
        EpgSyncWorker.cancel(wm, playlistId)
    }

    override suspend fun schedulePeriodic(intervalHours: Long) {
        val hours = intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
        val wm = workManager()
        val request = PeriodicWorkRequestBuilder<PlaylistRefreshAllWorker>(hours, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .addTag(TAG_PIPELINE)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        // The per-playlist periodic syncs (U3) would run playlists concurrently; this replaces them.
        playlistStore.playlists().forEach { wm.cancelUniqueWork(PlaylistSyncWorker.periodicName(it.id)) }
    }

    override fun observe(handle: PipelineHandle): Flow<Map<PipelineStep, StepState>> {
        if (handle.steps.isEmpty()) return flowOf(emptyMap())
        val wm = workManager()
        val flows = handle.steps.map { (step, id) ->
            wm.getWorkInfoByIdFlow(id).map { info -> step to stateOf(handle.playlistId, step, info) }
        }
        return combine(flows) { pairs -> pairs.toMap() }.distinctUntilChanged()
    }

    // ---- internals ----

    private data class StepRequest(val step: PipelineStep, val force: Boolean)

    private suspend fun append(playlistId: Long, steps: List<StepRequest>): PipelineHandle {
        val requests = steps.map { it.step to request(playlistId, it.step, it.force) }
        val wm = workManager()
        val operation = wm.beginUniqueWork(CHAIN_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, requests.first().second)
            .let { continuation -> requests.drop(1).fold(continuation) { c, (_, r) -> c.then(r) } }
            .enqueue()
        withContext(Dispatchers.IO) { operation.result.get() }
        logger.i("Queued ${steps.joinToString { it.step.name }} for playlist $playlistId")
        return PipelineHandle(playlistId, requests.associate { (step, r) -> step to r.id })
    }

    /**
     * The playlist's not-yet-started request that begins at [first] (and its later steps), or null.
     * With [needForce] only a forced pending request qualifies.
     */
    private suspend fun pendingHandle(playlistId: Long, first: PipelineStep, needForce: Boolean): PipelineHandle? {
        val pending = pendingInfos().filter { playlistTag(playlistId) in it.tags }
        val start = pending.firstOrNull { stepTag(first) in it.tags && (!needForce || TAG_FORCE in it.tags) } ?: return null
        val steps = linkedMapOf(first to start.id)
        PipelineStep.entries.filter { it.ordinal > first.ordinal }.forEach { later ->
            pending.firstOrNull { stepTag(later) in it.tags }?.let { steps[later] = it.id }
        }
        return PipelineHandle(playlistId, steps)
    }

    /** True while a GUIDE step for [playlistId] is waiting or running in the pipeline. */
    suspend fun isGuideStepActive(playlistId: Long): Boolean {
        val query = WorkQuery.Builder.fromTags(listOf(playlistTag(playlistId)))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING))
            .build()
        return workManager().getWorkInfosFlow(query).first().any { stepTag(PipelineStep.GUIDE) in it.tags }
    }

    private suspend fun pendingInfos(): List<WorkInfo> {
        val query = WorkQuery.Builder.fromTags(listOf(TAG_STEP))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED))
            .build()
        return workManager().getWorkInfosFlow(query).first()
    }

    /**
     * Cancels the running pipeline step if its tags match [select], then re-appends the steps that
     * WorkManager cancelled with it (everything waiting was queued behind it), except those that
     * match [select] themselves.
     */
    private suspend fun cancelRunning(select: (Set<String>) -> Boolean) {
        val wm = workManager()
        val all = WorkQuery.Builder.fromTags(listOf(TAG_STEP))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING))
            .build()
        val before = wm.getWorkInfosFlow(all).first()
        val running = before.filter { it.state == WorkInfo.State.RUNNING && select(it.tags) }
        if (running.isEmpty()) return
        val bystanders = before.filter { it.state != WorkInfo.State.RUNNING && !select(it.tags) }
        running.forEach { info -> withContext(Dispatchers.IO) { wm.cancelWorkById(info.id).result.get() } }
        logger.i("Cancelled the running pipeline step(s) ${running.size}; re-queuing ${bystanders.size} waiting step(s)")

        val cancelled = bystanders.filter { info ->
            wm.getWorkInfoByIdFlow(info.id).first()?.state == WorkInfo.State.CANCELLED
        }
        if (cancelled.isEmpty()) return
        val order = playlistStore.playlists().map { it.id }
        cancelled.groupBy { playlistIdOf(it.tags) }
            .toList()
            .sortedBy { (id, _) -> order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
            .forEach { (id, infos) ->
                if (id == null) return@forEach
                val steps = PipelineStep.entries.mapNotNull { step ->
                    infos.firstOrNull { stepTag(step) in it.tags }?.let { StepRequest(step, TAG_FORCE in it.tags) }
                }
                if (steps.isNotEmpty()) append(id, steps)
            }
    }

    private fun stateOf(playlistId: Long, step: PipelineStep, info: WorkInfo?): StepState = when (info?.state) {
        null, WorkInfo.State.CANCELLED -> StepState.Cancelled
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> StepState.Pending
        WorkInfo.State.RUNNING -> StepState.Running
        WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> StepState.Finished(
            outcome = info.outputData.getString(outcomeKey(playlistId, step)) ?: OUTCOME_FAILED,
            failureKind = info.outputData.getString(failureKey(playlistId, step)),
            reason = info.outputData.getString(reasonKey(playlistId, step)),
        )
    }

    companion object {
        const val CHAIN_NAME = "playlist-pipeline"
        const val PERIODIC_NAME = "playlist-refresh-all"
        const val TAG_PIPELINE = "playlist-pipeline"
        const val TAG_STEP = "playlist-pipeline-step"
        const val TAG_FORCE = "pipeline-force"
        const val KEY_PLAYLIST_ID = "pipeline.playlistId"
        const val KEY_STEP = "pipeline.step"
        const val KEY_FORCE = "pipeline.force"
        const val OUTCOME_FAILED = "Failed"
        const val MIN_INTERVAL_HOURS = 1L
        const val MAX_INTERVAL_HOURS = 168L

        fun playlistTag(playlistId: Long) = "pipeline-p$playlistId"
        fun stepTag(step: PipelineStep) = "pipeline-step-${step.name}"

        /**
         * Step outputs are namespaced by playlist: WorkManager merges a request's parent outputs
         * into its input, and in the shared chain a parent can belong to another playlist.
         */
        fun outcomeKey(playlistId: Long, step: PipelineStep) = "p$playlistId.${step.name}.outcome"
        fun failureKey(playlistId: Long, step: PipelineStep) = "p$playlistId.${step.name}.failureKind"
        fun reasonKey(playlistId: Long, step: PipelineStep) = "p$playlistId.${step.name}.reason"

        private fun playlistIdOf(tags: Set<String>): Long? =
            tags.firstNotNullOfOrNull { tag -> tag.removePrefix("pipeline-p").takeIf { tag.startsWith("pipeline-p") }?.toLongOrNull() }

        internal val networkConstraints: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun request(playlistId: Long, step: PipelineStep, force: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PlaylistPipelineWorker>()
                .setInputData(workDataOf(KEY_PLAYLIST_ID to playlistId, KEY_STEP to step.name, KEY_FORCE to force))
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_PIPELINE)
                .addTag(TAG_STEP)
                .addTag(playlistTag(playlistId))
                .addTag(stepTag(step))
                .apply { if (force) addTag(TAG_FORCE) }
                .build()
    }
}

/** What one pipeline step did; [retryable] failures may be retried by the worker. */
data class StepResult(val outcome: String, val failureKind: String? = null, val reason: String? = null, val retryable: Boolean = false)

/** Runs one pipeline step; [PipelineSteps] in production, a fake in scheduling tests. */
fun interface PipelineStepRunner {
    suspend fun run(step: PipelineStep, playlistId: Long, force: Boolean, previous: Map<PipelineStep, String>): StepResult
}

/**
 * The pipeline steps' logic, independent of WorkManager. [previous] holds the outcomes of the same
 * playlist's earlier steps in this request (null when the request started later in the pipeline).
 */
class PipelineSteps(
    private val playlistStore: PlaylistStore,
    private val playlistSyncer: () -> PlaylistSyncer,
    private val catalogSyncer: () -> CatalogSyncer,
    private val epgSyncer: () -> EpgSyncer,
    /** True when the guide needs its first import or a re-import (EpgSyncChain.needsImport). */
    private val needsEpgImport: suspend (playlistId: Long) -> Boolean,
    private val hasEpgOverride: suspend (playlistId: Long) -> Boolean,
    /** Ensures the periodic idle/unmetered guide refresh is scheduled (EpgSyncWorker.schedulePeriodic). */
    private val scheduleDailyGuide: suspend (playlistId: Long) -> Unit,
    private val logger: RedactingLogger = RedactingLogger.NONE,
) : PipelineStepRunner {
    override suspend fun run(step: PipelineStep, playlistId: Long, force: Boolean, previous: Map<PipelineStep, String>): StepResult =
        when (step) {
            PipelineStep.PLAYLIST -> playlist(playlistId)
            PipelineStep.CATALOG -> catalog(playlistId, force, previous)
            PipelineStep.GUIDE -> guide(playlistId, force, previous)
        }

    private suspend fun playlist(playlistId: Long): StepResult = when (val outcome = playlistSyncer().sync(playlistId)) {
        is SyncOutcome.Failed -> StepResult(outcome.name, outcome.kind, retryable = outcome.retryable)
        else -> StepResult(outcome.name)
    }

    private suspend fun catalog(playlistId: Long, force: Boolean, previous: Map<PipelineStep, String>): StepResult {
        if (previous[PipelineStep.PLAYLIST] == PlaylistOrchestrator.OUTCOME_FAILED) return skipped(REASON_PLAYLIST_FAILED)
        val playlist = playlistStore.playlist(playlistId) ?: return skipped(REASON_NO_PLAYLIST)
        if (!playlist.xtreamEnabled) return skipped(CatalogSyncer.REASON_DISABLED)
        if (playlistStore.syncState(playlistId)?.activeGeneration == null) return skipped(REASON_NO_CHANNELS)
        return when (val outcome = catalogSyncer().sync(playlistId, force)) {
            is CatalogSyncOutcome.Failed -> StepResult(outcome.name, outcome.kind, retryable = outcome.retryable)
            is CatalogSyncOutcome.Skipped -> skipped(outcome.reason)
            else -> StepResult(outcome.name)
        }
    }

    private suspend fun guide(playlistId: Long, force: Boolean, previous: Map<PipelineStep, String>): StepResult {
        if (previous[PipelineStep.PLAYLIST] == PlaylistOrchestrator.OUTCOME_FAILED) return skipped(REASON_PLAYLIST_FAILED)
        val playlist = playlistStore.playlist(playlistId) ?: return skipped(REASON_NO_PLAYLIST)
        if (playlistStore.syncState(playlistId)?.activeGeneration == null) return skipped(REASON_NO_CHANNELS)
        if (playlist.xtreamEnabled) {
            val catalog = previous[PipelineStep.CATALOG]
            if (catalog != null && catalog != CATALOG_UPDATED && catalog != CATALOG_FRESH) return skipped(REASON_CATALOG_NOT_READY)
        } else if (!hasEpgOverride(playlistId)) {
            return skipped(REASON_NO_GUIDE_SOURCE)
        }
        try {
            scheduleDailyGuide(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w("Could not schedule the daily guide refresh for playlist $playlistId", e)
        }
        if (!force && !needsEpgImport(playlistId)) return StepResult(OUTCOME_CURRENT)
        return when (val outcome = epgSyncer().sync(playlistId)) {
            is EpgSyncOutcome.Failed -> StepResult(outcome.name, outcome.kind, retryable = outcome.retryable)
            is EpgSyncOutcome.Skipped -> skipped(outcome.reason)
            else -> StepResult(outcome.name)
        }
    }

    private fun skipped(reason: String) = StepResult(OUTCOME_SKIPPED, reason = reason)

    companion object {
        const val OUTCOME_SKIPPED = "Skipped"
        const val OUTCOME_CURRENT = "Current"
        const val REASON_PLAYLIST_FAILED = "PlaylistSyncFailed"
        const val REASON_NO_PLAYLIST = "NoSuchPlaylist"
        const val REASON_NO_CHANNELS = "NoChannels"
        const val REASON_CATALOG_NOT_READY = "CatalogNotReady"
        const val REASON_NO_GUIDE_SOURCE = "NoGuideSource"
        private const val CATALOG_UPDATED = "Updated"
        private const val CATALOG_FRESH = "Fresh"
    }
}

/**
 * One step of the serial playlist pipeline (see [PlaylistOrchestrator]). Never returns
 * `failure` for an ordinary sync failure, so later playlists in the shared chain still run.
 */
class PlaylistPipelineWorker(
    context: Context,
    params: WorkerParameters,
    private val steps: PipelineStepRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(PlaylistOrchestrator.KEY_PLAYLIST_ID, -1L)
        val step = inputData.getString(PlaylistOrchestrator.KEY_STEP)?.let { name -> PipelineStep.entries.firstOrNull { it.name == name } }
        if (playlistId < 0 || step == null) return Result.success()
        val force = inputData.getBoolean(PlaylistOrchestrator.KEY_FORCE, false)
        val previous = PipelineStep.entries.filter { it.ordinal < step.ordinal }.mapNotNull { earlier ->
            inputData.getString(PlaylistOrchestrator.outcomeKey(playlistId, earlier))?.let { earlier to it }
        }.toMap()

        val result = steps.run(step, playlistId, force, previous)
        if (result.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()

        val output = Data.Builder()
        // Pass this playlist's earlier outcomes on to its next step (outputs become the child's input).
        inputData.keyValueMap.forEach { (key, value) ->
            if (key.startsWith("p$playlistId.") && value is String) output.putString(key, value)
        }
        output.putString(PlaylistOrchestrator.outcomeKey(playlistId, step), result.outcome)
        result.failureKind?.let { output.putString(PlaylistOrchestrator.failureKey(playlistId, step), it) }
        result.reason?.let { output.putString(PlaylistOrchestrator.reasonKey(playlistId, step), it) }
        return Result.success(output.build())
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/** Periodic entry point: queues every playlist on the serial pipeline. */
class PlaylistRefreshAllWorker(
    context: Context,
    params: WorkerParameters,
    private val scheduler: PlaylistSyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        scheduler.refreshAll()
        return Result.success()
    }
}

/** Creates the pipeline workers; add it to the app's DelegatingWorkerFactory. */
class PipelineWorkerFactory(
    private val steps: () -> PipelineStepRunner,
    private val scheduler: () -> PlaylistSyncScheduler,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            PlaylistPipelineWorker::class.java.name -> PlaylistPipelineWorker(appContext, workerParameters, steps())
            PlaylistRefreshAllWorker::class.java.name -> PlaylistRefreshAllWorker(appContext, workerParameters, scheduler())
            else -> null
        }
}

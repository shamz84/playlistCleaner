package app.playlistplayer.feature.playlists

import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.source.M3uUrlSource
import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.sync.PipelineHandle
import app.playlistplayer.core.sync.PipelineStep
import app.playlistplayer.core.sync.PipelineSteps
import app.playlistplayer.core.sync.PlaylistRegistry
import app.playlistplayer.core.sync.PlaylistSyncScheduler
import app.playlistplayer.core.sync.PlaylistSyncer
import app.playlistplayer.core.sync.StepState
import app.playlistplayer.core.sync.StreamUrlVault
import app.playlistplayer.core.sync.SyncOutcome
import app.playlistplayer.feature.onboarding.OnboardingError
import app.playlistplayer.feature.onboarding.OnboardingProgress
import app.playlistplayer.feature.onboarding.OnboardingResult
import app.playlistplayer.feature.onboarding.OnboardingStage
import app.playlistplayer.feature.onboarding.PlaylistOnboarder
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.onboarding.StageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The U9 [PlaylistOnboarder] over the U15 playlist stack (first run and "Add playlist").
 *
 * 1. Registers the playlist ([PlaylistRegistry]: Room row + encrypted source) — stage Playlist.
 * 2. Runs the playlist sync inline ([PlaylistSyncer]), so [addPlaylist] returns once the channels
 *    are stored — stages Playlist → Channels (with the channel count).
 * 3. With Xtream processing on (and available), queues the catalog and guide on the serial
 *    pipeline ([PlaylistSyncScheduler.syncCatalogAndGuide]) and reports their progress from
 *    WorkManager after [addPlaylist] returned. Otherwise Catalog and Guide are Skipped.
 *
 * A failure before the channels are stored, or cancellation (Back during progress), removes the
 * half-created playlist entirely: the syncer has already discarded its partial generation, and
 * [PlaylistRegistry.remove] plus the vault delete drop the row and its secrets.
 *
 * [scope] outlives one add (application scope): it runs the catalog/guide progress observation.
 */
class DefaultPlaylistOnboarder(
    private val registry: PlaylistRegistry,
    private val syncer: PlaylistSyncer,
    private val playlistStore: PlaylistStore,
    private val vault: StreamUrlVault,
    private val scheduler: PlaylistSyncScheduler,
    private val scope: CoroutineScope,
    private val logger: RedactingLogger = RedactingLogger.NONE,
) : PlaylistOnboarder {

    private val updates = MutableSharedFlow<OnboardingProgress>(extraBufferCapacity = 64)
    private var observation: Job? = null

    override val progress: Flow<OnboardingProgress> = updates.asSharedFlow()

    override suspend fun addPlaylist(name: String, source: SourceInput, xtreamEnabled: Boolean): OnboardingResult {
        observation?.cancel()
        val reference = when (val check = source.toReference()) {
            is SourceCheck.Invalid -> return OnboardingResult.Failure(OnboardingError.InvalidInput)
            is SourceCheck.Valid -> check.reference
        }
        emit(OnboardingStage.Playlist, StageStatus.Running)
        val playlistId = try {
            registry.add(name.trim(), reference, xtreamEnabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Could not register the playlist", e)
            return fail(OnboardingError.Other)
        }

        val outcome = try {
            syncer.sync(playlistId)
        } catch (e: CancellationException) {
            // The syncer discarded its partial generation; drop the half-created playlist too.
            withContext(NonCancellable) { discardPlaylist(playlistId) }
            throw e
        } catch (e: Exception) {
            logger.e("Onboarding sync of playlist $playlistId failed", e)
            discardPlaylist(playlistId)
            return fail(OnboardingError.Other)
        }

        val channelCount = when (outcome) {
            is SyncOutcome.Updated -> outcome.channelCount
            is SyncOutcome.Failed -> {
                discardPlaylist(playlistId)
                return fail(errorFor(outcome, reference))
            }
            // Neither can happen on a first sync; treat them as unusable unless channels exist.
            SyncOutcome.Unchanged, is SyncOutcome.Rejected -> playlistStore.activeChannelCount(playlistId).takeIf { it > 0 } ?: run {
                discardPlaylist(playlistId)
                return fail(OnboardingError.Other)
            }
        }
        emit(OnboardingStage.Playlist, StageStatus.Done)
        emit(OnboardingStage.Channels, StageStatus.Done, channelCount)

        val effectiveXtream = playlistStore.playlist(playlistId)?.xtreamEnabled == true
        if (!effectiveXtream) {
            emit(OnboardingStage.Catalog, StageStatus.Skipped)
            emit(OnboardingStage.Guide, StageStatus.Skipped)
            return OnboardingResult.Success(playlistId, xtreamEnabled = false)
        }

        try {
            val handle = scheduler.syncCatalogAndGuide(playlistId)
            emit(OnboardingStage.Catalog, StageStatus.Pending)
            emit(OnboardingStage.Guide, StageStatus.Pending)
            observation = scope.launch { observe(handle) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Channels are usable; the periodic refresh will fetch the catalog later.
            logger.w("Could not queue the catalog for playlist $playlistId", e)
            emit(OnboardingStage.Catalog, StageStatus.Failed)
            emit(OnboardingStage.Guide, StageStatus.Failed)
        }
        return OnboardingResult.Success(playlistId, xtreamEnabled = true)
    }

    private suspend fun observe(handle: PipelineHandle) {
        val last = HashMap<OnboardingStage, StageStatus>()
        scheduler.observe(handle)
            .takeWhile { steps -> !publish(steps, last) }
            .collect {}
    }

    /** Emits changed stage statuses; returns true once both stages are terminal. */
    private suspend fun publish(steps: Map<PipelineStep, StepState>, last: MutableMap<OnboardingStage, StageStatus>): Boolean {
        val catalog = steps[PipelineStep.CATALOG]?.let(::catalogStatus) ?: StageStatus.Failed
        val guide = steps[PipelineStep.GUIDE]?.let(::guideStatus) ?: StageStatus.Failed
        for ((stage, status) in listOf(OnboardingStage.Catalog to catalog, OnboardingStage.Guide to guide)) {
            if (last[stage] != status) {
                last[stage] = status
                emit(stage, status)
            }
        }
        return catalog.isTerminal && guide.isTerminal
    }

    private suspend fun emit(stage: OnboardingStage, status: StageStatus, itemCount: Int? = null) {
        updates.emit(OnboardingProgress(stage, status, itemCount))
    }

    private suspend fun fail(error: OnboardingError): OnboardingResult.Failure {
        emit(OnboardingStage.Playlist, StageStatus.Failed)
        return OnboardingResult.Failure(error)
    }

    private suspend fun discardPlaylist(playlistId: Long) {
        try {
            scheduler.cancelAll(playlistId)
            registry.remove(playlistId)
            vault.deletePlaylist(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w("Could not remove the half-created playlist $playlistId", e)
        }
    }

    companion object {
        /** Maps a failed first sync to what the onboarding screen explains. */
        fun errorFor(outcome: SyncOutcome.Failed, reference: SourceReference?): OnboardingError = when (outcome.kind) {
            SourceError.KIND_NOT_FOUND -> OnboardingError.NotFoundOrNotShared
            SourceError.KIND_NOT_SHARED -> OnboardingError.NotShared
            SourceError.KIND_QUOTA -> OnboardingError.QuotaExceeded
            SourceError.KIND_NETWORK -> OnboardingError.Network
            SourceError.KIND_UNAUTHORIZED ->
                // A get.php URL refused means the provider account itself (expired or wrong credentials).
                if (reference is SourceReference.M3uUrl && M3uUrlSource.accountFromUrl(reference.url) != null) {
                    OnboardingError.AccountExpired
                } else {
                    OnboardingError.NotShared
                }
            SourceError.KIND_NOT_A_PLAYLIST, PlaylistSyncer.KIND_EMPTY, PlaylistSyncer.KIND_MISSING_SOURCE -> OnboardingError.InvalidInput
            // 5xx from the source is retryable: explain it as a connection problem.
            SourceError.KIND_OTHER -> if (outcome.retryable) OnboardingError.Network else OnboardingError.Other
            else -> OnboardingError.Other
        }

        fun catalogStatus(state: StepState): StageStatus = when (state) {
            StepState.Pending -> StageStatus.Pending
            StepState.Running -> StageStatus.Running
            StepState.Cancelled -> StageStatus.Failed
            is StepState.Finished -> when (state.outcome) {
                "Updated", "Fresh" -> StageStatus.Done
                PipelineSteps.OUTCOME_SKIPPED -> StageStatus.Skipped
                else -> StageStatus.Failed // AccountExpired, Failed
            }
        }

        fun guideStatus(state: StepState): StageStatus = when (state) {
            StepState.Pending -> StageStatus.Pending
            StepState.Running -> StageStatus.Running
            StepState.Cancelled -> StageStatus.Failed
            is StepState.Finished -> when (state.outcome) {
                "Imported", PipelineSteps.OUTCOME_CURRENT -> StageStatus.Done
                PipelineSteps.OUTCOME_SKIPPED ->
                    // Skipped because the catalog did not load (account expired / failed) reads as a failure.
                    if (state.reason == PipelineSteps.REASON_CATALOG_NOT_READY) StageStatus.Failed else StageStatus.Skipped
                else -> StageStatus.Failed
            }
        }
    }
}

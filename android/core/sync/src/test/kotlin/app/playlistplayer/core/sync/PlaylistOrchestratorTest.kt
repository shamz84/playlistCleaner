package app.playlistplayer.core.sync

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.sync.catalog.CatalogSyncer
import app.playlistplayer.core.sync.epg.AndroidXmlPullSource
import app.playlistplayer.core.sync.epg.EpgSyncer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PlaylistOrchestratorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness
    private lateinit var workManager: WorkManager
    private lateinit var orchestrator: PlaylistOrchestrator

    private data class Call(val playlistId: Long, val step: PipelineStep, val force: Boolean, val previous: Map<PipelineStep, String>)

    private val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())
    private val results = HashMap<Pair<Long, PipelineStep>, StepResult>()
    private val gates = HashMap<Pair<Long, PipelineStep>, CompletableDeferred<Unit>>()

    /** Records calls; like [PipelineSteps], a removed playlist or Xtream off makes a step a no-op. */
    private val runner = PipelineStepRunner { step, playlistId, force, previous ->
        val playlist = h.store.playlist(playlistId) ?: return@PipelineStepRunner StepResult("Skipped", reason = "NoSuchPlaylist")
        if (step != PipelineStep.PLAYLIST && !playlist.xtreamEnabled) return@PipelineStepRunner StepResult("Skipped", reason = "XtreamDisabled")
        calls += Call(playlistId, step, force, previous)
        gates[playlistId to step]?.await()
        results[playlistId to step] ?: StepResult(
            when (step) {
                PipelineStep.PLAYLIST -> "Updated"
                PipelineStep.CATALOG -> "Updated"
                PipelineStep.GUIDE -> "Imported"
            },
        )
    }

    @Before
    fun setUp() {
        h = SyncHarness(temp.root)
        orchestrator = PlaylistOrchestrator(h.store) { workManager }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            h.context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(PipelineWorkerFactory({ runner }, { orchestrator }))
                .build(),
        )
        workManager = WorkManager.getInstance(h.context)
    }

    @After
    fun tearDown() = h.close()

    private suspend fun playlist(name: String): Long = h.registry.add(name, SourceReference.Drive("FILE_$name"))

    private val released = HashSet<UUID>()

    /** Releases queued steps one by one (constraints met) until the pipeline is idle. */
    private fun drain() {
        val driver = WorkManagerTestInitHelper.getTestDriver(h.context)!!
        repeat(500) {
            val infos = workManager.getWorkInfosByTag(PlaylistOrchestrator.TAG_STEP).get()
            if (infos.none { !it.state.isFinished }) return
            infos.filter { it.state == WorkInfo.State.ENQUEUED && released.add(it.id) }.forEach { driver.setAllConstraintsMet(it.id) }
            Thread.sleep(10)
        }
        error("pipeline did not drain")
    }

    /** Releases queued steps until [target] runs. */
    private fun releaseUntilRunning(target: UUID) {
        val driver = WorkManagerTestInitHelper.getTestDriver(h.context)!!
        repeat(500) {
            if (workManager.getWorkInfoById(target).get()?.state == WorkInfo.State.RUNNING) return
            workManager.getWorkInfosByTag(PlaylistOrchestrator.TAG_STEP).get()
                .filter { it.state == WorkInfo.State.ENQUEUED && released.add(it.id) }
                .forEach { driver.setAllConstraintsMet(it.id) }
            Thread.sleep(10)
        }
        error("step did not start")
    }

    private fun states(playlistId: Long): List<WorkInfo.State> =
        workManager.getWorkInfosByTag(PlaylistOrchestrator.playlistTag(playlistId)).get().map { it.state }

    @Test
    fun playlistsRunOneAtATimeInOrderWithTheirOwnStepOutcomes() = runBlocking<Unit> {
        val a = playlist("A")
        val b = playlist("B")

        val handles = orchestrator.refreshAll()
        drain()

        assertThat(handles.map { it.playlistId }).containsExactly(a, b).inOrder()
        assertThat(calls.map { it.playlistId to it.step }).containsExactly(
            a to PipelineStep.PLAYLIST, a to PipelineStep.CATALOG, a to PipelineStep.GUIDE,
            b to PipelineStep.PLAYLIST, b to PipelineStep.CATALOG, b to PipelineStep.GUIDE,
        ).inOrder()
        // Each step sees only its own playlist's earlier outcomes.
        assertThat(calls[3].previous).isEmpty()
        assertThat(calls[4].previous).containsExactly(PipelineStep.PLAYLIST, "Updated")
        assertThat(calls[5].previous).containsExactly(PipelineStep.PLAYLIST, "Updated", PipelineStep.CATALOG, "Updated")
    }

    @Test
    fun oneFailingPlaylistDoesNotFailTheNext() = runBlocking<Unit> {
        val a = playlist("A")
        val b = playlist("B")
        results[a to PipelineStep.PLAYLIST] = StepResult("Failed", failureKind = "NotFoundOrNotShared")

        val handleA = orchestrator.refreshNow(a)
        orchestrator.refreshNow(b)
        drain()

        assertThat(calls.filter { it.playlistId == b }.map { it.step }).containsExactlyElementsIn(PipelineStep.entries).inOrder()
        assertThat(states(b).toSet()).containsExactly(WorkInfo.State.SUCCEEDED)
        val observed = withTimeout(5_000) { orchestrator.observe(handleA).first { m -> m.values.all { it.isTerminal } } }
        assertThat(observed[PipelineStep.PLAYLIST]).isEqualTo(StepState.Finished("Failed", "NotFoundOrNotShared"))
        // The catalog step was told the playlist sync failed (PipelineSteps then skips it).
        assertThat(calls.first { it.playlistId == a && it.step == PipelineStep.CATALOG }.previous[PipelineStep.PLAYLIST]).isEqualTo("Failed")
    }

    @Test
    fun aWaitingRefreshIsReused() = runBlocking<Unit> {
        val a = playlist("A")
        val first = orchestrator.refreshNow(a)
        val second = orchestrator.refreshNow(a)

        assertThat(second).isEqualTo(first)
        drain()
        assertThat(calls.count { it.step == PipelineStep.PLAYLIST }).isEqualTo(1)
        // Once finished, a new refresh queues new work.
        assertThat(orchestrator.refreshNow(a)).isNotEqualTo(first)
    }

    @Test
    fun removingAPlaylistCancelsItsRunningStepAndRequeuesTheOthers() = runBlocking<Unit> {
        val a = playlist("A")
        val b = playlist("B")
        gates[a to PipelineStep.PLAYLIST] = CompletableDeferred()
        val handleA = orchestrator.refreshNow(a)
        val handleB = orchestrator.refreshNow(b)
        releaseUntilRunning(handleA.steps.getValue(PipelineStep.PLAYLIST))

        orchestrator.cancelAll(a)
        h.registry.remove(a)
        drain()

        assertThat(calls.map { it.playlistId to it.step }).containsExactly(
            a to PipelineStep.PLAYLIST, // started, then cancelled
            b to PipelineStep.PLAYLIST, b to PipelineStep.CATALOG, b to PipelineStep.GUIDE,
        ).inOrder()
        val observedA = orchestrator.observe(handleA).first()
        assertThat(observedA.values.toSet()).containsExactly(StepState.Cancelled)
        // B's original request was cancelled behind A and re-queued under new ids.
        assertThat(orchestrator.observe(handleB).first().values.toSet()).containsExactly(StepState.Cancelled)
        assertThat(states(b).filter { it == WorkInfo.State.SUCCEEDED }).hasSize(3)
    }

    @Test
    fun waitingStepsOfARemovedPlaylistBecomeNoOps() = runBlocking<Unit> {
        val a = playlist("A")
        val b = playlist("B")
        orchestrator.refreshNow(a)
        orchestrator.refreshNow(b)

        orchestrator.cancelAll(a)
        h.registry.remove(a)
        drain()

        assertThat(calls.map { it.playlistId }.toSet()).containsExactly(b)
        assertThat(calls.map { it.step }).containsExactlyElementsIn(PipelineStep.entries).inOrder()
    }

    @Test
    fun turningXtreamOffCancelsTheRunningCatalogButKeepsTheOthers() = runBlocking<Unit> {
        val a = playlist("A")
        val b = playlist("B")
        gates[a to PipelineStep.CATALOG] = CompletableDeferred()
        val handleA = orchestrator.refreshNow(a)
        orchestrator.refreshNow(b)
        releaseUntilRunning(handleA.steps.getValue(PipelineStep.CATALOG))

        h.store.setXtreamEnabled(a, false)
        orchestrator.cancelCatalogAndGuide(a)
        drain()

        assertThat(calls.map { it.playlistId to it.step }).containsExactly(
            a to PipelineStep.PLAYLIST, a to PipelineStep.CATALOG,
            b to PipelineStep.PLAYLIST, b to PipelineStep.CATALOG, b to PipelineStep.GUIDE,
        ).inOrder()
        assertThat(orchestrator.observe(handleA).first()[PipelineStep.GUIDE]).isEqualTo(StepState.Cancelled)
    }

    @Test
    fun catalogAndGuideRequestCarriesForceFlags() = runBlocking<Unit> {
        val a = playlist("A")
        val handle = orchestrator.syncCatalogAndGuide(a, forceCatalog = true, forceGuide = true)
        assertThat(handle.steps.keys).containsExactly(PipelineStep.CATALOG, PipelineStep.GUIDE).inOrder()
        // An unforced request does not reuse... but a forced one is reused by an unforced ask.
        assertThat(orchestrator.syncCatalogAndGuide(a)).isEqualTo(handle)
        drain()

        assertThat(calls.map { it.step to it.force }).containsExactly(PipelineStep.CATALOG to true, PipelineStep.GUIDE to true).inOrder()
        val observed = orchestrator.observe(handle).first { m -> m.values.all { it.isTerminal } }
        assertThat(observed[PipelineStep.GUIDE]).isEqualTo(StepState.Finished("Imported"))
    }

    @Test
    fun periodicRefreshUsesTheSettingsIntervalAndReplacesPerPlaylistPeriodicSync() = runBlocking<Unit> {
        val a = playlist("A")
        PlaylistSyncWorker.schedulePeriodic(workManager, a)

        orchestrator.schedulePeriodic(12)
        val periodic = workManager.getWorkInfosForUniqueWork(PlaylistOrchestrator.PERIODIC_NAME).get().single()
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(12L * 3_600_000)
        assertThat(workManager.getWorkInfosForUniqueWork(PlaylistSyncWorker.periodicName(a)).get().single().state)
            .isEqualTo(WorkInfo.State.CANCELLED)

        orchestrator.schedulePeriodic(3)
        val updated = workManager.getWorkInfosForUniqueWork(PlaylistOrchestrator.PERIODIC_NAME).get().single()
        assertThat(updated.periodicityInfo!!.repeatIntervalMillis).isEqualTo(3L * 3_600_000)
    }

    @Test
    fun workerRetriesRetryableFailuresThenSucceedsWithTheOutcome() = runBlocking<Unit> {
        val retrying = PipelineStepRunner { _, _, _, _ -> StepResult("Failed", "Network", retryable = true) }
        fun worker(attempt: Int) = TestListenableWorkerBuilder<PlaylistPipelineWorker>(h.context)
            .setWorkerFactory(PipelineWorkerFactory({ retrying }, { orchestrator }))
            .setInputData(
                workDataOf(
                    PlaylistOrchestrator.KEY_PLAYLIST_ID to 7L,
                    PlaylistOrchestrator.KEY_STEP to PipelineStep.CATALOG.name,
                    PlaylistOrchestrator.outcomeKey(7, PipelineStep.PLAYLIST) to "Updated",
                    PlaylistOrchestrator.outcomeKey(8, PipelineStep.PLAYLIST) to "Failed",
                ),
            )
            .setRunAttemptCount(attempt)
            .build()

        assertThat(worker(0).doWork()).isEqualTo(ListenableWorker.Result.retry())
        val last = worker(PlaylistPipelineWorker.MAX_ATTEMPTS - 1).doWork()
        assertThat(last).isInstanceOf(ListenableWorker.Result.Success::class.java)
        val output = last.outputData
        assertThat(output.getString(PlaylistOrchestrator.outcomeKey(7, PipelineStep.CATALOG))).isEqualTo("Failed")
        assertThat(output.getString(PlaylistOrchestrator.failureKey(7, PipelineStep.CATALOG))).isEqualTo("Network")
        // Its own earlier outcome is passed on; another playlist's is not.
        assertThat(output.getString(PlaylistOrchestrator.outcomeKey(7, PipelineStep.PLAYLIST))).isEqualTo("Updated")
        assertThat(output.getString(PlaylistOrchestrator.outcomeKey(8, PipelineStep.PLAYLIST))).isNull()
    }

    // ---- PipelineSteps decisions (no network: every branch here stops before a request) ----

    private fun steps(needsImport: Boolean = true, hasOverride: Boolean = false): PipelineSteps {
        val http = OkHttpClient()
        val catalog = CatalogStore(h.db)
        val xtream = XtreamClient(http)
        val catalogSyncer = CatalogSyncer(h.store, catalog, { h.secureStore.account(it) }, xtream)
        val epgSyncer = EpgSyncer(
            h.store, catalog, EpgStore(h.db), { h.secureStore.account(it) }, { null }, XmltvDownloader(http), xtream, AndroidXmlPullSource,
        )
        return PipelineSteps(
            h.store, { h.syncer({ FakeSource() }) }, { catalogSyncer }, { epgSyncer },
            needsEpgImport = { needsImport }, hasEpgOverride = { hasOverride }, scheduleDailyGuide = {},
        )
    }

    @Test
    fun stepsSkipCatalogAndGuideWhenXtreamIsOffOrThePlaylistFailed() = runBlocking<Unit> {
        val id = h.registry.add("Live only", SourceReference.Drive("F"), xtreamEnabled = false)
        val s = steps()
        assertThat(s.run(PipelineStep.PLAYLIST, id, false, emptyMap()).outcome).isEqualTo("Updated")

        assertThat(s.run(PipelineStep.CATALOG, id, false, emptyMap()))
            .isEqualTo(StepResult(PipelineSteps.OUTCOME_SKIPPED, reason = CatalogSyncer.REASON_DISABLED))
        assertThat(s.run(PipelineStep.GUIDE, id, false, emptyMap()).reason).isEqualTo(PipelineSteps.REASON_NO_GUIDE_SOURCE)

        val failed = mapOf(PipelineStep.PLAYLIST to PlaylistOrchestrator.OUTCOME_FAILED)
        assertThat(s.run(PipelineStep.CATALOG, id, false, failed).reason).isEqualTo(PipelineSteps.REASON_PLAYLIST_FAILED)
        assertThat(s.run(PipelineStep.GUIDE, id, false, failed).reason).isEqualTo(PipelineSteps.REASON_PLAYLIST_FAILED)
    }

    @Test
    fun guideWaitsForAUsableCatalogAndIsCurrentWhenNoImportIsNeeded() = runBlocking<Unit> {
        val id = h.registry.add("Xtream", SourceReference.Drive("F"))
        h.syncer({ FakeSource() }).sync(id)

        val expired = mapOf(PipelineStep.PLAYLIST to "Updated", PipelineStep.CATALOG to "AccountExpired")
        assertThat(steps().run(PipelineStep.GUIDE, id, false, expired).reason).isEqualTo(PipelineSteps.REASON_CATALOG_NOT_READY)

        val fresh = mapOf(PipelineStep.PLAYLIST to "Unchanged", PipelineStep.CATALOG to "Fresh")
        assertThat(steps(needsImport = false).run(PipelineStep.GUIDE, id, false, fresh).outcome).isEqualTo(PipelineSteps.OUTCOME_CURRENT)
    }
}

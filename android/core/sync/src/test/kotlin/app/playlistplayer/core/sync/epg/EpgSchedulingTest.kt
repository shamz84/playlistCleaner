package app.playlistplayer.core.sync.epg

import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.playlistplayer.core.sync.SyncOutcome
import app.playlistplayer.core.sync.catalog.CatalogSyncOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpgSchedulingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: EpgTestEnv
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        env = EpgTestEnv(temp.root)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            env.h.context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(env.h.context)
    }

    @After
    fun tearDown() = env.close()

    private fun info(name: String): WorkInfo? = workManager.getWorkInfosForUniqueWork(name).get().firstOrNull()

    @Test
    fun firstImportNeedsOnlyANetworkAndPeriodicRefreshNeedsIdleUnmetered() {
        EpgSyncWorker.enqueueImport(workManager, 7)
        EpgSyncWorker.schedulePeriodic(workManager, 7)

        val first = info(EpgSyncWorker.uniqueName(7))!!.constraints
        assertThat(first.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(first.requiresDeviceIdle()).isFalse()
        assertThat(first.requiresCharging()).isFalse()

        val periodic = info(EpgSyncWorker.periodicName(7))!!
        assertThat(periodic.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(periodic.constraints.requiresDeviceIdle()).isTrue()
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(24L * 60 * 60 * 1000)

        // The request objects carry the same constraints.
        assertThat(EpgSyncWorker.importRequest(1).workSpec.constraints.requiresDeviceIdle()).isFalse()
        assertThat(EpgSyncWorker.periodicRequest(1).workSpec.constraints.requiresDeviceIdle()).isTrue()
        assertThat(EpgSyncWorker.importRequest(1).workSpec.expedited).isFalse()
    }

    @Test
    fun chainQueuesImportAfterCatalogSyncWhenStaleOrNeverImported() = runBlocking<Unit> {
        val id = env.playlist()
        val chain = EpgSyncChain(env.h.store, env.epg, { env.h.secureStore.epgOverrideUrl(it) != null }) { workManager }

        // Failed/expired catalog syncs do not chain.
        chain.afterSync(id, CatalogSyncOutcome.Failed("Network", "x", retryable = true))
        assertThat(info(EpgSyncWorker.uniqueName(id))).isNull()

        assertThat(chain.needsImport(id)).isTrue() // epgStale after the first playlist sync
        chain.afterSync(id, CatalogSyncOutcome.Fresh)
        assertThat(info(EpgSyncWorker.uniqueName(id))).isNotNull()
        assertThat(info(EpgSyncWorker.periodicName(id))).isNotNull()

        // Once imported and not stale, only the periodic refresh remains.
        assertThat(env.syncer().sync(id, backfill = false)).isInstanceOf(EpgSyncOutcome.Imported::class.java)
        assertThat(chain.needsImport(id)).isFalse()
        workManager.cancelUniqueWork(EpgSyncWorker.uniqueName(id)).result.get()
        workManager.pruneWork().result.get()
        chain.afterSync(id, CatalogSyncOutcome.Fresh)
        assertThat(info(EpgSyncWorker.uniqueName(id))).isNull()
    }

    @Test
    fun playlistSyncChainsOnlyForNonXtreamPlaylistsWithOverride() = runBlocking<Unit> {
        val id = env.playlist(xtream = false)
        val chain = EpgSyncChain(env.h.store, env.epg, { env.h.secureStore.epgOverrideUrl(it) != null }) { workManager }
        chain.afterSync(id, SyncOutcome.Unchanged)
        assertThat(info(EpgSyncWorker.uniqueName(id))).isNull()

        env.h.secureStore.putEpgOverrideUrl(id, env.overrideUrl)
        chain.afterSync(id, SyncOutcome.Unchanged)
        assertThat(info(EpgSyncWorker.uniqueName(id))).isNotNull()
    }

    @Test
    fun overrideChangeReplacesQueuedImport() = runBlocking<Unit> {
        val id = env.playlist()
        val settings = EpgOverrideSettings(env.h.secureStore, env.epg) { workManager }
        assertThat(settings.setOverride(id, "not a url")).isEqualTo(EpgOverrideSettings.Result.InvalidUrl)
        assertThat(settings.setOverride(id, env.overrideUrl)).isEqualTo(EpgOverrideSettings.Result.Saved)
        assertThat(settings.hasOverride(id)).isTrue()
        assertThat(env.epg.state(id)!!.overrideUrlPresent).isTrue()
        val first = info(EpgSyncWorker.uniqueName(id))!!.id

        assertThat(settings.setOverride(id, null)).isEqualTo(EpgOverrideSettings.Result.Saved)
        assertThat(settings.hasOverride(id)).isFalse()
        val infos = workManager.getWorkInfosForUniqueWork(EpgSyncWorker.uniqueName(id)).get()
        assertThat(infos.map { it.id }).doesNotContain(first)
        assertThat(infos.single().constraints.requiresDeviceIdle()).isFalse()
    }

    @Test
    fun workerMapsOutcomes() = runBlocking<Unit> {
        val id = env.playlist()
        val factory = EpgSyncWorkerFactory { env.syncer() }
        val ok = TestListenableWorkerBuilder<EpgSyncWorker>(env.h.context)
            .setInputData(workDataOf(EpgSyncWorker.KEY_PLAYLIST_ID to id, EpgSyncWorker.KEY_BACKFILL to false))
            .setWorkerFactory(factory)
            .build()
            .doWork()
        assertThat(ok).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(ok.outputData.getString(EpgSyncWorker.KEY_OUTCOME)).isEqualTo("Imported")

        env.provider = { mockwebserver3.MockResponse.Builder().code(503).build() }
        val retry = TestListenableWorkerBuilder<EpgSyncWorker>(env.h.context)
            .setInputData(workDataOf(EpgSyncWorker.KEY_PLAYLIST_ID to id))
            .setWorkerFactory(factory)
            .build()
            .doWork()
        assertThat(retry).isInstanceOf(ListenableWorker.Result.Retry::class.java)

        val invalid = TestListenableWorkerBuilder<EpgSyncWorker>(env.h.context).setWorkerFactory(factory).build().doWork()
        assertThat(invalid).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }
}

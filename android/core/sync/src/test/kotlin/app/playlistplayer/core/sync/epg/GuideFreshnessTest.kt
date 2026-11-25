package app.playlistplayer.core.sync.epg

import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
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
class GuideFreshnessTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: EpgTestEnv
    private lateinit var workManager: WorkManager

    private val now = 1_000L * HOUR
    private val queued = mutableListOf<Long>()
    private val active = mutableSetOf<Long>()

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

    private fun freshness(ids: List<Long>, hours: Long = 24) = GuideFreshness(
        epgStore = env.epg,
        playlistIds = { ids },
        intervalHours = { hours },
        isImportActive = { it in active },
        enqueueImport = { queued += it },
        now = { now },
    )

    @Test
    fun onlyGuidesOlderThanTheIntervalOrNeverImportedAreQueued() = runBlocking<Unit> {
        val fresh = env.playlist()
        val old = env.playlist()
        val never = env.playlist()
        env.epg.updateState(fresh) { it.copy(lastImportAt = now - 23 * HOUR, lastAttemptAt = now - 23 * HOUR) }
        env.epg.updateState(old) { it.copy(lastImportAt = now - 25 * HOUR, lastAttemptAt = now - 25 * HOUR) }

        assertThat(freshness(listOf(fresh, old, never)).refreshOverdue()).containsExactly(old, never)
        assertThat(queued).containsExactly(old, never)
    }

    @Test
    fun theIntervalComesFromSettings() = runBlocking<Unit> {
        val id = env.playlist()
        env.epg.updateState(id) { it.copy(lastImportAt = now - 7 * HOUR, lastAttemptAt = now - 7 * HOUR) }

        assertThat(freshness(listOf(id), hours = 12).refreshOverdue()).isEmpty()
        assertThat(freshness(listOf(id), hours = 6).refreshOverdue()).containsExactly(id)
    }

    @Test
    fun aGuideAlreadyImportingIsLeftAlone() = runBlocking<Unit> {
        val id = env.playlist()
        active += id

        assertThat(freshness(listOf(id)).refreshOverdue()).isEmpty()
        assertThat(queued).isEmpty()
    }

    @Test
    fun aRecentFailedAttemptWaitsBeforeRetrying() = runBlocking<Unit> {
        val id = env.playlist()
        env.epg.updateState(id) { it.copy(lastImportAt = now - 48 * HOUR, lastAttemptAt = now - 30 * MINUTE) }
        assertThat(freshness(listOf(id)).refreshOverdue()).isEmpty()

        env.epg.updateState(id) { it.copy(lastAttemptAt = now - 61 * MINUTE) }
        assertThat(freshness(listOf(id)).refreshOverdue()).containsExactly(id)
    }

    @Test
    fun importsQueuedOrRunningThroughTheWorkerCountAsActive() = runBlocking<Unit> {
        assertThat(EpgSyncWorker.isImportActive(workManager, 9)).isFalse()
        // A periodic refresh waiting for its next run is not an import in progress.
        EpgSyncWorker.schedulePeriodic(workManager, 9)
        assertThat(EpgSyncWorker.isImportActive(workManager, 9)).isFalse()

        // A one-time import waiting for its network constraint is.
        EpgSyncWorker.enqueueImport(workManager, 9)
        assertThat(EpgSyncWorker.isImportActive(workManager, 9)).isTrue()
    }

    @Test
    fun changingTheIntervalUpdatesThePeriodicRefresh() {
        EpgSyncWorker.schedulePeriodic(workManager, 5, 24)
        EpgSyncWorker.schedulePeriodic(workManager, 5, 6)
        assertThat(periodicHours(5)).isEqualTo(24)

        EpgSyncWorker.schedulePeriodic(workManager, 5, 6, ExistingPeriodicWorkPolicy.UPDATE)
        assertThat(periodicHours(5)).isEqualTo(6)
    }

    private fun periodicHours(id: Long): Long =
        workManager.getWorkInfosForUniqueWork(EpgSyncWorker.periodicName(id)).get().single().periodicityInfo!!.repeatIntervalMillis / HOUR

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}

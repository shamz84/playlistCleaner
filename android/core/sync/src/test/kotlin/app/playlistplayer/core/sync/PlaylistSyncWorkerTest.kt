package app.playlistplayer.core.sync

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.source.SourceError
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
class PlaylistSyncWorkerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness
    private val source = FakeSource()
    private lateinit var factory: SyncWorkerFactory

    @Before
    fun setUp() {
        h = SyncHarness(temp.root)
        val syncer = h.syncer({ source })
        factory = SyncWorkerFactory { syncer }
    }

    @After
    fun tearDown() = h.close()

    private fun worker(playlistId: Long?, attempt: Int = 0): PlaylistSyncWorker =
        TestListenableWorkerBuilder<PlaylistSyncWorker>(h.context)
            .setWorkerFactory(factory)
            .setInputData(if (playlistId == null) workDataOf() else workDataOf(PlaylistSyncWorker.KEY_PLAYLIST_ID to playlistId))
            .setRunAttemptCount(attempt)
            .build()

    @Test
    fun successfulSyncSucceedsAndWritesChannels() = runBlocking<Unit> {
        val id = h.registry.add("My playlist", SourceReference.Drive("FILE_ID"))

        val result = worker(id).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(result.outputData.getString(PlaylistSyncWorker.KEY_OUTCOME)).isEqualTo("Updated")
        assertThat(h.store.channels(id)).hasSize(10)
    }

    @Test
    fun networkFailureRetriesThenFails() = runBlocking<Unit> {
        val id = h.registry.add("My playlist", SourceReference.Drive("FILE_ID"))
        source.error = SourceError.Network("offline")

        assertThat(worker(id, attempt = 0).doWork()).isEqualTo(ListenableWorker.Result.retry())
        val last = worker(id, attempt = PlaylistSyncWorker.MAX_ATTEMPTS - 1).doWork()
        assertThat(last).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(last.outputData.getString(PlaylistSyncWorker.KEY_FAILURE_KIND)).isEqualTo(SourceError.KIND_NETWORK)
    }

    @Test
    fun permanentFailureDoesNotRetry() = runBlocking<Unit> {
        val id = h.registry.add("My playlist", SourceReference.Drive("FILE_ID"))
        source.error = SourceError.NotFoundOrNotShared()

        val result = worker(id).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(result.outputData.getString(PlaylistSyncWorker.KEY_FAILURE_KIND)).isEqualTo(SourceError.KIND_NOT_FOUND)
    }

    @Test
    fun missingInputFails() = runBlocking<Unit> {
        assertThat(worker(null).doWork()).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }
}

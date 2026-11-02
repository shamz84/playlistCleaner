package app.playlistplayer.core.sync

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.sync.catalog.CatalogSyncOutcome
import app.playlistplayer.core.sync.catalog.CatalogSyncer
import app.playlistplayer.core.sync.epg.EpgSyncOutcome
import app.playlistplayer.core.sync.epg.EpgSyncer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class BackgroundWorkGateTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness

    @Before
    fun setUp() {
        h = SyncHarness(temp.root)
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun jobsNeverOverlapAndRunInArrivalOrder() = runBlocking<Unit> {
        val gate = BackgroundWorkGate()
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val order = Collections.synchronizedList(ArrayList<Int>())
        val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { gate.run("holder") { release.await() } }
        // Undispatched: each job reaches the gate (and queues) before the next one starts.
        val jobs = (0 until 8).map { i ->
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.run("job-$i") {
                    val now = inside.incrementAndGet()
                    maxInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    delay(10)
                    order += i
                    inside.decrementAndGet()
                }
            }
        }
        release.complete(Unit)
        holder.join()
        jobs.awaitAll()
        assertThat(maxInside.get()).isEqualTo(1)
        assertThat(order).containsExactlyElementsIn(0 until 8).inOrder()
        assertThat(gate.running).isNull()
    }

    @Test
    fun catalogSyncAndEpgImportWaitForTheSharedGate() = runBlocking<Unit> {
        val gate = BackgroundWorkGate()
        val http = OkHttpClient()
        val catalogStore = CatalogStore(h.db)
        val catalog = CatalogSyncer(h.store, catalogStore, { null }, XtreamClient(http), gate = gate)
        val epg = EpgSyncer(
            playlistStore = h.store,
            catalogStore = catalogStore,
            epgStore = EpgStore(h.db),
            accounts = { null },
            overrideUrls = { null },
            downloader = XmltvDownloader(http),
            xtreamClient = XtreamClient(http),
            gate = gate,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Another heavy job (e.g. the title index build) holds the gate.
        val holder = launch(Dispatchers.Default) {
            gate.run("title-index") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val catalogRun = async(Dispatchers.Default) { catalog.sync(404) }
        val epgRun = async(Dispatchers.Default) { epg.sync(404) }
        delay(200)
        assertThat(catalogRun.isCompleted).isFalse()
        assertThat(epgRun.isCompleted).isFalse()
        assertThat(gate.running).isEqualTo("title-index")

        release.complete(Unit)
        holder.join()
        withTimeout(10_000) {
            assertThat(catalogRun.await()).isInstanceOf(CatalogSyncOutcome.Failed::class.java)
            assertThat(epgRun.await()).isInstanceOf(EpgSyncOutcome.Failed::class.java)
        }
    }

    @Test
    fun lowPriorityDispatcherRunsOnOneMinimumPriorityDaemonThread() = runBlocking<Unit> {
        val gate = BackgroundWorkGate(BackgroundWorkGate.lowPriorityDispatcher("gate-test"))
        val threads = (0 until 3).map {
            async(Dispatchers.Default) { gate.run("job") { Thread.currentThread() } }
        }.awaitAll()
        assertThat(threads.map { it.name }.distinct()).containsExactly("gate-test")
        assertThat(threads.first().priority).isEqualTo(Thread.MIN_PRIORITY)
        assertThat(threads.first().isDaemon).isTrue()
    }
}

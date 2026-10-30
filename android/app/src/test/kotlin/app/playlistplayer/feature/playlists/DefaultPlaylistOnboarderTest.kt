package app.playlistplayer.feature.playlists

import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.sync.PipelineStep
import app.playlistplayer.core.sync.PipelineSteps
import app.playlistplayer.core.sync.StepState
import app.playlistplayer.core.sync.SyncOutcome
import app.playlistplayer.feature.onboarding.OnboardingError
import app.playlistplayer.feature.onboarding.OnboardingProgress
import app.playlistplayer.feature.onboarding.OnboardingResult
import app.playlistplayer.feature.onboarding.OnboardingStage
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.onboarding.StageStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class DefaultPlaylistOnboarderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: PlaylistTestEnv
    private lateinit var onboarder: DefaultPlaylistOnboarder
    private val seen: MutableList<OnboardingProgress> = Collections.synchronizedList(ArrayList())

    @Before
    fun setUp() {
        env = PlaylistTestEnv(temp.root)
        onboarder = env.onboarder()
    }

    @After
    fun tearDown() = env.close()

    /** Collects progress (like the view model, subscribed before the add) while [block] runs. */
    private fun <T> recording(block: suspend () -> T): T = runBlocking {
        // Unconfined: every update is recorded synchronously as it is emitted.
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { onboarder.progress.collect { seen += it } }
        try {
            block()
        } finally {
            collector.cancel()
        }
    }

    private suspend fun awaitProgress(stage: OnboardingStage, status: StageStatus) {
        withTimeout(5_000) {
            while (seen.none { it.stage == stage && it.status == status }) kotlinx.coroutines.delay(5)
        }
    }

    private suspend fun assertNothingLeft() {
        assertThat(env.store.playlists()).isEmpty()
        assertThat(env.secureStore.sourceReference(1)).isNull()
        assertThat(env.store.generationsPresent(1)).isEmpty()
        assertThat(env.vaultDir.resolve("p1").listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun xtreamPlaylistReportsChannelsThenCatalogAndGuideFromTheQueue() {
        val result = recording {
            val r = onboarder.addPlaylist("My playlist", SourceInput.DriveId("FILE_A"), xtreamEnabled = true)
            val handle = env.scheduler.handles.single()
            env.scheduler.set(handle, PipelineStep.CATALOG, StepState.Running)
            awaitProgress(OnboardingStage.Catalog, StageStatus.Running)
            env.scheduler.set(handle, PipelineStep.CATALOG, StepState.Finished("Updated"))
            env.scheduler.set(handle, PipelineStep.GUIDE, StepState.Running)
            awaitProgress(OnboardingStage.Guide, StageStatus.Running)
            env.scheduler.set(handle, PipelineStep.GUIDE, StepState.Finished("Imported"))
            awaitProgress(OnboardingStage.Guide, StageStatus.Done)
            r
        }

        val id = (result as OnboardingResult.Success).playlistId
        assertThat(result.xtreamEnabled).isTrue()
        assertThat(runBlocking { env.store.channels(id) }).hasSize(9)
        assertThat(seen.take(5)).containsExactly(
            OnboardingProgress(OnboardingStage.Playlist, StageStatus.Running),
            OnboardingProgress(OnboardingStage.Playlist, StageStatus.Done),
            OnboardingProgress(OnboardingStage.Channels, StageStatus.Done, itemCount = 9),
            OnboardingProgress(OnboardingStage.Catalog, StageStatus.Pending),
            OnboardingProgress(OnboardingStage.Guide, StageStatus.Pending),
        ).inOrder()
        val later = seen.drop(5).map { it.stage to it.status }
        assertThat(later).containsAtLeast(
            OnboardingStage.Catalog to StageStatus.Running,
            OnboardingStage.Catalog to StageStatus.Done,
            OnboardingStage.Guide to StageStatus.Running,
            OnboardingStage.Guide to StageStatus.Done,
        ).inOrder()
        assertThat(env.scheduler.calls).containsExactly("catalogAndGuide:$id:false:false")
    }

    @Test
    fun xtreamOffSkipsCatalogAndGuide() {
        val result = recording { onboarder.addPlaylist("Live only", SourceInput.DriveId("FILE_A"), xtreamEnabled = false) }

        assertThat(result).isEqualTo(OnboardingResult.Success((result as OnboardingResult.Success).playlistId, xtreamEnabled = false))
        assertThat(seen.map { it.stage to it.status }).containsAtLeast(
            OnboardingStage.Channels to StageStatus.Done,
            OnboardingStage.Catalog to StageStatus.Skipped,
            OnboardingStage.Guide to StageStatus.Skipped,
        ).inOrder()
        assertThat(env.scheduler.calls).isEmpty()
    }

    @Test
    fun notXtreamShapedPlaylistTurnsXtreamOffAndSkips() {
        env.server.enqueue(env.m3u(Playlists.generic))
        val result = recording { onboarder.addPlaylist("Web", SourceInput.M3uUrl(env.url("/list.m3u")), xtreamEnabled = true) }

        assertThat((result as OnboardingResult.Success).xtreamEnabled).isFalse()
        assertThat(seen.last()).isEqualTo(OnboardingProgress(OnboardingStage.Guide, StageStatus.Skipped))
    }

    @Test
    fun accountExpiredDuringTheCatalogMarksCatalogAndGuideFailed() {
        recording {
            onboarder.addPlaylist("P", SourceInput.DriveId("FILE_A"), xtreamEnabled = true)
            val handle = env.scheduler.handles.single()
            env.scheduler.set(handle, PipelineStep.CATALOG, StepState.Finished("AccountExpired"))
            env.scheduler.set(handle, PipelineStep.GUIDE, StepState.Finished(PipelineSteps.OUTCOME_SKIPPED, reason = PipelineSteps.REASON_CATALOG_NOT_READY))
            awaitProgress(OnboardingStage.Guide, StageStatus.Failed)
        }
        assertThat(seen.map { it.stage to it.status }).contains(OnboardingStage.Catalog to StageStatus.Failed)
    }

    @Test
    fun sourceErrorsMapToOnboardingErrorsAndLeaveNoPlaylistBehind() = runBlocking<Unit> {
        suspend fun failure(input: SourceInput, prepare: () -> Unit = {}): OnboardingError {
            prepare()
            val r = onboarder.addPlaylist("P", input, xtreamEnabled = true)
            assertThat(env.store.playlists()).isEmpty()
            return (r as OnboardingResult.Failure).error
        }

        env.drives["MISSING"] = FakeDriveSource("").apply { error = SourceError.NotFoundOrNotShared() }
        env.drives["QUOTA"] = FakeDriveSource("").apply { error = SourceError.QuotaExceeded() }
        env.drives["OFFLINE"] = FakeDriveSource("").apply { error = SourceError.Network("offline") }
        env.drives["EMPTY"] = FakeDriveSource("#EXTM3U\n")
        assertThat(failure(SourceInput.DriveId("MISSING"))).isEqualTo(OnboardingError.NotFoundOrNotShared)
        assertThat(failure(SourceInput.DriveId("QUOTA"))).isEqualTo(OnboardingError.QuotaExceeded)
        assertThat(failure(SourceInput.DriveId("OFFLINE"))).isEqualTo(OnboardingError.Network)
        assertThat(failure(SourceInput.DriveId("EMPTY"))).isEqualTo(OnboardingError.InvalidInput)

        val html = env.m3u("<!DOCTYPE html><html>portal</html>", contentType = "text/html")
        assertThat(failure(SourceInput.M3uUrl(env.url("/page"))) { env.server.enqueue(html) }).isEqualTo(OnboardingError.InvalidInput)
        assertThat(failure(SourceInput.M3uUrl(env.url("/get.php?username=u&password=p"))) { env.server.enqueue(env.m3u("", code = 401)) })
            .isEqualTo(OnboardingError.AccountExpired)
        assertThat(failure(SourceInput.M3uUrl(env.url("/private.m3u"))) { env.server.enqueue(env.m3u("", code = 403)) })
            .isEqualTo(OnboardingError.NotShared)
        assertThat(failure(SourceInput.M3uUrl(env.url("/busy.m3u"))) { env.server.enqueue(env.m3u("", code = 503)) })
            .isEqualTo(OnboardingError.Network)
        assertThat(failure(SourceInput.M3uUrl(env.url("/gone.m3u"))) { env.server.enqueue(env.m3u("", code = 404)) })
            .isEqualTo(OnboardingError.NotFoundOrNotShared)
        assertThat(failure(SourceInput.M3uUrl("not a url"))).isEqualTo(OnboardingError.InvalidInput)
        assertThat(env.secureStore.sourceReference(1)).isNull()
    }

    @Test
    fun errorMappingCoversSyncerKinds() {
        fun map(kind: String, retryable: Boolean = false) =
            DefaultPlaylistOnboarder.errorFor(SyncOutcome.Failed(kind, "m", retryable), reference = null)
        assertThat(map(SourceError.KIND_NOT_SHARED)).isEqualTo(OnboardingError.NotShared)
        assertThat(map(SourceError.KIND_OTHER)).isEqualTo(OnboardingError.Other)
        assertThat(map(SourceError.KIND_OTHER, retryable = true)).isEqualTo(OnboardingError.Network)
        assertThat(map(SourceError.KIND_NOT_A_PLAYLIST)).isEqualTo(OnboardingError.InvalidInput)
        assertThat(map("SomethingElse")).isEqualTo(OnboardingError.Other)
    }

    @Test
    fun cancellationDiscardsTheHalfWrittenGenerationAndThePlaylist() = runBlocking<Unit> {
        val written = CompletableDeferred<Unit>()
        env.drives["SLOW"] = FakeDriveSource(Playlists.generic).apply {
            hold = {
                written.complete(Unit)
                awaitCancellation()
            }
        }
        val add = async { onboarder.addPlaylist("Slow", SourceInput.DriveId("SLOW"), xtreamEnabled = false) }
        written.await()
        // Rows and the vault file of the pending generation exist at this point.
        assertThat(env.store.generationsPresent(1)).isNotEmpty()

        add.cancelAndJoin()

        assertNothingLeft()
    }

    @Test
    fun progressFlowIsHotAndOnlyCarriesTheAddInFlight() = runBlocking<Unit> {
        val firstUpdate = async(start = CoroutineStart.UNDISPATCHED) { onboarder.progress.first() }
        onboarder.addPlaylist("P", SourceInput.DriveId("FILE_A"), xtreamEnabled = false)
        assertThat(firstUpdate.await()).isEqualTo(OnboardingProgress(OnboardingStage.Playlist, StageStatus.Running))
        val updates = async(start = CoroutineStart.UNDISPATCHED) {
            onboarder.progress.takeWhile { it.stage != OnboardingStage.Guide }.toList()
        }
        onboarder.addPlaylist("Q", SourceInput.DriveId("FILE_B"), xtreamEnabled = false)
        assertThat(updates.await().map { it.stage }).containsExactly(
            OnboardingStage.Playlist, OnboardingStage.Playlist, OnboardingStage.Channels, OnboardingStage.Catalog,
        ).inOrder()
    }
}

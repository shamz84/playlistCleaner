package app.playlistplayer.feature.playlists

import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.ChannelEpgMapEntity
import app.playlistplayer.core.database.entity.EpgMapMethod
import app.playlistplayer.core.database.entity.EpgMapOrigin
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.ProgrammeSource
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.model.PlaylistSourceType
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.sync.SyncOutcome
import app.playlistplayer.feature.onboarding.OnboardingResult
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.settings.ProtectedAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: PlaylistTestEnv
    private lateinit var repo: PlaylistRepository

    @Before
    fun setUp() {
        env = PlaylistTestEnv(temp.root)
        repo = env.repository()
    }

    @After
    fun tearDown() = env.close()

    private suspend fun onboard(name: String, source: SourceInput, xtream: Boolean = true): Long {
        val result = env.onboarder().addPlaylist(name, source, xtream)
        return (result as OnboardingResult.Success).playlistId
    }

    private suspend fun drivePlaylist(name: String = "My playlist", fileId: String = "FILE_A"): Long =
        onboard(name, SourceInput.DriveId(fileId))

    @Test
    fun secondPlaylistByM3uUrlGetsItsOwnGroupsUnderItsNameAndTheFirstIsUnchanged() = runBlocking<Unit> {
        val first = drivePlaylist()
        val firstGroups = env.store.groups(first)
        val firstChannels = env.store.channels(first)

        env.server.enqueue(env.m3u(Playlists.generic))
        val second = onboard("Web radio", SourceInput.M3uUrl(env.url("/lists/radio.m3u")), xtream = false)

        val summaries = repo.observePlaylists().first { list -> list.size == 2 }
        assertThat(summaries.map { it.name }).containsExactly("My playlist", "Web radio").inOrder()
        assertThat(summaries.map { it.sourceType }).containsExactly(PlaylistSourceType.DRIVE, PlaylistSourceType.M3U_URL).inOrder()
        assertThat(summaries[1].counts).isEqualTo(StorageCounts(groups = 2, channels = 3))
        assertThat(env.store.groups(second).map { it.name }).containsExactly("News", "Radio").inOrder()
        assertThat(env.store.groups(first)).isEqualTo(firstGroups)
        assertThat(env.store.channels(first)).isEqualTo(firstChannels)
    }

    @Test
    fun getPhpUrlDerivesTheAccountAndDefaultsXtreamOn() = runBlocking<Unit> {
        env.server.enqueue(env.m3u(Playlists.xtream(6, env.serverHost, "u1", "p1")))
        val id = onboard("Provider", SourceInput.M3uUrl(env.url("/get.php?username=u1&password=p1&type=m3u_plus")))

        val summary = repo.summary(id)!!
        assertThat(summary.xtreamEnabled).isTrue()
        assertThat(summary.xtreamAvailable).isTrue()
        assertThat(env.secureStore.account(id)!!.username).isEqualTo("u1")
        assertThat(env.scheduler.calls).contains("catalogAndGuide:$id:false:false")
    }

    @Test
    fun nonXtreamPlaylistLoadsLiveOnlyAndCannotTurnXtreamOn() = runBlocking<Unit> {
        env.server.enqueue(env.m3u(Playlists.generic))
        val result = env.onboarder().addPlaylist("Web", SourceInput.M3uUrl(env.url("/plain.m3u")), xtreamEnabled = true)

        val id = (result as OnboardingResult.Success).playlistId
        assertThat(result.xtreamEnabled).isFalse()
        val summary = repo.summary(id)!!
        assertThat(summary.xtreamEnabled).isFalse()
        assertThat(summary.xtreamAvailable).isFalse()
        assertThat(summary.account).isNull()
        assertThat(repo.setXtreamEnabled(id, true)).isEqualTo(PlaylistActionResult.XtreamUnavailable)
        assertThat(env.scheduler.calls.none { it.startsWith("catalogAndGuide") }).isTrue()
        // Playable through the encrypted vault.
        assertThat(env.vault.streamUrl(id, 1)).isEqualTo("https://cdn.example.invalid/hls/news2.m3u8?token=tok-2")
    }

    @Test
    fun twoPlaylistsOnTheSameAccountStaySeparate() = runBlocking<Unit> {
        val a = drivePlaylist("A", "FILE_A")
        val b = drivePlaylist("B", "FILE_B")

        assertThat(env.secureStore.account(a)).isEqualTo(env.secureStore.account(b))
        assertThat(env.store.channels(a).map { it.playlistId }.distinct()).containsExactly(a)
        assertThat(env.store.channels(b).map { it.playlistId }.distinct()).containsExactly(b)
        assertThat(repo.playlists().map { it.counts.channels }).containsExactly(9, 9)
    }

    @Test
    fun onlyTheFailingPlaylistShowsTheCachedCopyBanner() = runBlocking<Unit> {
        val good = drivePlaylist("Good", "FILE_GOOD")
        val bad = drivePlaylist("Bad", "FILE_BAD")
        env.drives.getValue("FILE_BAD").error = SourceError.Network("offline")

        assertThat(env.syncer.sync(bad)).isInstanceOf(SyncOutcome.Failed::class.java)
        assertThat(env.syncer.sync(good)).isEqualTo(SyncOutcome.Unchanged)

        val byName = repo.playlists().associateBy { it.name }
        assertThat(byName.getValue("Bad").showCachedCopyBanner).isTrue()
        assertThat(byName.getValue("Bad").failureKind).isEqualTo(SourceError.KIND_NETWORK)
        assertThat(byName.getValue("Bad").counts.channels).isEqualTo(9)
        assertThat(byName.getValue("Good").showCachedCopyBanner).isFalse()
        assertThat(byName.getValue("Good").health).isEqualTo(SyncHealth.Ok)
    }

    @Test
    fun removeCancelsWorkAndDeletesEveryRowSecretAndStreamUrl() = runBlocking<Unit> {
        val keep = drivePlaylist("Keep", "FILE_KEEP")
        env.server.enqueue(env.m3u(Playlists.generic))
        val gone = onboard("Gone", SourceInput.M3uUrl(env.url("/gone.m3u")), xtream = false)
        seedCatalogAndGuide(gone)
        env.secureStore.putEpgOverrideUrl(gone, "https://guide.example.invalid/epg.xml")
        assertThat(env.vault.streamUrl(gone, 0)).isNotNull()

        assertThat(repo.remove(gone)).isEqualTo(PlaylistActionResult.Done)

        assertThat(env.scheduler.calls).contains("cancelAll:$gone")
        assertThat(env.store.playlist(gone)).isNull()
        assertThat(env.store.generationsPresent(gone)).isEmpty()
        assertThat(env.store.syncState(gone)).isNull()
        assertThat(env.catalog.state(gone)).isNull()
        assertThat(env.catalog.accountStatus(gone)).isNull()
        assertThat(env.catalog.generationsPresent(gone)).isEmpty()
        assertThat(env.epg.programmeCount(gone)).isEqualTo(0)
        assertThat(env.epg.mappings(gone)).isEmpty()
        assertThat(env.secureStore.sourceReference(gone)).isNull()
        assertThat(env.secureStore.epgOverrideUrl(gone)).isNull()
        assertThat(env.vault.streamUrl(gone, 0)).isNull()
        assertThat(env.vaultDir.resolve("p$gone").exists()).isFalse()
        assertThat(env.removedHook).containsExactly(gone)
        // The other playlist is untouched.
        assertThat(env.store.channels(keep)).hasSize(9)
        assertThat(env.secureStore.sourceReference(keep)).isEqualTo(SourceReference.Drive("FILE_KEEP"))
        assertThat(repo.playlists().map { it.id }).containsExactly(keep)
    }

    @Test
    fun mutatingActionsAskTheAuthorizerAndChangeNothingWhenRefused() = runBlocking<Unit> {
        val id = drivePlaylist()
        val asked = ArrayList<ProtectedAction>()
        val deny: PlaylistAuthorizer = { asked += it; false }

        assertThat(repo.rename(id, "New", deny)).isEqualTo(PlaylistActionResult.NotAuthorized)
        assertThat(repo.editSource(id, SourceInput.DriveId("OTHER"), deny)).isEqualTo(PlaylistActionResult.NotAuthorized)
        assertThat(repo.setXtreamEnabled(id, false, deny)).isEqualTo(PlaylistActionResult.NotAuthorized)
        assertThat(repo.remove(id, deny)).isEqualTo(PlaylistActionResult.NotAuthorized)

        assertThat(asked).containsExactly(
            ProtectedAction.EditPlaylist, ProtectedAction.EditPlaylist, ProtectedAction.ChangeXtreamProcessing, ProtectedAction.RemovePlaylist,
        ).inOrder()
        val playlist = env.store.playlist(id)!!
        assertThat(playlist.name).isEqualTo("My playlist")
        assertThat(playlist.xtreamEnabled).isTrue()
        assertThat(env.secureStore.sourceReference(id)).isEqualTo(SourceReference.Drive("FILE_A"))
        assertThat(env.scheduler.calls.none { it.startsWith("cancel") }).isTrue()
    }

    @Test
    fun renameTrimsAndRejectsBlankNames() = runBlocking<Unit> {
        val id = drivePlaylist()
        assertThat(repo.rename(id, "   ")).isEqualTo(PlaylistActionResult.Invalid(InvalidReason.BlankName))
        assertThat(repo.rename(id, "  Living room  ")).isEqualTo(PlaylistActionResult.Done)
        assertThat(env.store.playlist(id)!!.name).isEqualTo("Living room")
        assertThat(repo.rename(999, "x")).isEqualTo(PlaylistActionResult.NotFound)
    }

    @Test
    fun editSourceResyncsAndACredentialChangeInvalidatesTheCatalog() = runBlocking<Unit> {
        val id = drivePlaylist()
        env.store.clearCatalogStale(id)
        env.store.clearEpgStale(id)
        env.scheduler.calls.clear()

        env.server.enqueue(env.m3u(Playlists.xtream(9, "xtream.example.invalid:8080", user = "newuser", pass = "newpass")))
        val newUrl = env.url("/get.php?username=newuser&password=newpass")
        assertThat(repo.editSource(id, SourceInput.M3uUrl(newUrl))).isEqualTo(PlaylistActionResult.Done)

        assertThat(env.scheduler.calls).containsExactly("cancelAll:$id", "refresh:$id").inOrder()
        assertThat(env.store.playlist(id)!!.sourceType).isEqualTo(PlaylistSourceType.M3U_URL)
        assertThat(env.secureStore.sourceReference(id)).isEqualTo(SourceReference.M3uUrl(newUrl))
        assertThat(env.store.syncState(id)!!.sourceMd5).isNull()

        // The queued refresh (run here directly) downloads the new source and sees the new account.
        val outcome = env.syncer.sync(id) as SyncOutcome.Updated
        assertThat(outcome.credentialsChanged).isTrue()
        assertThat(env.store.syncState(id)!!.catalogStale).isTrue()
        assertThat(env.store.syncState(id)!!.epgStale).isTrue()
        assertThat(env.secureStore.account(id)!!.username).isEqualTo("newuser")

        assertThat(repo.editSource(id, SourceInput.M3uUrl("ftp://nope"))).isEqualTo(PlaylistActionResult.Invalid(InvalidReason.InvalidUrl))
    }

    @Test
    fun xtreamOffClearsCatalogAndProviderGuideButKeepsLiveAndOverrideGuide() = runBlocking<Unit> {
        val id = drivePlaylist()
        seedCatalogAndGuide(id)
        env.secureStore.putEpgOverrideUrl(id, "https://guide.example.invalid/epg.xml")
        env.scheduler.calls.clear()

        assertThat(repo.setXtreamEnabled(id, false)).isEqualTo(PlaylistActionResult.Done)

        assertThat(env.store.playlist(id)!!.xtreamEnabled).isFalse()
        assertThat(env.scheduler.calls).containsExactly("cancelCatalogAndGuide:$id", "catalogAndGuide:$id:false:false").inOrder()
        assertThat(env.catalog.state(id)).isNull()
        assertThat(env.catalog.accountStatus(id)).isNull()
        assertThat(env.catalog.generationsPresent(id)).isEmpty()
        assertThat(env.epg.mappings(id).map { it.origin }).containsExactly(EpgMapOrigin.OVERRIDE.name)
        assertThat(env.epg.programmes(id, listOf("override.ch", "provider.ch"), 0, Long.MAX_VALUE / 4).map { it.title })
            .containsExactly("Override show")
        assertThat(env.store.channels(id)).hasSize(9)
        val summary = repo.summary(id)!!
        assertThat(summary.account).isNull()
        assertThat(summary.counts.movies).isEqualTo(0)

        env.scheduler.calls.clear()
        assertThat(repo.setXtreamEnabled(id, true)).isEqualTo(PlaylistActionResult.Done)
        assertThat(env.store.playlist(id)!!.xtreamEnabled).isTrue()
        assertThat(env.scheduler.calls).containsExactly("catalogAndGuide:$id:true:true")
    }

    @Test
    fun summaryShowsAccountStatusAndStorageCounts() = runBlocking<Unit> {
        val id = drivePlaylist()
        seedCatalogAndGuide(id)

        val summary = repo.observePlaylists().first { it.single().account != null }.single()

        assertThat(summary.account).isEqualTo(AccountSummary("Active", true, 2_000_000_000_000L, 1, 0, env.now))
        assertThat(summary.counts).isEqualTo(StorageCounts(groups = 3, channels = 9, movies = 1, series = 0, programmes = 2))
        assertThat(summary.health).isEqualTo(SyncHealth.Ok)
    }

    @Test
    fun viewModelListsPlaylistsAndReportsRefusedActions() = runBlocking<Unit> {
        val id = drivePlaylist()
        val vm = PlaylistsViewModel(repo, authorize = { false }, externalScope = env.scope)

        val loaded = vm.state.first { !it.loading && it.playlists.isNotEmpty() }
        assertThat(loaded.playlists.single().id).isEqualTo(id)

        vm.remove(id)
        val refused = vm.state.first { it.message != null }
        assertThat(refused.message).isEqualTo(PlaylistMessage.NotAuthorized(id))
        assertThat(env.store.playlist(id)).isNotNull()

        vm.dismissMessage()
        vm.refresh(id)
        assertThat(vm.state.first { it.message != null }.message).isEqualTo(PlaylistMessage.RefreshQueued(id))
    }

    /** A catalog generation with one movie, an account status, and one provider + one override programme. */
    private suspend fun seedCatalogAndGuide(id: Long) {
        val generation = env.catalog.beginGeneration(id)
        env.catalog.writer(id, generation).insertVodStreams(
            listOf(
                VodStreamEntity(
                    playlistId = id, generation = generation, streamId = 1, name = "Film", icon = null, rating = null, added = null,
                    categoryId = "1", containerExtension = "mkv", tmdb = null, isAdult = false, providerOrder = 0,
                ),
            ),
        )
        env.catalog.activateGeneration(id, generation) { it.copy(vodCount = 1, lastSuccessAt = env.now) }
        env.catalog.putAccountStatus(AccountStatusEntity(id, "Active", true, 2_000_000_000_000L, 1, 0, "ts", "Europe/Amsterdam", false, env.now))

        val importId = env.epg.beginImport(id)
        fun programme(key: String, title: String) = ProgrammeEntity(
            playlistId = id, channelKey = key, startMillis = 10_000, stopMillis = 20_000, title = title, subTitle = null,
            description = null, categories = null, episodeNum = null, source = ProgrammeSource.XMLTV.name,
        )
        env.epg.stage(importId, listOf(programme("provider.ch", "Provider show"), programme("override.ch", "Override show")))
        env.epg.commitImport(
            id, importId, importStart = 0, pruneBefore = 0,
            mappings = listOf(
                ChannelEpgMapEntity(id, "s:10000", "provider.ch", EpgMapMethod.EXACT_ID.name, EpgMapOrigin.PROVIDER.name),
                ChannelEpgMapEntity(id, "s:10001", "override.ch", EpgMapMethod.EXACT_ID.name, EpgMapOrigin.OVERRIDE.name),
            ),
        ) { state, _, _, _ -> state.copy(lastImportAt = env.now, matchedCount = 2, totalCount = 9, overrideMatchedCount = 1, providerMatchedCount = 1) }
    }
}

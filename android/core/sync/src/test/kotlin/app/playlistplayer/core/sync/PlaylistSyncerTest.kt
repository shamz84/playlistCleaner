package app.playlistplayer.core.sync

import androidx.room3.useReaderConnection
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.network.source.SourceVersion
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
class PlaylistSyncerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness
    private val source = FakeSource()
    private lateinit var syncer: PlaylistSyncer

    @Before
    fun setUp() {
        h = SyncHarness(temp.root)
        syncer = h.syncer({ source })
    }

    @After
    fun tearDown() = h.close()

    private suspend fun addPlaylist(): Long = h.registry.add("My playlist", SourceReference.Drive("FILE_ID"))

    @Test
    fun firstSyncWritesFixtureInFileOrderAndStoresAccount() = runBlocking<Unit> {
        val id = addPlaylist()

        val outcome = syncer.sync(id)

        assertThat(outcome).isEqualTo(SyncOutcome.Updated(10, 3, credentialsChanged = true, mixedAccounts = true))
        assertThat(h.store.groups(id).map { it.name }).containsExactly("🇬🇧 TV Guide (UK)", "⚽ Sports", "🎬 Movies 24/7").inOrder()
        assertThat(h.store.groups(id).map { it.channelCount }).containsExactly(5, 3, 2).inOrder()
        val channels = h.store.channels(id)
        assertThat(channels).hasSize(10)
        assertThat(channels.first().isHeader).isTrue()
        assertThat(channels.first { it.name == "BBC One London" }.catchupDays).isEqualTo(3)
        assertThat(channels.first { it.name == "BBC One London" }.streamId).isEqualTo(1001L)
        assertThat(channels.last().name).isEqualTo("Channel 4")
        assertThat(channels.last().groupTitle).isEqualTo("🇬🇧 TV Guide (UK)")

        assertThat(h.secureStore.account(id)).isEqualTo(XtreamAccount("http", "example.invalid", 8080, "user", "pass"))
        val state = h.store.syncState(id)!!
        assertThat(state.channelCount).isEqualTo(10)
        assertThat(state.groupCount).isEqualTo(3)
        assertThat(state.mixedAccounts).isTrue()
        assertThat(state.sourceMd5).isEqualTo("md5-1")
        assertThat(state.sourceModifiedTime).isEqualTo(500L)
        assertThat(state.contentUpdatedAt).isEqualTo(h.now)
        assertThat(state.catalogStale).isTrue()
        assertThat(state.epgStale).isTrue()
        assertThat(state.lastFailureKind).isNull()
        assertThat(h.store.generationsPresent(id)).containsExactly(state.activeGeneration)
    }

    @Test
    fun unchangedSourceSkipsDownloadAndKeepsData() = runBlocking<Unit> {
        val id = addPlaylist()
        syncer.sync(id)
        val generation = h.store.syncState(id)!!.activeGeneration
        h.now += 1_000

        assertThat(syncer.sync(id)).isEqualTo(SyncOutcome.Unchanged)

        assertThat(source.downloads).isEqualTo(1)
        val state = h.store.syncState(id)!!
        assertThat(state.activeGeneration).isEqualTo(generation)
        assertThat(state.lastSuccessAt).isEqualTo(h.now)
        assertThat(state.contentUpdatedAt).isEqualTo(h.now - 1_000)
        assertThat(h.store.channels(id)).hasSize(10)
    }

    @Test
    fun networkDropMidDownloadKeepsPreviousGeneration() = runBlocking<Unit> {
        val id = addPlaylist()
        syncer.sync(id)
        val before = h.store.syncState(id)!!
        source.body = TestPlaylists.build(count = 200)
        source.version = SourceVersion(md5 = "md5-2")
        source.failAfterBytes = source.body.length / 2 // several batches (size 4) already inserted
        h.now += 1_000

        val outcome = syncer.sync(id)

        assertThat((outcome as SyncOutcome.Failed).kind).isEqualTo(SourceError.KIND_NETWORK)
        assertThat(outcome.retryable).isTrue()
        val after = h.store.syncState(id)!!
        assertThat(after.activeGeneration).isEqualTo(before.activeGeneration)
        assertThat(after.pendingGeneration).isNull()
        assertThat(after.lastFailureKind).isEqualTo(SourceError.KIND_NETWORK)
        assertThat(after.lastFailureAt).isEqualTo(h.now)
        assertThat(after.contentUpdatedAt).isEqualTo(before.contentUpdatedAt)
        assertThat(after.sourceMd5).isEqualTo("md5-1")
        assertThat(h.store.channels(id).map { it.name }).contains("BBC One London")
        assertThat(h.store.channels(id)).hasSize(10)
        assertThat(h.store.generationsPresent(id)).containsExactly(before.activeGeneration)
    }

    @Test
    fun failureIsClearedByNextSuccess() = runBlocking<Unit> {
        val id = addPlaylist()
        source.error = SourceError.Network("offline")
        assertThat(syncer.sync(id)).isInstanceOf(SyncOutcome.Failed::class.java)
        assertThat(h.store.syncState(id)!!.lastFailureKind).isEqualTo(SourceError.KIND_NETWORK)
        assertThat(h.store.channels(id)).isEmpty()

        source.error = null
        assertThat(syncer.sync(id)).isInstanceOf(SyncOutcome.Updated::class.java)
        assertThat(h.store.syncState(id)!!.lastFailureKind).isNull()
    }

    @Test
    fun credentialChangeUpdatesAccountAndMarksCatalogAndEpgStale() = runBlocking<Unit> {
        val id = addPlaylist()
        source.body = TestPlaylists.build(count = 20)
        syncer.sync(id)
        // Catalog and EPG sync ran since.
        h.store.clearCatalogStale(id)
        h.store.clearEpgStale(id)

        source.body = TestPlaylists.build(count = 20, user = "user2", pass = "pass2")
        source.version = SourceVersion(md5 = "md5-2")
        val outcome = syncer.sync(id)

        assertThat((outcome as SyncOutcome.Updated).credentialsChanged).isTrue()
        assertThat(h.secureStore.account(id)).isEqualTo(XtreamAccount("http", "example.invalid", 8080, "user2", "pass2"))
        val state = h.store.syncState(id)!!
        assertThat(state.catalogStale).isTrue()
        assertThat(state.epgStale).isTrue()
    }

    @Test
    fun sameCredentialsDoNotMarkStale() = runBlocking<Unit> {
        val id = addPlaylist()
        source.body = TestPlaylists.build(count = 20)
        syncer.sync(id)
        h.store.clearCatalogStale(id)
        h.store.clearEpgStale(id)

        source.body = TestPlaylists.build(count = 21)
        source.version = SourceVersion(md5 = "md5-2")
        val outcome = syncer.sync(id)

        assertThat((outcome as SyncOutcome.Updated).credentialsChanged).isFalse()
        assertThat(h.store.syncState(id)!!.catalogStale).isFalse()
        assertThat(h.store.syncState(id)!!.epgStale).isFalse()
        assertThat(h.store.channels(id)).hasSize(21)
    }

    @Test
    fun channelDropOverHalfIsRejectedWithWarning() = runBlocking<Unit> {
        val id = addPlaylist()
        source.body = TestPlaylists.build(count = 100)
        syncer.sync(id)
        val before = h.store.syncState(id)!!

        source.body = TestPlaylists.build(count = 49)
        source.version = SourceVersion(md5 = "md5-2")
        h.now += 1_000
        val outcome = syncer.sync(id)

        assertThat(outcome).isInstanceOf(SyncOutcome.Rejected::class.java)
        outcome as SyncOutcome.Rejected
        assertThat(outcome.previousCount).isEqualTo(100)
        assertThat(outcome.newCount).isEqualTo(49)
        val after = h.store.syncState(id)!!
        assertThat(after.activeGeneration).isEqualTo(before.activeGeneration)
        assertThat(after.lastWarning).contains("100")
        assertThat(after.lastWarningAt).isEqualTo(h.now)
        // The fingerprint is not advanced, so the next refresh re-checks the file.
        assertThat(after.sourceMd5).isEqualTo(before.sourceMd5)
        assertThat(h.store.channels(id)).hasSize(100)
        assertThat(h.store.generationsPresent(id)).containsExactly(before.activeGeneration)
    }

    @Test
    fun dropOfExactlyHalfIsAccepted() = runBlocking<Unit> {
        val id = addPlaylist()
        source.body = TestPlaylists.build(count = 100)
        syncer.sync(id)
        source.body = TestPlaylists.build(count = 50)
        source.version = SourceVersion(md5 = "md5-2")
        assertThat(syncer.sync(id)).isInstanceOf(SyncOutcome.Updated::class.java)
        assertThat(h.store.syncState(id)!!.lastWarning).isNull()
    }

    @Test
    fun emptyPlaylistIsAFailureAndKeepsCache() = runBlocking<Unit> {
        val id = addPlaylist()
        syncer.sync(id)
        source.body = "#EXTM3U\n"
        source.version = SourceVersion(md5 = "md5-2")

        val outcome = syncer.sync(id)

        assertThat((outcome as SyncOutcome.Failed).kind).isEqualTo(PlaylistSyncer.KIND_EMPTY)
        assertThat(h.store.channels(id)).hasSize(10)
    }

    @Test
    fun missingSourceAndUnknownPlaylistFail() = runBlocking<Unit> {
        assertThat((syncer.sync(999) as SyncOutcome.Failed).kind).isEqualTo(PlaylistSyncer.KIND_NO_PLAYLIST)
        val id = h.store.createPlaylist("No source", app.playlistplayer.core.model.PlaylistSourceType.DRIVE, true, 1L)
        assertThat((syncer.sync(id) as SyncOutcome.Failed).kind).isEqualTo(PlaylistSyncer.KIND_MISSING_SOURCE)
    }

    @Test
    fun nonXtreamPlaylistDisablesXtreamAndStoresNoAccount() = runBlocking<Unit> {
        val id = addPlaylist()
        source.body = "#EXTM3U\n#EXTINF:-1 group-title=\"Radio\",Radio One\nhttps://radio.example.invalid/one.mp3\n"

        val outcome = syncer.sync(id)

        assertThat((outcome as SyncOutcome.Updated).credentialsChanged).isFalse()
        assertThat(h.secureStore.account(id)).isNull()
        assertThat(h.store.playlist(id)!!.xtreamEnabled).isFalse()
        assertThat(h.store.channels(id).single().streamId).isNull()
    }

    @Test
    fun playlistsSyncIndependently() = runBlocking<Unit> {
        val sources = mapOf("A" to FakeSource(), "B" to FakeSource())
        sources.getValue("B").body = TestPlaylists.build(count = 3, user = "bob", pass = "bobpass")
        val multi = h.syncer({ ref -> sources.getValue((ref as SourceReference.Drive).fileId) })
        val a = h.registry.add("A", SourceReference.Drive("A"))
        val b = h.registry.add("B", SourceReference.Drive("B"))

        multi.sync(a)
        multi.sync(b)
        sources.getValue("A").error = SourceError.QuotaExceeded()
        sources.getValue("A").version = SourceVersion(md5 = "other")
        multi.sync(a)

        assertThat(h.store.channels(a)).hasSize(10)
        assertThat(h.store.channels(b)).hasSize(3)
        assertThat(h.store.syncState(a)!!.lastFailureKind).isEqualTo(SourceError.KIND_QUOTA)
        assertThat(h.store.syncState(b)!!.lastFailureKind).isNull()
        assertThat(h.secureStore.account(b)!!.username).isEqualTo("bob")

        h.registry.remove(a)
        assertThat(h.store.channels(a)).isEmpty()
        assertThat(h.secureStore.sourceReference(a)).isNull()
        assertThat(h.secureStore.account(a)).isNull()
        assertThat(h.store.channels(b)).hasSize(3)
    }

    @Test
    fun noColumnHoldsCredentialsOrStreamUrlsAfterFixtureSync() = runBlocking<Unit> {
        val id = addPlaylist()
        syncer.sync(id)
        // Also leave a failure message behind, which goes through the Redactor.
        source.error = SourceError.Other("boom http://example.invalid:8080/live/user/pass/1.ts?username=user&password=pass")
        source.version = SourceVersion(md5 = "md5-2")
        syncer.sync(id)

        val cells = h.db.useReaderConnection { connection ->
            val values = mutableListOf<String>()
            val tables = mutableListOf<String>()
            connection.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { st ->
                while (st.step()) tables += st.getText(0)
            }
            for (table in tables) {
                connection.usePrepared("SELECT * FROM \"$table\"") { st ->
                    while (st.step()) {
                        for (i in 0 until st.getColumnCount()) {
                            if (!st.isNull(i)) values += st.getText(i)
                        }
                    }
                }
            }
            values
        }
        assertThat(cells).isNotEmpty()
        // Masked values ("/live/***/***/", "username=***") are fine; anything unmasked is not.
        val forbidden = listOf(
            Regex("""/(live|movie|series|timeshift)/(?!\*\*\*)"""),
            Regex("""(?i)(username|password)=(?!\*\*\*)"""),
            Regex("""/user/pass/|/user2/pass2/|user2|pass2"""),
            Regex("""example\.invalid:8080|other\.example\.invalid"""),
        )
        for (pattern in forbidden) {
            assertThat(cells.filter { pattern.containsMatchIn(it) }).isEmpty()
        }
        assertThat(cells.any { it.contains("/live/***/***/") }).isTrue() // the failure message was stored, redacted
        assertThat(cells.filter { it == "user" || it == "pass" || it == "FILE_ID" }).isEmpty()
        // The DataStore file holds only ciphertext.
        val raw = h.dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
        for (needle in listOf("example.invalid", "FILE_ID", "\"pass\"", "\"user\"")) {
            assertThat(raw).doesNotContain(needle)
        }
    }
}

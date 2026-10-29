package app.playlistplayer.core.database

import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.model.PlaylistSourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: PlaylistStore

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        store = PlaylistStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun channel(playlistId: Long, generation: Long, ordinal: Int, name: String, group: Int = 0) = ChannelEntity(
        playlistId = playlistId,
        generation = generation,
        ordinal = ordinal,
        groupOrdinal = group,
        name = name,
        streamId = 1000L + ordinal,
        containerExtension = "ts",
        tvgId = "id$ordinal",
        tvgName = name,
        logo = null,
        cuid = null,
        tvgChno = null,
        catchupType = null,
        catchupDays = 0,
        isHeader = false,
    )

    private suspend fun writeGeneration(playlistId: Long, names: List<String>, groupName: String = "🇬🇧 UK"): Long {
        val generation = store.beginGeneration(playlistId)
        val writer = store.writer(playlistId, generation, batchSize = 2)
        names.forEachIndexed { i, name -> writer.add(channel(playlistId, generation, i, name)) }
        writer.writeGroups(listOf(ChannelGroupEntity(playlistId, generation, 0, groupName, names.size)))
        return generation
    }

    @Test
    fun pendingGenerationIsInvisibleUntilActivated() = runBlocking<Unit> {
        val id = store.createPlaylist("My playlist", PlaylistSourceType.DRIVE, xtreamEnabled = true, createdAt = 1L)
        val generation = writeGeneration(id, listOf("A", "B", "C"))

        assertThat(store.channels(id)).isEmpty()
        assertThat(store.groups(id)).isEmpty()
        assertThat(store.activeChannelCount(id)).isEqualTo(0)

        store.activateGeneration(id, generation) { it.copy(channelCount = 3) }

        assertThat(store.channels(id).map { it.name }).containsExactly("A", "B", "C").inOrder()
        assertThat(store.groups(id).single().name).isEqualTo("🇬🇧 UK")
        assertThat(store.channels(id).first().groupTitle).isEqualTo("🇬🇧 UK")
        assertThat(store.syncState(id)!!.activeGeneration).isEqualTo(generation)
        assertThat(store.syncState(id)!!.pendingGeneration).isNull()
    }

    @Test
    fun flipReplacesOldGenerationAndDeletesItsRows() = runBlocking<Unit> {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1L)
        val first = writeGeneration(id, listOf("A", "B"))
        store.activateGeneration(id, first) { it }
        val second = writeGeneration(id, listOf("X", "Y", "Z"))

        // While the second generation is written, readers still see the first.
        assertThat(store.channels(id).map { it.name }).containsExactly("A", "B").inOrder()

        val previous = store.activateGeneration(id, second) { it }

        assertThat(previous).isEqualTo(first)
        assertThat(store.channels(id).map { it.name }).containsExactly("X", "Y", "Z").inOrder()
        assertThat(store.generationsPresent(id)).containsExactly(second)
        assertThat(second).isGreaterThan(first)
    }

    @Test
    fun discardedGenerationIsDeletedAndPreviousStaysActive() = runBlocking<Unit> {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1L)
        val first = writeGeneration(id, listOf("A", "B"))
        store.activateGeneration(id, first) { it }
        val failed = writeGeneration(id, listOf("X"))

        store.discardGeneration(id, failed)

        assertThat(store.channels(id).map { it.name }).containsExactly("A", "B").inOrder()
        assertThat(store.generationsPresent(id)).containsExactly(first)
        assertThat(store.syncState(id)!!.pendingGeneration).isNull()
    }

    @Test
    fun abandonedGenerationIsCleanedUpByNextBegin() = runBlocking<Unit> {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1L)
        val first = writeGeneration(id, listOf("A"))
        store.activateGeneration(id, first) { it }
        val crashed = writeGeneration(id, listOf("X", "Y")) // never activated nor discarded (process death)

        val next = store.beginGeneration(id)

        assertThat(next).isGreaterThan(crashed)
        assertThat(store.generationsPresent(id)).containsExactly(first)
        assertThat(store.channels(id).map { it.name }).containsExactly("A")
    }

    @Test
    fun activatingANonPendingGenerationFails() = runBlocking<Unit> {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1L)
        val result = runCatching { store.activateGeneration(id, 42L) { it } }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun playlistsAreIsolatedAndDeleteIsScoped() = runBlocking<Unit> {
        val a = store.createPlaylist("A", PlaylistSourceType.DRIVE, true, 1L)
        val b = store.createPlaylist("B", PlaylistSourceType.M3U_URL, false, 2L)
        store.activateGeneration(a, writeGeneration(a, listOf("A1", "A2"))) { it }
        store.activateGeneration(b, writeGeneration(b, listOf("B1"))) { it }

        assertThat(store.channels(a).map { it.name }).containsExactly("A1", "A2")
        assertThat(store.channels(b).map { it.name }).containsExactly("B1")

        store.deletePlaylist(a)

        assertThat(store.playlist(a)).isNull()
        assertThat(store.channels(a)).isEmpty()
        assertThat(store.syncState(a)).isNull()
        assertThat(store.channels(b).map { it.name }).containsExactly("B1")
        assertThat(store.playlists().map { it.name }).containsExactly("B")
    }

    @Test
    fun flowsEmitActiveGeneration() = runBlocking<Unit> {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1L)
        store.activateGeneration(id, writeGeneration(id, listOf("A", "B"))) { it }
        assertThat(store.observeGroups(id).first().map { it.name }).containsExactly("🇬🇧 UK")
        assertThat(store.observeChannelsInGroup(id, 0).first().map { it.name }).containsExactly("A", "B").inOrder()
        assertThat(store.observeSyncState(id).first()!!.activeGeneration).isNotNull()
    }

    @Test
    fun updateSyncStateCannotMoveGenerationPointers() = runBlocking<Unit> {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, true, 1L)
        val generation = writeGeneration(id, listOf("A"))
        store.activateGeneration(id, generation) { it }
        store.updateSyncState(id) { it.copy(activeGeneration = 999, lastFailureKind = "Network") }
        val state = store.syncState(id)!!
        assertThat(state.activeGeneration).isEqualTo(generation)
        assertThat(state.lastFailureKind).isEqualTo("Network")
    }

    @Test
    fun schemaHasNoCredentialOrUrlColumns() = runBlocking<Unit> {
        val columns = db.useReaderConnection { connection ->
            val names = mutableListOf<String>()
            for (table in listOf("playlist", "channel_group", "channel", "sync_state")) {
                connection.usePrepared("PRAGMA table_info($table)") { statement ->
                    while (statement.step()) names += statement.getText(1).lowercase()
                }
            }
            names
        }
        assertThat(columns).isNotEmpty()
        for (forbidden in listOf("url", "password", "username", "host", "user", "pass", "credential", "fileid", "token")) {
            assertThat(columns.filter { it.contains(forbidden) }).isEmpty()
        }
    }
}

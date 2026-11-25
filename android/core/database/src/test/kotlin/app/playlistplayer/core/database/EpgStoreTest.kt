package app.playlistplayer.core.database

import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.dao.DROP_ARCHIVE_OVERLAPPING_SQL
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelEpgMapEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.EpgMapMethod
import app.playlistplayer.core.database.entity.EpgMapOrigin
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.ProgrammeSource
import app.playlistplayer.core.database.entity.epgEntryKey
import app.playlistplayer.core.model.PlaylistSourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpgStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: PlaylistStore
    private lateinit var epg: EpgStore

    private val hour = 3_600_000L
    private val now = 1_000L * hour

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        store = PlaylistStore(db)
        epg = EpgStore(db)
    }

    @After
    fun tearDown() = db.close()

    /** Entries 0 and 1 share stream-less tvg-id "a"; entry 2 has a stream id. */
    private suspend fun playlist(): Long {
        val id = store.createPlaylist("P", PlaylistSourceType.DRIVE, xtreamEnabled = true, createdAt = 1)
        val generation = store.beginGeneration(id)
        val writer = store.writer(id, generation)
        writer.add(ChannelEntity(id, generation, 0, 0, "A", null, null, "a", null, null, null, null, null, 0, false))
        writer.add(ChannelEntity(id, generation, 1, 0, "A backup", null, null, "a", null, null, null, null, null, 0, false))
        writer.add(ChannelEntity(id, generation, 2, 1, "B", 42L, "ts", "b", null, null, null, null, null, 3, false))
        writer.writeGroups(listOf(ChannelGroupEntity(id, generation, 0, "G0", 2), ChannelGroupEntity(id, generation, 1, "G1", 1)))
        store.activateGeneration(id, generation) { it }
        return id
    }

    private fun row(playlistId: Long, key: String, startH: Long, stopH: Long, title: String, source: ProgrammeSource = ProgrammeSource.XMLTV) =
        ProgrammeEntity(
            playlistId = playlistId, channelKey = key, startMillis = now + startH * hour, stopMillis = now + stopH * hour,
            title = title, subTitle = null, description = null, categories = null, episodeNum = null, source = source.name,
        )

    private fun maps(playlistId: Long) = listOf(
        ChannelEpgMapEntity(playlistId, epgEntryKey(null, 0), "a", EpgMapMethod.EXACT_ID.name, EpgMapOrigin.PROVIDER.name),
        ChannelEpgMapEntity(playlistId, epgEntryKey(null, 1), "a", EpgMapMethod.EXACT_ID.name, EpgMapOrigin.PROVIDER.name),
        ChannelEpgMapEntity(playlistId, epgEntryKey(42L, 2), "b", EpgMapMethod.EXACT_ID.name, EpgMapOrigin.PROVIDER.name),
    )

    private suspend fun import(playlistId: Long, rows: List<ProgrammeEntity>, at: Long = now, pruneBefore: Long = at - 96 * hour) {
        val importId = epg.beginImport(playlistId)
        epg.stage(importId, rows)
        epg.commitImport(playlistId, importId, at - hour, pruneBefore, maps(playlistId)) { s, _, from, to ->
            s.copy(lastImportAt = at, coverageFrom = from, coverageTo = to)
        }
    }

    @Test
    fun stagedRowsAreInvisibleUntilCommitAndDiscardRemovesThem() = runBlocking<Unit> {
        val id = playlist()
        val importId = epg.beginImport(id)
        epg.stage(importId, listOf(row(id, "a", 0, 1, "x")))
        assertThat(epg.programmeCount(id)).isEqualTo(0)
        assertThat(epg.pendingCount(id)).isEqualTo(1)
        epg.discardImport(id, importId)
        assertThat(epg.pendingCount(id)).isEqualTo(0)
        assertThat(epg.state(id)!!.pendingImportId).isNull()
    }

    @Test
    fun reimportKeepsPastReplacesFutureAndPrunes() = runBlocking<Unit> {
        val id = playlist()
        import(id, listOf(row(id, "a", -200, -199, "ancient"), row(id, "a", -5, -4, "old past"), row(id, "a", 1, 2, "old future")))
        assertThat(epg.programmeCount(id)).isEqualTo(2) // "ancient" ended before pruneBefore

        import(id, listOf(row(id, "a", -5, -4, "new past"), row(id, "a", -3, -2, "new gap"), row(id, "a", 1, 2, "new future")))
        val titles = epg.programmes(id, listOf("a"), now - 10 * hour, now + 10 * hour).map { it.title }
        assertThat(titles).containsExactly("old past", "new gap", "new future").inOrder()
        assertThat(epg.state(id)!!.coverageFrom).isEqualTo(now - 5 * hour)
    }

    @Test
    fun archiveRowsNeverOverlapAndXmltvWinsLater() = runBlocking<Unit> {
        val id = playlist()
        import(id, listOf(row(id, "b", -2, -1, "xmltv")))
        val inserted = epg.insertArchive(
            id, "b",
            listOf(row(id, "b", -2, -1, "dup", ProgrammeSource.XTREAM_ARCHIVE), row(id, "b", -30, -29, "archive", ProgrammeSource.XTREAM_ARCHIVE)),
        )
        assertThat(inserted).isEqualTo(1)
        // Re-running the backfill is idempotent.
        assertThat(epg.insertArchive(id, "b", listOf(row(id, "b", -30, -29, "archive", ProgrammeSource.XTREAM_ARCHIVE)))).isEqualTo(0)

        // A later XMLTV import covering that slot replaces the archive row.
        import(id, listOf(row(id, "b", -30, -29, "xmltv old")))
        val rows = epg.programmes(id, listOf("b"), now - 40 * hour, now)
        assertThat(rows.map { it.title to it.sourceType }).containsExactly(
            "xmltv old" to ProgrammeSource.XMLTV,
            "xmltv" to ProgrammeSource.XMLTV,
        ).inOrder()
    }

    @Test
    fun archiveOverlapUsesTheChannelAndTimeIndex() = runBlocking<Unit> {
        val plan = db.useReaderConnection { connection ->
            connection.usePrepared("EXPLAIN QUERY PLAN $DROP_ARCHIVE_OVERLAPPING_SQL") { statement ->
                buildList {
                    while (statement.step()) add(statement.getText(3))
                }
            }
        }
        assertThat(plan.any {
            it.contains("SEARCH n USING INDEX index_programme_playlistId_channelKey_startMillis") &&
                it.contains("playlistId=? AND channelKey=? AND startMillis>? AND startMillis<?")
        }).isTrue()
    }

    @Test
    fun largeImportReplacesOnlyOverlappingArchiveRows() = runBlocking<Unit> {
        val id = playlist()
        val otherId = playlist()
        val dao = db.programmeDao()
        val archive = (0 until 300).flatMap { channel ->
            (0 until 20).map { slot -> row(id, "channel-$channel", slot.toLong(), slot + 1L, "archive", ProgrammeSource.XTREAM_ARCHIVE) }
        }
        archive.chunked(500).forEach { dao.insert(it) }
        dao.insert(listOf(row(otherId, "channel-0", 0, 1, "other playlist", ProgrammeSource.XTREAM_ARCHIVE)))
        val importId = epg.beginImport(id)
        val staged = (0 until 300).flatMap { channel ->
            (0 until 100).map { slot -> row(id, "channel-$channel", slot.toLong(), slot + 1L, "xmltv") }
        }
        staged.chunked(500).forEach { epg.stage(importId, it) }

        epg.commitImport(id, importId, now - hour, now - 96 * hour, emptyList()) { state, _, _, _ -> state }

        assertThat(epg.programmeCount(id)).isEqualTo(30_000)
        assertThat(epg.pendingCount(id)).isEqualTo(0)
        assertThat(epg.programmes(otherId, listOf("channel-0"), now, now + hour).single().title).isEqualTo("other playlist")
    }

    @Test
    fun gridNowNextAndPastReadsUseTheMapping() = runBlocking<Unit> {
        val id = playlist()
        import(id, listOf(row(id, "a", -1, 1, "now a"), row(id, "a", 1, 2, "next a"), row(id, "b", -3, -2, "past b"), row(id, "b", 0, 1, "now b")))

        val grid = epg.gridForGroup(id, 0, now, now + 3 * hour)
        assertThat(grid.map { it.channelOrdinal to it.programme.title }).containsExactly(
            0 to "now a", 0 to "next a", 1 to "now a", 1 to "next a",
        ).inOrder()
        assertThat(grid[0].programme.id).isEqualTo(grid[2].programme.id)
        // Lazily loaded guide rows: only the channels in the ordinal range.
        assertThat(epg.gridForGroupRange(id, 0, 1, 1, now, now + 3 * hour).map { it.channelOrdinal to it.programme.title }).containsExactly(
            1 to "now a", 1 to "next a",
        ).inOrder()

        assertThat(epg.channelKey(id, 1)).isEqualTo("a")
        assertThat(epg.channelKeysInGroup(id, 1).map { it.channelKey }).containsExactly("b")
        val (current, next) = epg.nowNext(id, "a", now)
        assertThat(current!!.title).isEqualTo("now a")
        assertThat(next!!.title).isEqualTo("next a")
        assertThat(epg.programme(current.id)).isEqualTo(current)
        assertThat(epg.pastProgrammes(id, "b", now - 72 * hour, now).map { it.title }).containsExactly("past b")
    }

    @Test
    fun deletingThePlaylistRemovesEpgRows() = runBlocking<Unit> {
        val id = playlist()
        import(id, listOf(row(id, "a", 0, 1, "x")))
        store.deletePlaylist(id)
        assertThat(epg.programmeCount(id)).isEqualTo(0)
        assertThat(epg.mappings(id)).isEmpty()
        assertThat(epg.state(id)).isNull()
    }
}

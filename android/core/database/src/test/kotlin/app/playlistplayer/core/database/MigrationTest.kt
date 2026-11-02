package app.playlistplayer.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** AutoMigrations are additive: 2 -> 3 adds the EPG tables, 3 -> 4 personal data and search, 4 -> 5 the trending cache. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val dbFile: File = ApplicationProvider.getApplicationContext<android.content.Context>().getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        dbFile,
        BundledSQLiteDriver(),
        AppDatabase::class,
    )

    @Test
    fun migrate2To3KeepsDataAndAddsEpgTables() = runBlocking<Unit> {
        helper.createDatabase(2).apply {
            execSQL("INSERT INTO playlist (id, name, sourceType, xtreamEnabled, createdAt) VALUES (1, 'P', 'DRIVE', 1, 1)")
            close()
        }
        val connection = helper.runMigrationsAndValidate(3)
        val stmt = connection.prepare("SELECT COUNT(*) FROM playlist")
        stmt.step()
        assertThat(stmt.getLong(0)).isEqualTo(1)
        stmt.close()
        val epg = connection.prepare("SELECT COUNT(*) FROM programme")
        epg.step()
        assertThat(epg.getLong(0)).isEqualTo(0)
        epg.close()
        connection.close()
    }

    @Test
    fun migrate3To4KeepsDataAndAddsPersonalAndSearchTables() = runBlocking<Unit> {
        helper.createDatabase(3).apply {
            execSQL("INSERT INTO playlist (id, name, sourceType, xtreamEnabled, createdAt) VALUES (1, 'P', 'DRIVE', 1, 1)")
            close()
        }
        val connection = helper.runMigrationsAndValidate(4)
        fun count(sql: String): Long = connection.prepare(sql).use { it.step(); it.getLong(0) }

        assertThat(count("SELECT COUNT(*) FROM playlist")).isEqualTo(1)
        for (table in listOf("favourite", "playback_progress", "title_pick", "last_channel", "recent_channel", "title_override", "search_index_state")) {
            assertThat(count("SELECT COUNT(*) FROM $table")).isEqualTo(0)
        }
        // The migration spec adds Room's FTS content-sync triggers, so the index follows its content.
        assertThat(count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_%'")).isEqualTo(8)
        connection.execSQL(
            "INSERT INTO search_live_content (playlistId, ordinal, groupOrdinal, groupName, streamKey, normalizedName, displayName, logo) " +
                "VALUES (1, 0, 0, 'G', 's:1', 'uk bbc one hd', 'UK: BBC One HD', NULL)",
        )
        assertThat(count("SELECT COUNT(*) FROM search_live WHERE search_live MATCH '\"bbc one\"'")).isEqualTo(1)
        connection.execSQL("DELETE FROM search_live_content")
        assertThat(count("SELECT COUNT(*) FROM search_live WHERE search_live MATCH '\"bbc one\"'")).isEqualTo(0)
        connection.close()
    }

    @Test
    fun migrate4To5KeepsDataAndAddsTrendingTables() = runBlocking<Unit> {
        helper.createDatabase(4).apply {
            execSQL("INSERT INTO playlist (id, name, sourceType, xtreamEnabled, createdAt) VALUES (1, 'P', 'DRIVE', 1, 1)")
            execSQL(
                "INSERT INTO playback_progress (id, titleKey, season, episode, playlistId, kind, label, versionId, positionMs, durationMs, updatedAt, watched) " +
                    "VALUES ('k#-#-', 'k', NULL, NULL, 1, 'MOVIE', 'Dune', '101', 10, 100, 5, 0)",
            )
            close()
        }
        val connection = helper.runMigrationsAndValidate(5)
        fun count(sql: String): Long = connection.prepare(sql).use { it.step(); it.getLong(0) }

        assertThat(count("SELECT COUNT(*) FROM playlist")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM playback_progress")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM trending_title")).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM trending_state")).isEqualTo(0)
        connection.close()
    }

    @Test
    fun migrate5To6KeepsDataAndAddsTheTitleIndexTables() = runBlocking<Unit> {
        helper.createDatabase(5).apply {
            execSQL("INSERT INTO playlist (id, name, sourceType, xtreamEnabled, createdAt) VALUES (1, 'P', 'DRIVE', 1, 1)")
            execSQL("INSERT INTO catalog_sync_state (playlistId, activeGeneration, lastGeneration, accountExpired, liveInfoCount, liveMissingCount, vodCategoryCount, vodCount, seriesCategoryCount, seriesCount) VALUES (1, 3, 3, 0, 0, 0, 0, 1, 0, 0)")
            execSQL(
                "INSERT INTO vod_stream (playlistId, generation, streamId, name, icon, rating, added, categoryId, containerExtension, tmdb, isAdult, providerOrder) " +
                    "VALUES (1, 3, 101, 'EN - Dune (2021)', NULL, NULL, 5, '10', 'mkv', '438631', 0, 0)",
            )
            execSQL("INSERT INTO trending_state (kind, fetchedAt) VALUES ('MOVIE', 7)")
            close()
        }
        val connection = helper.runMigrationsAndValidate(6)
        fun count(sql: String): Long = connection.prepare(sql).use { it.step(); it.getLong(0) }

        assertThat(count("SELECT COUNT(*) FROM playlist")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM vod_stream")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM trending_state")).isEqualTo(1)
        // Empty until the app's title indexer groups the active catalog in the background.
        for (table in listOf("vod_title_state", "vod_title", "vod_title_item")) {
            assertThat(count("SELECT COUNT(*) FROM $table")).isEqualTo(0)
        }
        // The browse sorts and Trending lookups are indexed.
        assertThat(count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND tbl_name = 'vod_title' AND name LIKE 'index_%'")).isEqualTo(5)
        assertThat(count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND tbl_name = 'vod_title_item' AND name LIKE 'index_%'")).isEqualTo(2)
        connection.close()
    }
}

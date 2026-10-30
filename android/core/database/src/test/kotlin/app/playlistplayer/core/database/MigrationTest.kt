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

/** The 2 -> 3 AutoMigration only adds the EPG tables; existing rows survive. */
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
}

package app.playlistplayer.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.playlistplayer.core.database.dao.ChannelDao
import app.playlistplayer.core.database.dao.PlaylistDao
import app.playlistplayer.core.database.dao.SyncStateDao
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.PlaylistEntity
import app.playlistplayer.core.database.entity.SyncStateEntity
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Single source of truth. Uses the bundled SQLite driver so FTS5 (trigram) behaves the same on
 * every device regardless of the platform SQLite version.
 *
 * Invariant: no table stores stream URLs or credentials; only stream ids and attributes.
 */
@Database(
    entities = [
        PlaylistEntity::class,
        ChannelGroupEntity::class,
        ChannelEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val FILE_NAME: String = "playlist_player.db"

        fun create(
            context: Context,
            driver: SQLiteDriver = BundledSQLiteDriver(),
            queryContext: CoroutineContext = Dispatchers.IO,
        ): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .setDriver(driver)
                .setQueryCoroutineContext(queryContext)
                .build()

        fun createInMemory(
            context: Context,
            driver: SQLiteDriver = BundledSQLiteDriver(),
            queryContext: CoroutineContext = Dispatchers.IO,
        ): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .setDriver(driver)
                .setQueryCoroutineContext(queryContext)
                .build()
    }
}

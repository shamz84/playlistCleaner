package app.playlistplayer.core.database

import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.entity.TrendingTitleEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrendingStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: TrendingStore

    @Before
    fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        store = TrendingStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(id: Long, title: String) = TrendingTitleEntity("", 0, id, title, null, 2024)

    @Test
    fun replaceKeepsOrderPerKindAndRecordsFetchTime() = runBlocking<Unit> {
        assertThat(store.cached("MOVIE")).isNull()
        store.replace("MOVIE", listOf(row(3, "C"), row(1, "A")), fetchedAt = 10)
        store.replace("SERIES", listOf(row(9, "S")), fetchedAt = 11)
        store.replace("MOVIE", listOf(row(2, "B"), row(3, "C"), row(1, "A")), fetchedAt = 20)

        val movies = store.cached("MOVIE")!!
        assertThat(movies.fetchedAt).isEqualTo(20)
        assertThat(movies.titles.map { it.tmdbId }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(movies.titles.map { it.rank }).containsExactly(0, 1, 2).inOrder()
        assertThat(store.cached("SERIES")!!.titles.single().title).isEqualTo("S")
    }

    @Test
    fun anEmptyListIsStillAFetch() = runBlocking<Unit> {
        store.replace("MOVIE", emptyList(), fetchedAt = 5)
        assertThat(store.cached("MOVIE")).isEqualTo(CachedTrending(5, emptyList()))
    }
}

package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.TitleGrouper
import app.playlistplayer.core.consolidation.VodItem
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.network.LogSink
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.tmdb.TmdbException
import app.playlistplayer.core.network.tmdb.TmdbMediaType
import app.playlistplayer.core.network.tmdb.TmdbTrendingItem
import app.playlistplayer.core.network.tmdb.TmdbTrendingSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrendingRepositoryTest {
    private val fakeKey = "FAKE_TMDB_KEY_123"
    private var now = 1_000_000L
    private val day = TrendingRepository.DEFAULT_MAX_AGE_MS
    private val logged = mutableListOf<String>()
    private val logger = RedactingLogger(LogSink { _, _, message, throwable -> logged += message + (throwable?.stackTraceToString() ?: "") })

    private class FakeTmdb : TmdbTrendingSource {
        val calls = mutableListOf<Pair<TmdbMediaType, Int>>()
        var failWith: Exception? = null
        var items = listOf(TmdbTrendingItem(1, "One", null, 2024), TmdbTrendingItem(2, "Two", null, 2023))
        override suspend fun weeklyTrending(type: TmdbMediaType, pages: Int): List<TmdbTrendingItem> {
            calls += type to pages
            failWith?.let { throw it }
            return items
        }
    }

    private val tmdb = FakeTmdb()
    private val cache = InMemoryTrendingCache()
    private fun repository(source: TmdbTrendingSource? = tmdb) =
        TrendingRepository(source, cache, clock = { now }, logger = logger, secrets = listOf(fakeKey))

    @Test
    fun `no key means no row and no request`() = runBlocking<Unit> {
        assertThat(repository(source = null).trending(VodKind.MOVIE)).isNull()
        assertThat(tmdb.calls).isEmpty()
    }

    @Test
    fun `fetches three pages per kind and does not refetch within 24 hours`() = runBlocking<Unit> {
        val repo = repository()
        assertThat(repo.trending(VodKind.MOVIE)!!.map { it.tmdbId }).containsExactly(1L, 2L).inOrder()
        assertThat(tmdb.calls).containsExactly(TmdbMediaType.MOVIE to 3)

        now += day - 1
        tmdb.items = listOf(TmdbTrendingItem(3, "Three", null, 2025))
        assertThat(repo.trending(VodKind.MOVIE)!!.map { it.tmdbId }).containsExactly(1L, 2L).inOrder()
        // The cache survives a new repository (app restart).
        assertThat(repository().trending(VodKind.MOVIE)!!.map { it.tmdbId }).containsExactly(1L, 2L).inOrder()
        assertThat(tmdb.calls).hasSize(1)

        repo.trending(VodKind.SERIES)
        assertThat(tmdb.calls.last()).isEqualTo(TmdbMediaType.TV to 3)

        now += 2
        assertThat(repo.trending(VodKind.MOVIE)!!.map { it.tmdbId }).containsExactly(3L)
        assertThat(tmdb.calls.count { it.first == TmdbMediaType.MOVIE }).isEqualTo(2)
    }

    @Test
    fun `a failed fetch hides the row, waits before retrying, and never logs the key`() = runBlocking<Unit> {
        val repo = repository()
        tmdb.failWith = TmdbException("HTTP 401 for https://api.example/3/trending/movie/week?api_key=$fakeKey", 401)
        assertThat(repo.trending(VodKind.MOVIE)).isNull()
        assertThat(repo.trending(VodKind.MOVIE)).isNull()
        assertThat(tmdb.calls).hasSize(1)

        tmdb.failWith = IllegalStateException("boom $fakeKey")
        now += TrendingRepository.DEFAULT_RETRY_AFTER_FAILURE_MS
        assertThat(repo.trending(VodKind.MOVIE)).isNull()
        assertThat(tmdb.calls).hasSize(2)

        assertThat(logged).hasSize(2)
        logged.forEach { assertThat(it).doesNotContain(fakeKey) }

        tmdb.failWith = null
        now += TrendingRepository.DEFAULT_RETRY_AFTER_FAILURE_MS
        assertThat(repo.trending(VodKind.MOVIE)).isNotNull()
    }

    @Test
    fun `a failed refresh of a stale list hides the row`() = runBlocking<Unit> {
        val repo = repository()
        repo.trending(VodKind.MOVIE)
        now += day
        tmdb.failWith = TmdbException("network error")
        assertThat(repo.trending(VodKind.MOVIE)).isNull()
    }

    @Test
    fun `tmdb matches with conflicting years are rejected`() {
        val items = listOf(
            VodItem("1", "1", VodKind.MOVIE, "EN - Arsenal (2017)", tmdb = "470044", providerOrder = 0),
            VodItem("2", "1", VodKind.MOVIE, "SOC - Arsenal - Liverpool 04.02.2024", tmdb = "470044", providerOrder = 1),
        )
        val titles = TitleGrouper.group(items, emptyList()).map { CatalogTitle(it, emptyMap()) }
        val matched = TrendingMatcher.match(listOf(TrendingEntry(470044, "Arsenal", null, 2017)), titles, VodKind.MOVIE)
        assertThat(matched.map { it.title.title }).containsExactly("Arsenal")
        assertThat(matched.single().title.versions.map { it.item.id }).containsExactly("1")
    }
}

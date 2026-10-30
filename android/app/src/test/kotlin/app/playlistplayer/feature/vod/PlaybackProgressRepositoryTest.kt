package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.VodKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackProgressRepositoryTest {

    private var now = 0L
    private val store = InMemoryPlaybackProgressStore()
    private val repo = PlaybackProgressRepository(store) { now }
    private val movie = ProgressKey("1|movie|tmdb:1|year:2021")

    private suspend fun save(key: ProgressKey, position: Long, duration: Long = 100_000) {
        repo.save(key, 1, VodKind.MOVIE, "Title", "movie:1", position, duration)
        now += 1
    }

    @Test
    fun `progress at 3 percent does not offer resume`() = runTest {
        save(movie, 3_000)
        assertThat(repo.resumePosition(movie)).isNull()
        assertThat(repo.isWatched(movie)).isFalse()
    }

    @Test
    fun `progress at 96 percent is watched and does not offer resume`() = runTest {
        save(movie, 96_000)
        assertThat(repo.resumePosition(movie)).isNull()
        assertThat(repo.isWatched(movie)).isTrue()
    }

    @Test
    fun `resume between 5 and 95 percent, watched from 90 percent`() = runTest {
        save(movie, 50_000)
        assertThat(repo.resumePosition(movie)).isEqualTo(50_000)
        assertThat(PlaybackProgressRepository.isResumable(5_000, 100_000)).isFalse()
        assertThat(PlaybackProgressRepository.isResumable(5_001, 100_000)).isTrue()
        assertThat(PlaybackProgressRepository.isResumable(95_000, 100_000)).isFalse()
        assertThat(PlaybackProgressRepository.isWatched(89_999, 100_000)).isFalse()
        assertThat(PlaybackProgressRepository.isWatched(90_000, 100_000)).isTrue()
        assertThat(PlaybackProgressRepository.isWatched(0, 0)).isFalse()
    }

    @Test
    fun `tracker saves every 10 seconds and on stop`() = runTest {
        val tracker = repo.tracker(movie, 1, VodKind.MOVIE, "Title", "movie:1")
        assertThat(tracker.onPosition(1_000, 100_000)).isFalse() // starts the interval
        now += 9_999
        assertThat(tracker.onPosition(10_000, 100_000)).isFalse()
        assertThat(store.get(movie)).isNull()
        now += 1
        assertThat(tracker.onPosition(11_000, 100_000)).isTrue()
        assertThat(store.get(movie)!!.positionMs).isEqualTo(11_000)
        now += 3_000
        assertThat(tracker.onPosition(14_000, 100_000)).isFalse()
        tracker.versionId = "movie:2"
        tracker.onStop(14_500, 100_000)
        assertThat(store.get(movie)!!.positionMs).isEqualTo(14_500)
        assertThat(store.get(movie)!!.versionId).isEqualTo("movie:2")
    }

    @Test
    fun `continue watching lists resumable unwatched items, newest first, one per title`() = runTest {
        val a = ProgressKey("a")
        val b = ProgressKey("b")
        val c = ProgressKey("c")
        val s1 = ProgressKey("series", 1, 1)
        val s2 = ProgressKey("series", 1, 2)
        save(a, 30_000)
        save(b, 2_000) // too early
        save(c, 97_000) // watched
        save(s1, 20_000)
        save(s2, 40_000)
        save(a, 35_000) // a updated last
        val list = repo.continueWatching()
        assertThat(list.map { it.key }).containsExactly(a, s2).inOrder()
    }

    @Test
    fun `invalid durations are not saved`() = runTest {
        save(movie, 1_000, duration = 0)
        assertThat(store.get(movie)).isNull()
    }
}

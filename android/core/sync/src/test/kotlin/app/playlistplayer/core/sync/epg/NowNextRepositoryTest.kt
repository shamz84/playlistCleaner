package app.playlistplayer.core.sync.epg

import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.NOW
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class NowNextRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: EpgTestEnv

    @Before
    fun setUp() {
        env = EpgTestEnv(temp.root)
    }

    @After
    fun tearDown() = env.close()

    private fun repository() = NowNextRepository(
        env.h.store, env.catalog, env.epg, { env.h.secureStore.account(it) }, env.xtream, clock = { env.h.now },
    )

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun roomProgrammesComeFirst() = runBlocking<Unit> {
        val id = env.playlist()
        env.syncer().sync(id, backfill = false)
        val nowNext = repository().nowNext(id, 1)
        assertThat(nowNext.source).isEqualTo(NowNext.Source.ROOM)
        val current = nowNext.now!!
        assertThat(current.startMillis).isAtMost(NOW)
        assertThat(current.stopMillis).isGreaterThan(NOW)
        assertThat(current.programmeId).isNotNull()
        assertThat(env.epg.programme(current.programmeId!!)!!.title).isEqualTo(current.title)
        assertThat(nowNext.next!!.startMillis).isAtLeast(current.stopMillis)
        assertThat(env.requests.filter { it.startsWith("get_short_epg") }).isEmpty()
    }

    @Test
    fun channelsWithoutProgrammesUseShortEpgInServerZoneAndCacheIt() = runBlocking<Unit> {
        val id = env.playlist()
        val repo = repository()
        // No import yet: falls back to get_short_epg (strings are Europe/Amsterdam wall time).
        val nowNext = repo.nowNext(id, 0)
        assertThat(nowNext.source).isEqualTo(NowNext.Source.SHORT_EPG)
        assertThat(nowNext.now!!.title).isEqualTo("Not Going Out")
        assertThat(nowNext.now!!.startMillis).isEqualTo(utc(2026, 10, 29, 21, 30))
        assertThat(nowNext.now!!.programmeId).isNull()
        assertThat(nowNext.next!!.title).isEqualTo("BBC News and Weather")

        repo.nowNext(id, 0)
        assertThat(env.requests.filter { it.startsWith("get_short_epg") }).containsExactly("get_short_epg:497001")

        // After the cached "now" programme ends, the cache is refreshed.
        env.h.now = utc(2026, 10, 29, 22, 1)
        repo.nowNext(id, 0)
        assertThat(env.requests.filter { it.startsWith("get_short_epg") }).hasSize(2)
    }

    @Test
    fun nonXtreamPlaylistsWithoutGuideReturnNone() = runBlocking<Unit> {
        val id = env.playlist(xtream = false)
        assertThat(repository().nowNext(id, 4)).isEqualTo(NowNext.NONE)
        assertThat(env.requests).isEmpty()
    }
}

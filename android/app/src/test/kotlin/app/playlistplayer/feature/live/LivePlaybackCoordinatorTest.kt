package app.playlistplayer.feature.live

import app.playlistplayer.core.consolidation.DeviceCapabilities
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VersionPreferences
import app.playlistplayer.core.player.url.LiveFormatPreference
import app.playlistplayer.feature.settings.PlaybackPreferences
import app.playlistplayer.feature.vod.VersionPreferencesProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LivePlaybackCoordinatorTest {

    private val source = FakeLiveChannelSource()
    private val header = source.add(LiveChannel(1, 9, 2, "##### UK ENTERTAINMENT #####", isHeader = true))
    private val hd = source.add(LiveChannel(1, 10, 2, "UK: BBC One HD", streamId = 101, tvgId = "bbcone.uk", archiveDays = 3))
    private val two = source.add(LiveChannel(1, 11, 2, "UK: BBC Two HD", streamId = 102, tvgId = "bbctwo.uk"))
    private val uhd = source.add(LiveChannel(1, 20, 5, "UK: BBC One 4K", streamId = 201, tvgId = "bbcone.uk"))
    private val fhd = source.add(LiveChannel(1, 21, 6, "UK: BBC One FHD", streamId = 202, tvgId = "bbcone.uk"))
    private val sd = source.add(LiveChannel(1, 22, 7, "UK: BBC One SD", streamId = 203, tvgId = "bbcone.uk"))
    private val backup = source.add(LiveChannel(1, 23, 7, "UK: BBC One HD (Backup)", streamId = 204, tvgId = "bbcone.uk"))
    private val hd720 = source.add(LiveChannel(1, 24, 7, "UK: BBC One 720p", streamId = 205, tvgId = "bbcone.uk"))
    private val fhd25 = source.add(LiveChannel(1, 25, 7, "UK: BBC One 1080p25", streamId = 206, tvgId = "bbcone.uk"))
    private val otherPlaylist = source.add(LiveChannel(2, 20, 5, "UK: BBC One 4K", streamId = 901, tvgId = "bbcone.uk"))

    private val prefs = FakePreferences()
    private val lastChannels = InMemoryLastChannelStore()
    private var versionPreferences = VersionPreferencesProvider { VersionPreferences() }

    private fun coordinator(
        accountProvider: app.playlistplayer.feature.vod.AccountProvider = accounts,
        status: LiveAccountStatusSource = amsterdam,
    ) = LivePlaybackCoordinator(source, accountProvider, status, prefs, { versionPreferences.current() }, lastChannels)

    private suspend fun LivePlaybackCoordinator.ready(channel: LiveChannel) = (tune(channel) as LivePlanResult.Ready).plan

    @Test
    fun `builds at most five failover sources in comparator order from the same playlist only`() = runTest {
        val plan = coordinator().ready(hd)
        assertThat(plan.sources.map { it.id }).containsExactly(
            "live:1:10", // selected
            "live:1:20", // 4K
            "live:1:25", // 1080p25 (FHD, frame rate tie-break)
            "live:1:21", // FHD
            "live:1:24", // 720p (HD)
            "live:1:23", // HD backup ranks below the non-backup HD
        ).inOrder()
        assertThat(plan.sources.drop(1)).hasSize(5)
        assertThat(plan.sources.none { it.url.contains("/901.") }).isTrue()
        assertThat(plan.versions.map { it.channel.playlistId }.distinct()).containsExactly(1L)
        assertThat(plan.sources.all { it.isLive && it.accountKey == "1" }).isTrue()
        assertThat(plan.sources.first().versionTier).isEqualTo(QualityTier.HD)
        // The overlay lists the other versions (the tuned channel is marked separately).
        assertThat(plan.otherVersions.map { it.channel }).containsExactly(uhd, fhd25, fhd, hd720, backup).inOrder()
    }

    @Test
    fun `device capability cap demotes 4K below versions that fit`() = runTest {
        versionPreferences = VersionPreferencesProvider { VersionPreferences(device = DeviceCapabilities(maxVideoHeight = 1080)) }
        val plan = coordinator().ready(hd)
        // 4K no longer fits, so it ranks last and falls outside the top five.
        assertThat(plan.sources.map { it.id })
            .containsExactly("live:1:10", "live:1:25", "live:1:21", "live:1:24", "live:1:23", "live:1:22").inOrder()
        assertThat(plan.versions.map { it.channel }).doesNotContain(uhd)
    }

    @Test
    fun `Xtream URL uses the live format preference only when the account allows HLS`() = runTest {
        assertThat(coordinator().ready(hd).sources.first().url).isEqualTo("http://provider.invalid:8080/live/user-secret/pass-secret/101.ts")

        prefs.playback = PlaybackPreferences(liveFormat = LiveFormatPreference.HLS)
        assertThat(coordinator().ready(hd).sources.first().url).endsWith("/101.m3u8")

        val tsOnly = LiveAccountStatusSource { LiveAccountStatus(allowedFormats = listOf("ts")) }
        assertThat(coordinator(status = tsOnly).ready(hd).sources.first().url).endsWith("/101.ts")
    }

    @Test
    fun `non-Xtream entries play their vault URL`() = runTest {
        val plain = source.add(LiveChannel(3, 1, 1, "Local News", streamId = null))
        source.vault[3L to 1] = "https://example.invalid/hls/local.m3u8"
        val plan = coordinator(accountProvider = noAccount).ready(plain)
        assertThat(plan.sources.single().url).isEqualTo("https://example.invalid/hls/local.m3u8")
    }

    @Test
    fun `missing account, headers and unknown URLs are reported`() = runTest {
        assertThat(coordinator(accountProvider = noAccount).tune(hd)).isEqualTo(LivePlanResult.NoAccount)
        assertThat(coordinator().tune(header)).isEqualTo(LivePlanResult.NotPlayable)
        val noUrl = source.add(LiveChannel(3, 2, 1, "No URL"))
        assertThat(coordinator().tune(noUrl)).isEqualTo(LivePlanResult.NotPlayable)
    }

    @Test
    fun `remembers the last channel per playlist`() = runTest {
        val c = coordinator()
        c.ready(hd)
        c.ready(two)
        assertThat(c.lastChannel(1)).isEqualTo(LastChannel(ordinal = 11, groupOrdinal = 2))
        assertThat(c.lastChannel(2)).isNull()
    }

    @Test
    fun `switching to an other version plays it first and keeps the tuned channel as failover`() = runTest {
        val c = coordinator()
        val plan = c.ready(hd)
        val switched = (c.switchTo(plan, plan.versions.first { it.channel == uhd }) as LivePlanResult.Ready).plan
        assertThat(switched.sources.first().id).isEqualTo("live:1:20")
        assertThat(switched.sources.map { it.id }).contains("live:1:10")
        assertThat(switched.sources.drop(1)).hasSize(5)
        assertThat(switched.group).isEqualTo(hd)
        assertThat(switched.otherVersions.map { it.channel }).doesNotContain(uhd)
        assertThat(c.lastChannel(1)).isEqualTo(LastChannel(10, 2))
    }

    @Test
    fun `the other-versions index is built once per playlist until invalidated`() = runTest {
        val c = coordinator()
        c.ready(hd)
        c.ready(fhd)
        assertThat(source.playlistLoads).isEqualTo(1)
        c.invalidate(1)
        c.ready(hd)
        assertThat(source.playlistLoads).isEqualTo(2)
        assertThat(c.otherVersions(sd)).doesNotContain(otherPlaylist)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent guide and warm requests share one index build`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val slow = object : LiveChannelSource by source {
            override suspend fun playlistChannels(playlistId: Long): List<LiveChannel> {
                loads++
                gate.await()
                return source.playlistChannels(playlistId)
            }
        }
        val coordinator = LivePlaybackCoordinator(slow, accounts, amsterdam, prefs, versionPreferences, lastChannels)
        val first = async { coordinator.otherVersions(hd) }
        val second = async { coordinator.otherVersions(fhd) }
        runCurrent()
        assertThat(loads).isEqualTo(1)
        gate.complete(Unit)
        assertThat(first.await()).contains(uhd)
        assertThat(second.await()).contains(hd)
        assertThat(loads).isEqualTo(1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `invalidation during index build prevents stale results being cached`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val slow = object : LiveChannelSource by source {
            override suspend fun playlistChannels(playlistId: Long): List<LiveChannel> {
                val snapshot = source.playlistChannels(playlistId)
                loads++
                if (loads == 1) gate.await()
                return snapshot
            }
        }
        val coordinator = LivePlaybackCoordinator(slow, accounts, amsterdam, prefs, versionPreferences, lastChannels)
        val pending = async { coordinator.otherVersions(hd) }
        runCurrent()
        source.all.remove(uhd)
        coordinator.invalidate(1)
        gate.complete(Unit)
        assertThat(pending.await()).doesNotContain(uhd)
        assertThat(loads).isEqualTo(2)
        assertThat(coordinator.otherVersions(hd)).doesNotContain(uhd)
    }
}

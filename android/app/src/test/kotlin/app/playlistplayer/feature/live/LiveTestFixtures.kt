package app.playlistplayer.feature.live

import app.playlistplayer.core.database.entity.ChannelProgramme
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.ProgrammeSource
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.feature.settings.PlaybackPreferences
import app.playlistplayer.feature.vod.AccountProvider
import app.playlistplayer.feature.vod.VersionPreferencesProvider
import app.playlistplayer.feature.vod.testAccount
import app.playlistplayer.core.consolidation.VersionPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE

/** In-memory channels across playlists; group lookups never cross playlists. */
class FakeLiveChannelSource(val all: MutableList<LiveChannel> = mutableListOf()) : LiveChannelSource {
    val vault = HashMap<Pair<Long, Int>, String>()
    var playlistLoads = 0

    fun add(channel: LiveChannel): LiveChannel = channel.also { all += it }

    override suspend fun channel(playlistId: Long, ordinal: Int): LiveChannel? =
        all.firstOrNull { it.playlistId == playlistId && it.ordinal == ordinal }

    override suspend fun groupChannels(playlistId: Long, groupOrdinal: Int): List<LiveChannel> =
        all.filter { it.playlistId == playlistId && it.groupOrdinal == groupOrdinal }.sortedBy { it.ordinal }

    override suspend fun playlistChannels(playlistId: Long): List<LiveChannel> {
        playlistLoads++
        // Deliberately returns every playlist's entries: the coordinator must filter.
        return all.toList()
    }

    override suspend fun vaultUrl(playlistId: Long, ordinal: Int): String? = vault[playlistId to ordinal]
}

class FakePlayerDriver : PlayerDriver {
    val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = mutableState
    override var positionMs: Long = 0
    override var isPlaying: Boolean = false

    val plays = mutableListOf<Pair<List<PlayableSource>, Long?>>()
    val seeks = mutableListOf<Long>()
    var stops = 0
    var retries = 0
    var paused = false

    val lastSources: List<PlayableSource> get() = plays.last().first

    override fun play(sources: List<PlayableSource>, startPositionMs: Long?) {
        plays += sources to startPositionMs
        positionMs = startPositionMs ?: 0
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun retry() { retries++ }
    override fun stop() { stops++ }
}

class FakePreferences(var playback: PlaybackPreferences = PlaybackPreferences()) : PlaybackPreferencesProvider {
    override suspend fun current(): PlaybackPreferences = playback
}

val accounts = AccountProvider { testAccount }
val noAccount = AccountProvider { null }
val amsterdam = LiveAccountStatusSource { LiveAccountStatus(allowedFormats = listOf("m3u8", "ts", "rtmp"), serverTimezone = "Europe/Amsterdam") }
val versionPrefs = VersionPreferencesProvider { VersionPreferences() }

fun programme(
    id: Long,
    channelKey: String,
    start: Long,
    stop: Long,
    title: String? = "Programme $id",
    description: String? = null,
) = ProgrammeEntity(
    id = id,
    playlistId = 1,
    channelKey = channelKey,
    startMillis = start,
    stopMillis = stop,
    title = title,
    subTitle = null,
    description = description,
    categories = null,
    episodeNum = null,
    source = ProgrammeSource.XMLTV.name,
)

class FakeEpgGridSource : EpgGridSource {
    val rows = mutableListOf<ChannelProgramme>()
    val calls = mutableListOf<Pair<Long, Long>>()

    fun add(channelOrdinal: Int, programme: ProgrammeEntity) {
        rows += ChannelProgramme(channelOrdinal, programme)
    }

    override suspend fun gridForGroup(playlistId: Long, groupOrdinal: Int, fromMillis: Long, toMillis: Long): List<ChannelProgramme> {
        calls += fromMillis to toMillis
        return rows.filter { it.programme.stopMillis > fromMillis && it.programme.startMillis < toMillis }
    }
}

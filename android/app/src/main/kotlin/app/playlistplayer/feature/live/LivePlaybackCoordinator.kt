package app.playlistplayer.feature.live

import app.playlistplayer.core.consolidation.AlternativeFinder
import app.playlistplayer.core.consolidation.LiveEntry
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VersionComparator
import app.playlistplayer.core.consolidation.VersionPreferences
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.core.player.url.StreamAccount
import app.playlistplayer.core.player.url.StreamUrlBuilder
import app.playlistplayer.feature.vod.AccountProvider
import app.playlistplayer.feature.vod.VersionPreferencesProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One "other version" row for the player overlay. Carries no URL. */
data class OtherVersion(
    val channel: LiveChannel,
    val label: String,
    val quality: QualityTier,
    val hasArchive: Boolean,
) {
    /** Stable id, equal to the matching [PlayableSource.id]. */
    val id: String get() = LivePlaybackCoordinator.sourceId(channel)
}

/**
 * What to play for a channel: [sources] (selected version first, then up to five other
 * versions in comparator order for failover) and the overlay's [otherVersions]. Built on demand
 * at play time; [sources] carry credentials in their URLs and must not be kept in UI state.
 *
 * @property group the channel the user tuned to; other versions are always relative to it, so
 *   switching versions does not change the list.
 * @property versions [group] plus its other versions, in comparator order.
 */
data class LivePlan(
    val group: LiveChannel,
    val selected: LiveChannel,
    val sources: List<PlayableSource>,
    val versions: List<OtherVersion>,
) {
    /** The versions other than [selected], as the overlay lists them. */
    val otherVersions: List<OtherVersion> get() = versions.filter { it.channel != selected }

    override fun toString(): String = "LivePlan(selected=${selected.ordinal}, sources=$sources)"
}

sealed interface LivePlanResult {
    data class Ready(val plan: LivePlan) : LivePlanResult

    /** The channel cannot be played: a header, or no stream id and no vault URL. */
    data object NotPlayable : LivePlanResult

    /** An Xtream entry whose playlist has no stored account. */
    data object NoAccount : LivePlanResult
}

/** Other versions of a live entry (same playlist, any group), best first. */
fun interface OtherVersionsResolver {
    suspend fun otherVersions(channel: LiveChannel): List<LiveChannel>
}

/**
 * Builds live playback for a selected playlist entry (R9, R11):
 * - primary source: Xtream `StreamUrlBuilder.live` with the user's format (TS/HLS, HLS only when
 *   the account allows `m3u8`), or the entry's vault URL for non-Xtream playlists;
 * - failover: up to [AlternativeFinder.MAX_ALTERNATIVES] other versions from the **same
 *   playlist** (any group), ordered by [VersionComparator] with the device capability cap;
 * - [switchTo] makes a chosen other version primary;
 * - the last channel per playlist is remembered in [lastChannels].
 *
 * The other-versions index is built once per playlist; call [invalidate] when a sync activates
 * a new generation.
 */
class LivePlaybackCoordinator(
    private val channels: LiveChannelSource,
    private val accounts: AccountProvider,
    private val accountStatus: LiveAccountStatusSource,
    private val playbackPreferences: PlaybackPreferencesProvider,
    private val versionPreferences: VersionPreferencesProvider,
    private val lastChannels: LastChannelStore,
) : OtherVersionsResolver {

    private class Index(val finder: AlternativeFinder, val byOrdinal: Map<Int, LiveChannel>)

    private val mutex = Mutex()
    private val indexes = HashMap<Long, Index>()

    /** Drops the cached other-versions index (all playlists, or one). */
    suspend fun invalidate(playlistId: Long? = null) = mutex.withLock {
        if (playlistId == null) indexes.clear() else indexes.remove(playlistId)
    }

    suspend fun lastChannel(playlistId: Long): LastChannel? = lastChannels.lastChannel(playlistId)

    override suspend fun otherVersions(channel: LiveChannel): List<LiveChannel> =
        otherVersions(channel, versionPreferences.current())

    /** Plan for tuning to [channel]; records it as the playlist's last channel when playable. */
    suspend fun tune(channel: LiveChannel): LivePlanResult {
        val result = plan(channel, channel)
        if (result is LivePlanResult.Ready) {
            lastChannels.setLastChannel(channel.playlistId, LastChannel(channel.ordinal, channel.groupOrdinal))
        }
        return result
    }

    /**
     * Plan that plays [version] (one of [plan]'s versions) first, with the remaining versions
     * as failover in comparator order. The last channel stays the tuned one.
     */
    suspend fun switchTo(plan: LivePlan, version: OtherVersion): LivePlanResult {
        require(plan.versions.any { it.channel == version.channel }) { "Not a version of this channel" }
        return plan(plan.group, version.channel)
    }

    private suspend fun plan(group: LiveChannel, selected: LiveChannel): LivePlanResult {
        if (!selected.isPlayable) return LivePlanResult.NotPlayable
        val prefs = versionPreferences.current()
        val others = otherVersions(group, prefs)
        val index = index(group.playlistId)
        val versions = rank(listOf(group) + others, index, prefs)

        val builder = SourceBuilder(group.playlistId)
        val primary = when (val built = builder.build(selected, index)) {
            is Built.Source -> built.source
            Built.NoAccount -> return LivePlanResult.NoAccount
            Built.None -> return LivePlanResult.NotPlayable
        }
        val failover = ArrayList<PlayableSource>(AlternativeFinder.MAX_ALTERNATIVES)
        for (version in versions) {
            if (failover.size >= AlternativeFinder.MAX_ALTERNATIVES) break
            if (version.channel == selected) continue
            (builder.build(version.channel, index) as? Built.Source)?.let { failover += it.source }
        }
        return LivePlanResult.Ready(LivePlan(group, selected, listOf(primary) + failover, versions))
    }

    private suspend fun otherVersions(channel: LiveChannel, prefs: VersionPreferences): List<LiveChannel> {
        if (!channel.isPlayable) return emptyList()
        val index = index(channel.playlistId)
        return index.finder.alternativesFor(entryOf(channel), prefs, AlternativeFinder.MAX_ALTERNATIVES)
            .mapNotNull { index.byOrdinal[it.id.toInt()] }
            .filter { it.playlistId == channel.playlistId }
    }

    private fun rank(list: List<LiveChannel>, index: Index, prefs: VersionPreferences): List<OtherVersion> {
        val comparator = VersionComparator(prefs)
        return list.distinct()
            .map { it to index.finder.versionOf(entryOf(it)) }
            .sortedWith { a, b -> comparator.compare(a.second, b.second) }
            .map { (channel, version) ->
                OtherVersion(channel = channel, label = channel.name, quality = version.quality, hasArchive = channel.archiveDays > 0)
            }
    }

    private suspend fun index(playlistId: Long): Index {
        mutex.withLock { indexes[playlistId] }?.let { return it }
        val all = channels.playlistChannels(playlistId).filter { it.playlistId == playlistId }
        val built = Index(AlternativeFinder(all.map(::entryOf)), all.associateBy { it.ordinal })
        mutex.withLock { indexes[playlistId] = built }
        return built
    }

    private sealed interface Built {
        data class Source(val source: PlayableSource) : Built
        data object NoAccount : Built
        data object None : Built
    }

    /** Reads the account and settings once per plan; the account is dropped with the builder. */
    private inner class SourceBuilder(private val playlistId: Long) {
        private var accountLoaded = false
        private var account: StreamAccount? = null
        private var urlBuilder: StreamUrlBuilder? = null
        private var formatsLoaded = false
        private var allowedFormats: List<String> = emptyList()

        suspend fun build(channel: LiveChannel, index: Index): Built {
            if (!channel.isPlayable) return Built.None
            val quality = index.finder.versionOf(entryOf(channel)).quality
            val streamId = channel.streamId
            val url = if (streamId != null) {
                val builder = urlBuilder() ?: return vault(channel, quality) ?: Built.NoAccount
                builder.live(streamId.toString(), playbackPreferences.current().liveFormat, formats())
            } else {
                return vault(channel, quality) ?: Built.None
            }
            return Built.Source(source(channel, url, quality))
        }

        private suspend fun vault(channel: LiveChannel, quality: QualityTier): Built? =
            channels.vaultUrl(channel.playlistId, channel.ordinal)?.let { Built.Source(source(channel, it, quality)) }

        private fun source(channel: LiveChannel, url: String, quality: QualityTier) = PlayableSource(
            id = sourceId(channel),
            url = url,
            label = channel.name,
            isLive = true,
            versionTier = quality,
            accountKey = playlistId.toString(),
        )

        private suspend fun urlBuilder(): StreamUrlBuilder? {
            if (!accountLoaded) {
                accountLoaded = true
                account = accounts.streamAccount(playlistId)
                urlBuilder = account?.let(::StreamUrlBuilder)
            }
            return urlBuilder
        }

        private suspend fun formats(): List<String> {
            if (!formatsLoaded) {
                formatsLoaded = true
                allowedFormats = accountStatus.status(playlistId)?.allowedFormats.orEmpty()
            }
            return allowedFormats
        }
    }

    companion object {
        fun sourceId(channel: LiveChannel): String = "live:${channel.playlistId}:${channel.ordinal}"

        internal fun entryOf(channel: LiveChannel) = LiveEntry(
            id = channel.ordinal.toString(),
            playlistId = channel.playlistId.toString(),
            name = channel.name,
            tvgId = channel.tvgId,
            providerOrder = channel.providerOrder,
        )
    }
}

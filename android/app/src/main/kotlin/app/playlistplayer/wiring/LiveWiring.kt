package app.playlistplayer.wiring

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.entity.ChannelLiveInfo
import app.playlistplayer.core.model.Channel
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.xtream.AccountCheck
import app.playlistplayer.core.player.AccountStatus
import app.playlistplayer.core.player.AccountStatusChecker
import app.playlistplayer.core.player.url.StreamAccount
import app.playlistplayer.core.sync.StreamUrlVault
import app.playlistplayer.feature.live.LiveAccountStatus
import app.playlistplayer.feature.live.LiveAccountStatusSource
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.feature.live.LiveChannelSource
import app.playlistplayer.feature.search.ContentVisibility
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseSource
import app.playlistplayer.ui.common.live.numberChannels
import app.playlistplayer.core.database.PlaylistStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/** Archive window of a playlist entry: the catalog's `tv_archive` once synced, else the playlist's `catchup-days`. */
private fun Channel.archiveDays(info: ChannelLiveInfo?): Int = when {
    isHeader -> 0
    info != null -> info.effectiveArchiveDays
    else -> catchupDays
}

private fun Channel.toLive(info: ChannelLiveInfo?) = LiveChannel(
    playlistId = playlistId,
    ordinal = ordinal,
    groupOrdinal = groupOrdinal,
    name = name,
    streamId = streamId,
    tvgId = tvgId,
    archiveDays = archiveDays(info),
    isHeader = isHeader,
    logoUrl = logo,
)

/**
 * [LiveChannelSource] over Room: the active playlist generation ([PlaylistStore]) joined with the
 * catalog's live metadata ([CatalogStore.channelWithLiveInfo]); non-Xtream URLs come from the
 * encrypted [StreamUrlVault]. Entries of parental-hidden groups never become "other versions"
 * ([playlistChannels] drops them).
 */
class RoomLiveChannelSource(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
    private val vault: StreamUrlVault,
    private val visibility: () -> ContentVisibility,
) : LiveChannelSource {
    override suspend fun channel(playlistId: Long, ordinal: Int): LiveChannel? {
        val channel = playlistStore.channel(playlistId, ordinal) ?: return null
        return channel.toLive(catalogStore.channelWithLiveInfo(playlistId, ordinal))
    }

    override suspend fun groupChannels(playlistId: Long, groupOrdinal: Int): List<LiveChannel> {
        val channels = playlistStore.observeChannelsInGroup(playlistId, groupOrdinal).first()
        val info = catalogStore.channelsWithLiveInfoInGroup(playlistId, groupOrdinal).associateBy { it.ordinal }
        return channels.map { it.toLive(info[it.ordinal]) }
    }

    override suspend fun playlistChannels(playlistId: Long): List<LiveChannel> {
        val rules = visibility()
        val info = catalogStore.channelsWithLiveInfo(playlistId).associateBy { it.ordinal }
        return playlistStore.channels(playlistId)
            .filterNot { rules.isLiveGroupHidden(playlistId, it.groupOrdinal, it.groupTitle) }
            .map { it.toLive(info[it.ordinal]) }
    }

    override suspend fun vaultUrl(playlistId: Long, ordinal: Int): String? = vault.streamUrl(playlistId, ordinal)
}

/**
 * [LiveBrowseSource] over Room: playlists in registry order (`createdAt`), groups in file order
 * (R4, R25), parental-hidden groups removed (R20); channels numbered with [numberChannels].
 * Re-emits on generation flips (Room invalidation) and on [ContentVisibility.changes].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomLiveBrowseSource(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
    private val visibility: () -> ContentVisibility,
) : LiveBrowseSource {
    private fun changes(): Flow<Unit> = visibility().changes.onStart { emit(Unit) }

    override fun sections(): Flow<List<PlaylistSection>> =
        playlistStore.observePlaylists()
            .flatMapLatest { playlists ->
                if (playlists.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(playlists.map { p -> playlistStore.observeGroups(p.id).map { groups -> p to groups } }) { it.toList() }
                }
            }
            .combine(changes()) { list, _ ->
                val rules = visibility()
                list.map { (playlist, groups) ->
                    PlaylistSection(
                        playlistId = playlist.id,
                        name = playlist.name,
                        groups = groups
                            .filterNot { rules.isLiveGroupHidden(playlist.id, it.ordinal, it.name) }
                            .map { GroupItem(playlist.id, it.ordinal, it.name, it.channelCount) },
                    )
                }
            }

    override fun channels(group: GroupKey): Flow<List<ChannelRow>> =
        combine(
            playlistStore.observeChannelsInGroup(group.playlistId, group.groupOrdinal),
            catalogStore.observeState(group.playlistId).map { it?.activeGeneration },
            changes(),
        ) { channels, _, _ -> channels }
            .mapLatest { channels ->
                val groupName = channels.firstOrNull()?.groupTitle ?: return@mapLatest emptyList()
                // A group locked while open shows nothing (it also leaves the group rail).
                if (visibility().isLiveGroupHidden(group.playlistId, group.groupOrdinal, groupName)) return@mapLatest emptyList()
                val info = catalogStore.channelsWithLiveInfoInGroup(group.playlistId, group.groupOrdinal).associateBy { it.ordinal }
                numberChannels(
                    channels.map {
                        ChannelRow(
                            playlistId = it.playlistId,
                            ordinal = it.ordinal,
                            number = null,
                            name = it.name,
                            logoUrl = it.logo,
                            hasCatchup = it.archiveDays(info[it.ordinal]) > 0,
                            isHeader = it.isHeader,
                        )
                    },
                )
            }
}

/** `AccountStatusEntity` facts the live player needs (allowed formats, server timezone). */
class CatalogLiveAccountStatusSource(private val catalogStore: CatalogStore) : LiveAccountStatusSource {
    override suspend fun status(playlistId: Long): LiveAccountStatus? =
        catalogStore.accountStatus(playlistId)?.let { LiveAccountStatus(it.allowedFormatList, it.serverTimezone) }
}

/** The stored Xtream account as the URL builders take it. Never log the result. */
fun XtreamAccount.toStreamAccount(): StreamAccount = StreamAccount(scheme, host, port, username, password)

/**
 * The player's [AccountStatusChecker] over the network one: [app.playlistplayer.core.player.PlayableSource.accountKey]
 * is the playlist id (every live, catch-up and VOD source sets it).
 */
class PlaylistAccountStatusChecker(
    private val network: app.playlistplayer.core.network.xtream.AccountStatusChecker,
) : AccountStatusChecker {
    override suspend fun check(source: app.playlistplayer.core.player.PlayableSource): AccountStatus {
        val playlistId = source.accountKey?.toLongOrNull() ?: return AccountStatus.Unknown
        return when (val check = network.check(playlistId)) {
            is AccountCheck.Active -> AccountStatus.Active(check.activeConnections, check.maxConnections)
            AccountCheck.Expired -> AccountStatus.Expired
            AccountCheck.Unknown -> AccountStatus.Unknown
        }
    }
}

/** `EpgStore::gridForGroup` for the guide. */
fun EpgStore.gridSource(): app.playlistplayer.feature.live.EpgGridSource =
    app.playlistplayer.feature.live.EpgGridSource { playlistId, groupOrdinal, from, to -> gridForGroup(playlistId, groupOrdinal, from, to) }

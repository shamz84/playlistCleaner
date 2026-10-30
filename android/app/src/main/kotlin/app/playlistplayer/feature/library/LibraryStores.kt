package app.playlistplayer.feature.library

import app.playlistplayer.core.consolidation.MergeOverride
import app.playlistplayer.core.consolidation.SplitOverride
import app.playlistplayer.core.consolidation.TitleOverride
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.UserDataStore
import app.playlistplayer.core.database.entity.PlaybackProgressEntity
import app.playlistplayer.core.database.entity.ResolvedChannel
import app.playlistplayer.core.database.entity.StreamKeys
import app.playlistplayer.core.database.entity.TitleOverrideEntity
import app.playlistplayer.core.database.entity.TitlePickEntity
import app.playlistplayer.feature.live.LastChannel
import app.playlistplayer.feature.live.LastChannelStore
import app.playlistplayer.feature.vod.FavouriteKind
import app.playlistplayer.feature.vod.FavouriteRef
import app.playlistplayer.feature.vod.FavouritesStore
import app.playlistplayer.feature.vod.PlaybackProgress
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.PlaybackProgressStore
import app.playlistplayer.feature.vod.ProgressKey
import app.playlistplayer.feature.vod.TitlePickStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

// Room-backed implementations of the stores the live and VOD features declared as interfaces
// (`VodStores.kt`, `LiveStores.kt`), plus recent channels and title overrides (U11).

/** Merged title keys start with `<playlistId>|<kind>|` (`TitleGrouper.scopePrefix`). */
object TitleKeys {
    fun playlistIdOf(titleKey: String): Long = titleKey.substringBefore('|').toLongOrNull() ?: UNKNOWN_PLAYLIST

    /** Rows whose key carries no playlist id (never produced by `TitleGrouper`). */
    const val UNKNOWN_PLAYLIST: Long = -1
}

/** A saved channel (favourite / recent) found in the playlist's active generation. */
data class SavedChannel(
    val playlistId: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val groupTitle: String,
    val name: String,
    val streamId: Long?,
    val logo: String?,
    /** [StreamKeys] key the row is stored under. */
    val streamKey: String,
    /** When it was favourited / last watched. */
    val at: Long,
)

internal fun ResolvedChannel.toSaved(): SavedChannel =
    SavedChannel(playlistId, ordinal, groupOrdinal, groupTitle, name, streamId, logo, itemKey, at)

/** Favourites shared by live and VOD. Channels: `playlistId + StreamKeys`; titles: `VodTitle.key`. */
class RoomFavouritesStore(
    private val store: UserDataStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : FavouritesStore {
    override fun observe(): Flow<Set<FavouriteRef>> =
        store.observeFavourites().map { rows ->
            rows.mapNotNullTo(LinkedHashSet()) { row ->
                runCatching { FavouriteKind.valueOf(row.kind) }.getOrNull()?.let { FavouriteRef(it, row.playlistId, row.itemKey) }
            }
        }

    override suspend fun isFavourite(ref: FavouriteRef): Boolean = store.isFavourite(ref.kind.name, ref.playlistId, ref.key)

    override suspend fun setFavourite(ref: FavouriteRef, favourite: Boolean) =
        store.setFavourite(ref.kind.name, ref.playlistId, ref.key, favourite, clock())
}

class RoomPlaybackProgressStore(private val store: UserDataStore) : PlaybackProgressStore {
    override suspend fun get(key: ProgressKey): PlaybackProgress? = store.progress(key.titleKey, key.season, key.episode)?.toModel()

    override suspend fun put(progress: PlaybackProgress) = store.putProgress(
        PlaybackProgressEntity(
            id = "",
            titleKey = progress.key.titleKey,
            season = progress.key.season,
            episode = progress.key.episode,
            playlistId = progress.playlistId,
            kind = progress.kind.name,
            label = progress.label,
            versionId = progress.versionId,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            updatedAt = progress.updatedAt,
            watched = PlaybackProgressRepository.isWatched(progress.positionMs, progress.durationMs),
        ),
    )

    override suspend fun remove(key: ProgressKey) = store.removeProgress(key.titleKey, key.season, key.episode)

    override suspend fun recent(limit: Int): List<PlaybackProgress> = store.recentProgress(limit).mapNotNull { it.toModel() }

    private fun PlaybackProgressEntity.toModel(): PlaybackProgress? {
        val vodKind = runCatching { VodKind.valueOf(kind) }.getOrNull() ?: return null
        return PlaybackProgress(ProgressKey(titleKey, season, episode), playlistId, vodKind, label, versionId, positionMs, durationMs, updatedAt)
    }
}

class RoomTitlePickStore(
    private val store: UserDataStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : TitlePickStore {
    override suspend fun pick(titleKey: String): String? = store.pick(titleKey)?.versionId

    override suspend fun setPick(titleKey: String, versionId: String) =
        store.setPick(TitlePickEntity(titleKey, TitleKeys.playlistIdOf(titleKey), versionId, clock()))

    override suspend fun clearPick(titleKey: String) = store.clearPick(titleKey)
}

/** Recently watched channels (newest first, at most 30 kept), resolved against the active generation. */
interface RecentChannelsStore {
    fun observe(): Flow<List<SavedChannel>>
    suspend fun record(playlistId: Long, ordinal: Int)
}

class RoomRecentChannelsStore(
    private val store: UserDataStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentChannelsStore {
    override fun observe(): Flow<List<SavedChannel>> = store.observeRecentChannels().map { rows -> rows.map { it.toSaved() } }

    override suspend fun record(playlistId: Long, ordinal: Int) {
        val channel = store.activeChannel(playlistId, ordinal) ?: return
        if (channel.isHeader) return
        store.recordRecent(playlistId, StreamKeys.of(channel.streamId, ordinal), clock())
    }
}

/**
 * The last channel per playlist, re-found by stream id after a re-sync (falls back to the
 * ordinal for entries without one). Setting it also records a recent channel when [recents] is
 * given: tuning a channel is exactly the "watched" event (`LivePlaybackCoordinator.tune`).
 */
class RoomLastChannelStore(
    private val store: UserDataStore,
    private val recents: RecentChannelsStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : LastChannelStore {
    override suspend fun lastChannel(playlistId: Long): LastChannel? =
        store.resolveLastChannel(playlistId)?.let { LastChannel(it.ordinal, it.groupOrdinal) }

    override suspend fun setLastChannel(playlistId: Long, channel: LastChannel) {
        val row = store.activeChannel(playlistId, channel.ordinal)
        store.setLastChannel(playlistId, channel.ordinal, channel.groupOrdinal, StreamKeys.of(row?.streamId, channel.ordinal), clock())
        recents?.record(playlistId, channel.ordinal)
    }
}

/** A persisted override and its row id (for removal). */
data class StoredOverride(val id: Long, val override: TitleOverride)

/**
 * U5 split/merge overrides per playlist, applied by `VodCatalogRepository` (its `overrides`
 * provider) and by the search index. Records hold provider item ids, so they reapply after
 * every catalog re-sync.
 */
interface TitleOverrideStore {
    suspend fun overrides(playlistId: Long): List<TitleOverride>
    suspend fun list(playlistId: Long): List<StoredOverride>
    suspend fun add(override: TitleOverride): Long
    suspend fun remove(id: Long)

    /** Fingerprint of the playlist's overrides; changes whenever they do. */
    suspend fun stamp(playlistId: Long): String
}

/**
 * [TitleOverrideStore] over Room. [onChanged] runs after every edit (the app drops the
 * playlist's cached groupings there); the search index notices the change by itself.
 */
class RoomTitleOverrideStore(
    private val store: UserDataStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChanged: suspend (playlistId: Long) -> Unit = {},
) : TitleOverrideStore {
    override suspend fun overrides(playlistId: Long): List<TitleOverride> = list(playlistId).map { it.override }

    override suspend fun list(playlistId: Long): List<StoredOverride> =
        store.overrides(playlistId).mapNotNull { row -> decode(row)?.let { StoredOverride(row.id, it) } }

    override suspend fun add(override: TitleOverride): Long {
        val playlistId = override.playlistId.toLong()
        val id = store.addOverride(
            TitleOverrideEntity(playlistId = playlistId, kind = kindOf(override), payload = encode(override.itemIds), createdAt = clock()),
        )
        onChanged(playlistId)
        return id
    }

    override suspend fun remove(id: Long) {
        val owner = store.override(id)?.playlistId ?: return
        store.removeOverride(id)
        onChanged(owner)
    }

    override suspend fun stamp(playlistId: Long): String {
        val rows = store.overrides(playlistId)
        if (rows.isEmpty()) return EMPTY_STAMP
        val digest = MessageDigest.getInstance("SHA-256")
        rows.forEach { digest.update("${it.id}:${it.kind}:${it.payload};".toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        const val EMPTY_STAMP: String = "none"

        internal fun kindOf(override: TitleOverride): String = when (override) {
            is SplitOverride -> "SPLIT"
            is MergeOverride -> "MERGE"
        }

        /** Item ids are provider stream/series ids (digits); commas cannot occur in them. */
        internal fun encode(ids: Set<String>): String {
            require(ids.none { ',' in it }) { "Item ids cannot contain commas" }
            return ids.sorted().joinToString(",")
        }

        internal fun decode(row: TitleOverrideEntity): TitleOverride? {
            val ids = row.payload.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (ids.isEmpty()) return null
            val playlistId = row.playlistId.toString()
            return when (row.kind) {
                "SPLIT" -> SplitOverride(playlistId, ids)
                "MERGE" -> MergeOverride(playlistId, ids)
                else -> null
            }
        }
    }
}

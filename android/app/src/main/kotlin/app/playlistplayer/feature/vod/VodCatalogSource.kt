package app.playlistplayer.feature.vod

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.entity.SeriesCategoryEntity
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodStreamEntity

/** A playlist as the VOD screens show it (section header = playlist name, R25). */
data class VodPlaylist(val id: Long, val name: String)

/**
 * The catalog reads the VOD feature needs. [CatalogStoreVodSource] implements it over
 * [CatalogStore]; tests use an in-memory fake.
 */
interface VodCatalogSource {
    /** Playlists with Xtream processing on, in display order. */
    suspend fun playlists(): List<VodPlaylist>

    /** Active catalog generation, or null before the first catalog sync. */
    suspend fun catalogGeneration(playlistId: Long): Long?

    suspend fun vodCategories(playlistId: Long): List<VodCategoryEntity>
    suspend fun vodStreamsInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<VodStreamEntity>

    /** Keyset page in provider order; start with `afterOrder = -1`. */
    suspend fun vodStreamsPage(playlistId: Long, afterOrder: Int, limit: Int): List<VodStreamEntity>

    /** One movie version of the active catalog (detail screens). */
    suspend fun vodStream(playlistId: Long, streamId: Long): VodStreamEntity?

    suspend fun seriesCategories(playlistId: Long): List<SeriesCategoryEntity>
    suspend fun seriesInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<SeriesEntity>
    suspend fun seriesPage(playlistId: Long, afterOrder: Int, limit: Int): List<SeriesEntity>

    /** One series of the active catalog (detail screens). */
    suspend fun series(playlistId: Long, seriesId: Long): SeriesEntity?
}

/**
 * [VodCatalogSource] over Room. [playlists] comes from the playlist registry (wiring unit); the
 * catalog generation is the `catalog_sync_state` active pointer, which changes on every
 * successful catalog refresh and so triggers a title index rebuild ([VodTitleIndexer]).
 */
class CatalogStoreVodSource(
    private val store: CatalogStore,
    private val playlistsProvider: suspend () -> List<VodPlaylist>,
) : VodCatalogSource {
    override suspend fun playlists(): List<VodPlaylist> = playlistsProvider()
    override suspend fun catalogGeneration(playlistId: Long): Long? = store.state(playlistId)?.activeGeneration
    override suspend fun vodCategories(playlistId: Long) = store.vodCategories(playlistId)
    override suspend fun vodStreamsInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int) =
        store.vodStreamsInCategory(playlistId, categoryId, limit, offset)
    override suspend fun vodStreamsPage(playlistId: Long, afterOrder: Int, limit: Int) =
        store.vodStreamsPage(playlistId, afterOrder, limit)
    override suspend fun vodStream(playlistId: Long, streamId: Long) = store.vodStream(playlistId, streamId)
    override suspend fun seriesCategories(playlistId: Long) = store.seriesCategories(playlistId)
    override suspend fun seriesInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int) =
        store.seriesInCategory(playlistId, categoryId, limit, offset)
    override suspend fun seriesPage(playlistId: Long, afterOrder: Int, limit: Int) =
        store.seriesPage(playlistId, afterOrder, limit)
    override suspend fun series(playlistId: Long, seriesId: Long) = store.series(playlistId, seriesId)
}

package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.TitleTokenizer
import app.playlistplayer.core.consolidation.VodItem
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.consolidation.VodTitle
import app.playlistplayer.core.consolidation.VodVersion
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.VodStreamEntity

/** Display and playback attributes of one catalog row (movie stream or series), by item id. */
data class ItemMeta(
    val itemId: String,
    val name: String,
    val categoryId: String?,
    val icon: String?,
    val containerExtension: String?,
    val rating: Double?,
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    /** When the provider added (movies) or last updated (series) the item, as the catalog stores it. */
    val added: Long? = null,
)

/** A merged title plus the catalog attributes of each of its versions. */
data class CatalogTitle(
    val title: VodTitle,
    val meta: Map<String, ItemMeta>,
) {
    val playlistId: Long get() = title.playlistId.toLong()
    val key: String get() = title.key
    val kind: VodKind get() = title.kind

    /** Id of the first version in provider order: the stable handle a browse card carries. */
    val anchorId: String get() = title.versions.first().item.id

    /** First version (provider order) that has artwork. */
    val posterUrl: String?
        get() = title.versions.firstNotNullOfOrNull { meta[it.item.id]?.icon?.takeIf(String::isNotBlank) }

    /** Highest labelled quality among the versions, or null when none is labelled. */
    val bestQuality: QualityTier?
        get() = title.versions.map { it.version.quality }
            .maxByOrNull { it.rank }
            ?.takeIf { it.rank > QualityTier.UNLABELED.rank }

    /** The title's year, else the year of a version's release date. */
    val releaseYear: Int?
        get() = title.year ?: title.versions.firstNotNullOfOrNull { v ->
            meta[v.item.id]?.releaseDate?.trim()?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
        }

    /** Newest provider "added" time among the versions (the Recently added sort key). */
    val addedAt: Long?
        get() = title.versions.mapNotNull { meta[it.item.id]?.added }.maxOrNull()

    fun card(): VodCard = VodCard(
        playlistId = playlistId,
        kind = kind,
        anchorId = anchorId,
        titleKey = key,
        title = title.title,
        year = releaseYear,
        posterUrl = posterUrl,
        bestQuality = bestQuality,
        versionCount = title.versions.size,
    )
}

/** One browse card = one merged title (R13). */
data class VodCard(
    val playlistId: Long,
    val kind: VodKind,
    val anchorId: String,
    val titleKey: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val bestQuality: QualityTier?,
    val versionCount: Int,
)

internal object VodMappers {

    fun movieItem(row: VodStreamEntity, categoryName: String?): VodItem = VodItem(
        id = row.streamId.toString(),
        playlistId = row.playlistId.toString(),
        kind = VodKind.MOVIE,
        name = row.name,
        tmdb = row.tmdb,
        categoryName = categoryName,
        categoryLanguage = TitleTokenizer.languageOfCategory(categoryName),
        providerOrder = row.providerOrder,
    )

    fun seriesItem(row: SeriesEntity, categoryName: String?): VodItem = VodItem(
        id = row.seriesId.toString(),
        playlistId = row.playlistId.toString(),
        kind = VodKind.SERIES,
        name = row.name,
        tmdb = row.tmdb,
        categoryName = categoryName,
        categoryLanguage = TitleTokenizer.languageOfCategory(categoryName),
        providerOrder = row.providerOrder,
    )

    fun movieMeta(row: VodStreamEntity): ItemMeta = ItemMeta(
        itemId = row.streamId.toString(),
        name = row.name,
        categoryId = row.categoryId,
        icon = row.icon,
        containerExtension = row.containerExtension,
        rating = row.rating,
        added = row.added,
    )

    fun seriesMeta(row: SeriesEntity): ItemMeta = ItemMeta(
        itemId = row.seriesId.toString(),
        name = row.name,
        categoryId = row.categoryId,
        icon = row.cover,
        containerExtension = null,
        rating = row.rating,
        plot = row.plot,
        genre = row.genre,
        releaseDate = row.releaseDate,
        added = row.lastModified,
    )
}

/** Language chip key for versions whose language is unknown. */
const val UNKNOWN_LANGUAGE: String = "und"

internal fun VodVersion.languageKey(): String = version.language ?: UNKNOWN_LANGUAGE

/** One row of a version picker. [label] is the provider name of the version. */
data class VersionRow(
    val id: String,
    val label: String,
    val language: String?,
    val quality: QualityTier,
    val hdr: Boolean,
    val isDefault: Boolean,
    val isSelected: Boolean,
)

/** A filter chip over a title's versions (R15). */
data class FilterChipModel(val key: String, val selected: Boolean)

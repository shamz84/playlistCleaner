package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VodVersion

/**
 * Language and quality filter over a title's ranked versions (R15). Each chip row is computed
 * from the versions that pass the other filter, so a chip combination never yields an empty list.
 * A null filter means "all".
 */
internal data class VersionFilter(val language: String? = null, val quality: QualityTier? = null) {

    fun apply(ranked: List<VodVersion>): List<VodVersion> =
        ranked.filter { matchesLanguage(it) && matchesQuality(it) }

    fun languageChips(ranked: List<VodVersion>): List<FilterChipModel> =
        ranked.filter(::matchesQuality).map { it.languageKey() }.distinct()
            .map { FilterChipModel(it, it == language) }

    fun qualityChips(ranked: List<VodVersion>): List<FilterChipModel> =
        ranked.filter(::matchesLanguage).map { it.version.quality }.distinct()
            .sortedByDescending { it.rank }
            .map { FilterChipModel(it.name, it == quality) }

    /** Toggles the language chip [key] (selecting the active chip clears the filter). */
    fun toggleLanguage(key: String?, ranked: List<VodVersion>): VersionFilter {
        val next = if (key == null || key == language) copy(language = null) else copy(language = key)
        return next.normalized(ranked)
    }

    fun toggleQuality(key: String?, ranked: List<VodVersion>): VersionFilter {
        val tier = key?.let { k -> QualityTier.entries.firstOrNull { it.name == k } }
        val next = if (tier == null || tier == quality) copy(quality = null) else copy(quality = tier)
        return next.normalized(ranked)
    }

    /** Drops the other filter when the combination would match nothing. */
    private fun normalized(ranked: List<VodVersion>): VersionFilter = when {
        apply(ranked).isNotEmpty() -> this
        copy(quality = null).apply(ranked).isNotEmpty() -> copy(quality = null)
        else -> VersionFilter()
    }

    private fun matchesLanguage(v: VodVersion) = language == null || v.languageKey() == language
    private fun matchesQuality(v: VodVersion) = quality == null || v.version.quality == quality
}

internal fun List<VodVersion>.toRows(defaultId: String?, selectedId: String?): List<VersionRow> = map {
    VersionRow(
        id = it.item.id,
        label = it.item.name,
        language = it.version.language,
        quality = it.version.quality,
        hdr = it.version.hdr,
        isDefault = it.item.id == defaultId,
        isSelected = it.item.id == selectedId,
    )
}

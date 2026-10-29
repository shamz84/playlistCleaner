package app.playlistplayer.core.consolidation

import java.util.Locale

/**
 * One playable version of a live channel or VOD title, as seen by [VersionComparator].
 *
 * @property id the stream/entry id the caller uses to identify the version.
 * @property height vertical lines, defaulting to the tier's nominal height.
 * @property providerOrder position in the provider's list (playlist order), lower first.
 */
data class Version(
    val id: String,
    val language: String? = null,
    val quality: QualityTier = QualityTier.UNLABELED,
    val height: Int? = quality.nominalHeight,
    val hdr: Boolean = false,
    val codec: String? = null,
    val fps: Int? = null,
    val isBackup: Boolean = false,
    val providerOrder: Int = 0,
) {
    companion object {
        fun from(
            id: String,
            tokens: TitleTokens,
            providerOrder: Int,
            language: String? = tokens.language,
        ): Version = Version(
            id = id,
            language = language,
            quality = tokens.quality,
            height = tokens.resolution,
            hdr = tokens.hdr,
            codec = tokens.codec,
            fps = tokens.fps,
            isBackup = tokens.isBackup,
            providerOrder = providerOrder,
        )
    }
}

/**
 * What the device can decode and display. A version that exceeds any limit still plays as a
 * last resort, but ranks below every version that fits.
 *
 * @property maxVideoHeight highest vertical resolution the decoder and display handle.
 */
data class DeviceCapabilities(
    val maxVideoHeight: Int = Int.MAX_VALUE,
    val supportsHdr: Boolean = true,
    val supportsHevc: Boolean = true,
) {
    fun fits(version: Version): Boolean {
        val height = version.height
        if (height != null && height > maxVideoHeight) return false
        if (version.hdr && !supportsHdr) return false
        if (version.codec == "hevc" && !supportsHevc) return false
        return true
    }

    companion object {
        val UNRESTRICTED = DeviceCapabilities()
    }
}

/**
 * Inputs to the ranking.
 *
 * @property languages ranked language tags, best first (default English). Tags are compared
 *   case-insensitively and exactly (`in-tl` does not match `in`).
 * @property manualPickId the version id the user picked for this title, if any.
 */
data class VersionPreferences(
    val languages: List<String> = listOf("en"),
    val device: DeviceCapabilities = DeviceCapabilities.UNRESTRICTED,
    val manualPickId: String? = null,
)

/**
 * The single ordering used for VOD default pick and picker order, other live versions,
 * failover target and next-episode continuity. Earlier keys win:
 *
 * 1. the manual pick;
 * 2. language list position. Versions with no known language rank right after the listed
 *    languages and before versions in a known, unlisted language (an untagged name is more
 *    likely the original or English audio than an explicit foreign dub);
 * 3. device capability cap: versions that fit [DeviceCapabilities] before those that do not;
 * 4. quality tier ([QualityTier.rank], higher first);
 * 5. non-backup before backup;
 * 6. tie-breaks inside equal tier: HDR first (only when the device supports HDR), then higher
 *    frame rate (`1080p50` before `1080p25`);
 * 7. provider order, then id for a total, stable order.
 */
class VersionComparator(
    private val preferences: VersionPreferences = VersionPreferences(),
) : Comparator<Version> {

    private val languageIndex: Map<String, Int> = buildMap {
        preferences.languages.forEachIndexed { index, tag ->
            putIfAbsent(tag.trim().lowercase(Locale.ROOT), index)
        }
    }

    override fun compare(a: Version, b: Version): Int {
        val pick = preferences.manualPickId
        if (pick != null) {
            val aPicked = a.id == pick
            val bPicked = b.id == pick
            if (aPicked != bPicked) return if (aPicked) -1 else 1
        }
        languageRank(a).compareTo(languageRank(b)).let { if (it != 0) return it }
        val device = preferences.device
        val aFits = device.fits(a)
        val bFits = device.fits(b)
        if (aFits != bFits) return if (aFits) -1 else 1
        b.quality.rank.compareTo(a.quality.rank).let { if (it != 0) return it }
        if (a.isBackup != b.isBackup) return if (a.isBackup) 1 else -1
        if (device.supportsHdr && a.hdr != b.hdr) return if (a.hdr) -1 else 1
        (b.fps ?: 0).compareTo(a.fps ?: 0).let { if (it != 0) return it }
        a.providerOrder.compareTo(b.providerOrder).let { if (it != 0) return it }
        return a.id.compareTo(b.id)
    }

    private fun languageRank(version: Version): Int {
        val language = version.language?.lowercase(Locale.ROOT)
            ?: return languageIndex.size
        return languageIndex[language] ?: (languageIndex.size + 1)
    }
}

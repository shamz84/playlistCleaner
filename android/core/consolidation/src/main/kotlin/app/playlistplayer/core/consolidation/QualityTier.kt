package app.playlistplayer.core.consolidation

/**
 * Quality tiers with an explicit [rank] (higher is better). The comparator orders by [rank],
 * never by enum ordinal.
 *
 * Placement decisions, checked against the real playlist names (`names/live_names.txt`):
 * - [UNLABELED] (no quality label at all) ranks below [SD], as the review asked.
 * - [LOW_SOURCE] (Telesync / CAM / HDTS releases) ranks below everything, even unlabeled.
 * - [RAW] ranks between [FHD] and [UHD]. In the real playlist `ᴿᴬᵂ` marks untranscoded source
 *   feeds on kids, Disney+/AMC+ premiere and regional-sports channels (`ONEPLAY Bluey ᴿᴬᵂ`,
 *   `DISNEY+ SERIES 1 ᴿᴬᵂ`, `NBC SPORTS NOW (A) ᴿᴬᵂ`), never on 4K content, so it is a source
 *   marker with unknown resolution rather than a 4K label. It still beats re-encoded FHD.
 *   When a RAW name also carries a resolution label, the resolution decides the tier and RAW
 *   becomes [VersionFlag.RAW_SOURCE] only.
 * - [UHD_8K] ranks above [UHD]; the device capability cap demotes it on 4K-max devices.
 * - "Backup" is not a tier; it is [VersionFlag.BACKUP].
 *
 * @property nominalHeight vertical resolution implied by the tier when no explicit
 *   resolution label is present; `null` when unknown.
 */
enum class QualityTier(val rank: Int, val nominalHeight: Int?) {
    LOW_SOURCE(0, null),
    UNLABELED(1, null),
    SD(2, 576),
    HD(3, 720),
    FHD(4, 1080),
    RAW(5, null),
    UHD(6, 2160),
    UHD_8K(7, 4320),
}

/** Non-quality markers found in a name. */
enum class VersionFlag {
    /** `(Backup)`, `[Backup]`: a failover feed. Ranks below a non-backup of the same tier. */
    BACKUP,

    /** `MULTI-SUB(S)`. */
    MULTI_SUB,

    /** `MULTI` / `MULTI AUDIO`. */
    MULTI_AUDIO,

    /** `DUAL` / `DUAL AUDIO`. */
    DUAL_AUDIO,

    /** `RAW` label (untranscoded source feed). */
    RAW_SOURCE,

    /** Telesync / CAM style release. */
    LOW_SOURCE,
}

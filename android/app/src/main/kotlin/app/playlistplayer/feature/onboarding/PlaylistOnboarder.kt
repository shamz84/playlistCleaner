package app.playlistplayer.feature.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * Where a new playlist comes from (R1, R23). Both forms are bearer secrets, so [toString] never
 * reveals them; the onboarder stores them only encrypted.
 */
sealed interface SourceInput {
    /** A link-shared Google Drive file. [resourceKey] is set when the share link carried one. */
    data class DriveId(val id: String, val resourceKey: String? = null) : SourceInput {
        override fun toString(): String =
            "SourceInput.DriveId(id=***, resourceKey=${if (resourceKey == null) "null" else "***"})"
    }

    /** A plain http(s) M3U URL; it may embed account credentials. */
    data class M3uUrl(val url: String) : SourceInput {
        override fun toString(): String = "SourceInput.M3uUrl(url=***)"
    }
}

/** Onboarding stages, in order. Channels are usable once [Channels] is done (catalog/guide continue). */
enum class OnboardingStage { Playlist, Channels, Catalog, Guide }

enum class StageStatus {
    Pending,
    Running,
    Done,

    /** Not run: Xtream processing is off, or the playlist turned out not to be Xtream-shaped (R24). */
    Skipped,

    /** Failed without blocking onboarding (only Catalog and Guide may fail after Channels is done). */
    Failed,
    ;

    val isTerminal: Boolean get() = this == Done || this == Skipped || this == Failed
}

/** One progress update for [stage]. [itemCount] is optional detail (e.g. channels stored). */
data class OnboardingProgress(
    val stage: OnboardingStage,
    val status: StageStatus,
    val itemCount: Int? = null,
)

/** Failure causes the onboarding screen explains to the user. */
enum class OnboardingError {
    /** Drive API 404: wrong ID, or the file is not link-shared (Drive does not say which). */
    NotFoundOrNotShared,

    /** Drive API 403 on a file that exists but is restricted. */
    NotShared,

    /** Drive API quota / rate limit (403 `userRateLimitExceeded` / 429). */
    QuotaExceeded,

    /** No connectivity, DNS, timeout, or a 5xx from the source. */
    Network,

    /** The downloaded file is not a playable M3U (no `#EXTM3U`/`#EXTINF` entries), or bad input. */
    InvalidInput,

    /** Provider reports the Xtream account expired or disabled. */
    AccountExpired,

    /** Anything else; shown as a generic failure. */
    Other,
}

sealed interface OnboardingResult {
    /**
     * The playlist was stored and its channels are usable. [xtreamEnabled] is the effective flag:
     * false when it was requested but the downloaded playlist is not Xtream-shaped (R24).
     */
    data class Success(val playlistId: Long, val xtreamEnabled: Boolean) : OnboardingResult

    data class Failure(val error: OnboardingError) : OnboardingResult
}

/**
 * Adds a playlist (first run, or "Add playlist" later; U15). Implemented by the wiring unit over
 * `PlaylistRegistry` / `PlaylistSyncer` / the catalog and EPG sync.
 *
 * Contract:
 * - [addPlaylist] returns once the playlist and its channels are stored ([OnboardingResult.Success])
 *   or onboarding failed. It may return later (after all stages); the view model handles both.
 *   It must not throw for expected failures; it should honour cancellation by discarding any
 *   partially written generation.
 * - [progress] is a hot flow of per-stage updates for the add in flight. The view model subscribes
 *   before calling [addPlaylist] and stops once every stage is terminal. Catalog and Guide updates
 *   may keep arriving after [addPlaylist] returned (they run as background work). When Xtream
 *   processing is off (or turns out to be unavailable) Catalog and Guide are reported [StageStatus.Skipped].
 */
interface PlaylistOnboarder {
    val progress: Flow<OnboardingProgress>

    /** [epgOverrideUrl], when set, is stored as the playlist's guide override before the guide is queued. */
    suspend fun addPlaylist(name: String, source: SourceInput, xtreamEnabled: Boolean, epgOverrideUrl: String? = null): OnboardingResult
}

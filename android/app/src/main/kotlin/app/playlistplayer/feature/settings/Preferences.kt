package app.playlistplayer.feature.settings

import app.playlistplayer.core.consolidation.DeviceCapabilities
import app.playlistplayer.core.consolidation.VersionPreferences
import app.playlistplayer.core.player.catchup.CatchupUrlStyle
import app.playlistplayer.core.player.url.LiveFormatPreference
import java.util.Locale

/** Highest quality to pick by default. [maxHeight] null = no cap (best available). */
enum class QualityPreference(val maxHeight: Int?) {
    BEST(null),
    UHD(2160),
    FHD(1080),
    HD(720),
    SD(576),
}

/**
 * Content preferences (R14).
 *
 * @property languages ranked language tags, best first; lower-case provider codes such as
 *   `en` or `in-tl`, non-empty and distinct.
 */
data class ContentPreferences(
    val languages: List<String> = listOf("en"),
    val quality: QualityPreference = QualityPreference.BEST,
) {
    init {
        require(languages.isNotEmpty()) { "at least one language is required" }
        require(languages.all { LANGUAGE.matches(it) }) { "invalid language tag" }
        require(languages.distinct().size == languages.size) { "duplicate language" }
    }

    /** Moves the language at [from] to position [to] (D-pad reorder). */
    fun moveLanguage(from: Int, to: Int): ContentPreferences {
        require(from in languages.indices && to in languages.indices) { "index out of range" }
        if (from == to) return this
        val list = languages.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(languages = list)
    }

    /** Adds [tag] at the end (or keeps its position when already present). */
    fun addLanguage(tag: String): ContentPreferences {
        val code = normalizeLanguage(tag)
        return if (code in languages) this else copy(languages = languages + code)
    }

    /** Removes [tag]; the last remaining language cannot be removed. */
    fun removeLanguage(tag: String): ContentPreferences {
        val code = normalizeLanguage(tag)
        if (code !in languages || languages.size == 1) return this
        return copy(languages = languages - code)
    }

    /** Ranking inputs for `:core:consolidation`, capping the device height by [quality]. */
    fun versionPreferences(device: DeviceCapabilities = DeviceCapabilities.UNRESTRICTED): VersionPreferences {
        val cap = quality.maxHeight
        val capped = if (cap == null) device else device.copy(maxVideoHeight = minOf(device.maxVideoHeight, cap))
        return VersionPreferences(languages = languages, device = capped)
    }

    companion object {
        internal val LANGUAGE = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        fun normalizeLanguage(tag: String): String = tag.trim().lowercase(Locale.ROOT)
    }
}

/**
 * How video fills the screen: [FIT] (Normal) keeps the video's own shape and letterboxes,
 * [STRETCH] (Full) fills the screen by distorting, [ZOOM] fills by cropping, and the ratio entries
 * show the picture at that fixed shape, letterboxed in the screen. Declaration order is the
 * order the phone cycles through; names are stored, so do not rename them.
 */
enum class VideoScaling(val forcedAspect: Float? = null) {
    FIT, STRETCH, R4_3(4f / 3f), R16_9(16f / 9f), R2_35(2.35f), ZOOM;

    fun next(): VideoScaling = entries[(ordinal + 1) % entries.size]
}

enum class AppTheme {
    MIDNIGHT_BLUE, CINEMA_EMBER, GRAPHITE;

    fun next(): AppTheme = entries[(ordinal + 1) % entries.size]
}

/**
 * Playback preferences.
 *
 * @property videoScaling how video fills the screen (default [VideoScaling.FIT]).
 * @property catchupOffsetHours correction applied to catch-up start times, in [CATCHUP_OFFSET_RANGE].
 * @property audioLanguage preferred audio language of movies and episodes (lower-case BCP 47
 *   tag such as `en` or `pt-br`); null = the device language (then English).
 * @property subtitleLanguage preferred subtitle language of movies and episodes (default English);
 *   null = off.
 */
data class PlaybackPreferences(
    val liveFormat: LiveFormatPreference = LiveFormatPreference.TS,
    val matchFrameRate: Boolean = false,
    val tunneling: Boolean = false,
    val catchupOffsetHours: Int = 0,
    val catchupUrlStyle: CatchupUrlStyle = CatchupUrlStyle.PATH,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = DEFAULT_SUBTITLE_LANGUAGE,
    val videoScaling: VideoScaling = VideoScaling.FIT,
    val showClock: Boolean = false,
    val showVideoInfo: Boolean = false,
    val showVodDiagnostics: Boolean = false,
) {
    init {
        require(catchupOffsetHours in CATCHUP_OFFSET_RANGE) { "catch-up offset out of range" }
        require(audioLanguage == null || ContentPreferences.LANGUAGE.matches(audioLanguage)) { "invalid audio language" }
        require(subtitleLanguage == null || ContentPreferences.LANGUAGE.matches(subtitleLanguage)) { "invalid subtitle language" }
    }

    companion object {
        val CATCHUP_OFFSET_RANGE = -12..12
        const val DEFAULT_SUBTITLE_LANGUAGE = "en"
    }
}

/**
 * Sync preferences.
 *
 * @property refreshIntervalHours periodic playlist refresh, in [REFRESH_RANGE].
 * @property staleWarningDays age after which cached data shows a staleness warning, in [STALE_RANGE].
 */
data class SyncPreferences(
    val refreshIntervalHours: Int = 6,
    val staleWarningDays: Int = 7,
) {
    init {
        require(refreshIntervalHours in REFRESH_RANGE) { "refresh interval out of range" }
        require(staleWarningDays in STALE_RANGE) { "stale warning out of range" }
    }

    companion object {
        val REFRESH_RANGE = 1..168
        val STALE_RANGE = 1..90
    }
}

data class AppPreferences(
    val content: ContentPreferences = ContentPreferences(),
    val playback: PlaybackPreferences = PlaybackPreferences(),
    val sync: SyncPreferences = SyncPreferences(),
    /** Playlist ids in the order the user wants them listed in Live TV; unlisted playlists follow in the order added. */
    val playlistOrder: List<Long> = emptyList(),
    val theme: AppTheme = AppTheme.MIDNIGHT_BLUE,
)

/**
 * Flattens [AppPreferences] to a string map for DataStore and back. Decoding is lenient so a
 * bad or missing value never blocks start-up: an unparseable value falls back to its default,
 * an out-of-range number is coerced into its range, and invalid language tags are dropped
 * (an empty result falls back to the default list). Unknown keys are ignored.
 */
object PreferencesCodec {
    const val KEY_VERSION = "prefs.version"
    const val KEY_LANGUAGES = "content.languages"
    const val KEY_QUALITY = "content.quality"
    const val KEY_LIVE_FORMAT = "playback.liveFormat"
    const val KEY_MATCH_FRAME_RATE = "playback.matchFrameRate"
    const val KEY_TUNNELING = "playback.tunneling"
    const val KEY_CATCHUP_OFFSET = "playback.catchupOffsetHours"
    const val KEY_CATCHUP_STYLE = "playback.catchupUrlStyle"
    const val KEY_AUDIO_LANGUAGE = "playback.audioLanguage"
    const val KEY_SUBTITLE_LANGUAGE = "playback.subtitleLanguage"
    const val KEY_VIDEO_SCALING = "playback.videoScaling"
    const val KEY_SHOW_CLOCK = "playback.showClock"
    const val KEY_SHOW_VIDEO_INFO = "playback.showVideoInfo"
    const val KEY_SHOW_VOD_DIAGNOSTICS = "playback.showVodDiagnostics"
    const val KEY_REFRESH_HOURS = "sync.refreshIntervalHours"
    const val KEY_STALE_DAYS = "sync.staleWarningDays"
    const val KEY_PLAYLIST_ORDER = "live.playlistOrder"
    const val KEY_THEME = "app.theme"
    private const val VERSION = "1"

    /** Stored subtitle value for an explicit "Off"; missing or empty means the default (English). */
    const val SUBTITLES_OFF = "off"

    fun encode(prefs: AppPreferences): Map<String, String> = mapOf(
        KEY_VERSION to VERSION,
        KEY_LANGUAGES to prefs.content.languages.joinToString(","),
        KEY_QUALITY to prefs.content.quality.name,
        KEY_LIVE_FORMAT to prefs.playback.liveFormat.name,
        KEY_MATCH_FRAME_RATE to prefs.playback.matchFrameRate.toString(),
        KEY_TUNNELING to prefs.playback.tunneling.toString(),
        KEY_CATCHUP_OFFSET to prefs.playback.catchupOffsetHours.toString(),
        KEY_CATCHUP_STYLE to prefs.playback.catchupUrlStyle.name,
        // Empty audio = device language.
        KEY_AUDIO_LANGUAGE to prefs.playback.audioLanguage.orEmpty(),
        KEY_SUBTITLE_LANGUAGE to (prefs.playback.subtitleLanguage ?: SUBTITLES_OFF),
        KEY_VIDEO_SCALING to prefs.playback.videoScaling.name,
        KEY_SHOW_CLOCK to prefs.playback.showClock.toString(),
        KEY_SHOW_VIDEO_INFO to prefs.playback.showVideoInfo.toString(),
        KEY_SHOW_VOD_DIAGNOSTICS to prefs.playback.showVodDiagnostics.toString(),
        KEY_REFRESH_HOURS to prefs.sync.refreshIntervalHours.toString(),
        KEY_STALE_DAYS to prefs.sync.staleWarningDays.toString(),
        KEY_PLAYLIST_ORDER to prefs.playlistOrder.joinToString(","),
        KEY_THEME to prefs.theme.name,
    )

    fun decode(map: Map<String, String>): AppPreferences {
        val d = AppPreferences()
        val languages = map[KEY_LANGUAGES]
            ?.split(',')
            ?.map(ContentPreferences::normalizeLanguage)
            ?.filter { ContentPreferences.LANGUAGE.matches(it) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: d.content.languages
        return AppPreferences(
            content = ContentPreferences(
                languages = languages,
                quality = enumOr(map[KEY_QUALITY], d.content.quality),
            ),
            playback = PlaybackPreferences(
                liveFormat = enumOr(map[KEY_LIVE_FORMAT], d.playback.liveFormat),
                matchFrameRate = boolOr(map[KEY_MATCH_FRAME_RATE], d.playback.matchFrameRate),
                tunneling = boolOr(map[KEY_TUNNELING], d.playback.tunneling),
                catchupOffsetHours = intIn(map[KEY_CATCHUP_OFFSET], PlaybackPreferences.CATCHUP_OFFSET_RANGE, d.playback.catchupOffsetHours),
                catchupUrlStyle = enumOr(map[KEY_CATCHUP_STYLE], d.playback.catchupUrlStyle),
                audioLanguage = languageOrNull(map[KEY_AUDIO_LANGUAGE]),
                videoScaling = enumOr(map[KEY_VIDEO_SCALING], d.playback.videoScaling),
                showClock = boolOr(map[KEY_SHOW_CLOCK], d.playback.showClock),
                showVideoInfo = boolOr(map[KEY_SHOW_VIDEO_INFO], d.playback.showVideoInfo),
                showVodDiagnostics = boolOr(map[KEY_SHOW_VOD_DIAGNOSTICS], d.playback.showVodDiagnostics),
                subtitleLanguage = map[KEY_SUBTITLE_LANGUAGE]?.trim().let { v ->
                    if (v == SUBTITLES_OFF) null else languageOrNull(v) ?: d.playback.subtitleLanguage
                },
            ),
            sync = SyncPreferences(
                refreshIntervalHours = intIn(map[KEY_REFRESH_HOURS], SyncPreferences.REFRESH_RANGE, d.sync.refreshIntervalHours),
                staleWarningDays = intIn(map[KEY_STALE_DAYS], SyncPreferences.STALE_RANGE, d.sync.staleWarningDays),
            ),
            playlistOrder = map[KEY_PLAYLIST_ORDER].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.distinct(),
            theme = enumOr(map[KEY_THEME], d.theme),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v.trim() } } ?: default

    private fun boolOr(value: String?, default: Boolean): Boolean = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> default
    }

    /** A valid normalised language tag, else null (blank, missing or invalid). */
    private fun languageOrNull(value: String?): String? =
        value?.let(ContentPreferences::normalizeLanguage)?.takeIf { ContentPreferences.LANGUAGE.matches(it) }

    private fun intIn(value: String?, range: IntRange, default: Int): Int =
        value?.trim()?.toIntOrNull()?.coerceIn(range) ?: default
}

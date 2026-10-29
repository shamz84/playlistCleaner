@file:OptIn(ExperimentalSerializationApi::class)

package app.playlistplayer.core.network.xtream

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DateTimeException
import java.time.ZoneId

// ---------------------------------------------------------------------------------------------
// Lenient value parsing. Xtream panels send numbers as strings or numbers, empty strings for
// "absent", booleans as 0/1 or "0"/"1", and lists as either arrays or comma-separated strings.
// ---------------------------------------------------------------------------------------------

/** Lenient conversions from any JSON element. Unparseable values become `null`, never errors. */
object XtreamValues {
    /** Primitive content; blank, `"null"`, objects and arrays are absent. Text is not trimmed. */
    fun string(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val content = primitive.content
        if (content.isBlank() || (!primitive.isString && content == "null")) return null
        return content
    }

    fun long(element: JsonElement?): Long? {
        val text = string(element)?.trim() ?: return null
        text.toLongOrNull()?.let { return it }
        val d = text.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) d.toLong() else null
    }

    fun int(element: JsonElement?): Int? = long(element)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    fun double(element: JsonElement?): Double? =
        string(element)?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** `1`/`"1"`/`true`/`"true"` → true; `0`/`"0"`/`false`/`"false"` → false; other numbers → != 0. */
    fun boolean(element: JsonElement?): Boolean? {
        val text = string(element)?.trim()?.lowercase() ?: return null
        return when (text) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> long(element)?.let { it != 0L }
        }
    }

    /**
     * TMDB id normalised to its decimal string: `"123"` and `123` → `"123"`; `""`, `0`, `"0"`,
     * null and non-numeric text → absent.
     */
    fun tmdb(element: JsonElement?): String? = long(element)?.takeIf { it > 0 }?.toString()

    /** An array of primitives (nulls/blanks dropped) or a comma-separated string. */
    fun stringList(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { string(it) }
        is JsonPrimitive -> string(element)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }
}

/** Base for nullable lenient primitives: accepts any JSON token and never throws on bad values. */
abstract class LenientSerializer<T : Any>(
    name: String,
    kind: PrimitiveKind,
    private val parse: (JsonElement) -> T?,
) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.playlistplayer.xtream.$name", kind).nullable

    override fun deserialize(decoder: Decoder): T? {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Xtream models decode from JSON only")
        return parse(input.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }
}

object LenientStringSerializer : LenientSerializer<String>("LenientString", PrimitiveKind.STRING, XtreamValues::string)
object LenientLongSerializer : LenientSerializer<Long>("LenientLong", PrimitiveKind.LONG, XtreamValues::long)
object LenientIntSerializer : LenientSerializer<Int>("LenientInt", PrimitiveKind.INT, XtreamValues::int)
object LenientDoubleSerializer : LenientSerializer<Double>("LenientDouble", PrimitiveKind.DOUBLE, XtreamValues::double)
object LenientBooleanSerializer : LenientSerializer<Boolean>("LenientBoolean", PrimitiveKind.BOOLEAN, XtreamValues::boolean)
object TmdbSerializer : LenientSerializer<String>("Tmdb", PrimitiveKind.STRING, XtreamValues::tmdb)

/** A list of strings given as an array or a comma-separated string; anything else is empty. */
object LenientStringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.playlistplayer.xtream.LenientStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Xtream models decode from JSON only")
        return XtreamValues.stringList(input.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: List<String>) = encoder.encodeString(value.joinToString(","))
}

/**
 * A nested object that PHP panels sometimes send as `[]` (empty array) or `null`: anything that is
 * not an object, or that fails to decode, becomes `null`.
 */
open class LenientObjectSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun deserialize(decoder: Decoder): T? {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Xtream models decode from JSON only")
        val element = input.decodeJsonElement() as? JsonObject ?: return null
        return try {
            input.json.decodeFromJsonElement(inner, element)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, value)
    }
}

/** A list of objects; non-object or undecodable elements are skipped and a non-array is empty. */
open class LenientListSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<List<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "app.playlistplayer.xtream.LenientList<${inner.descriptor.serialName}>",
        PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: Decoder): List<T> {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Xtream models decode from JSON only")
        val array = input.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            if (element !is JsonObject) return@mapNotNull null
            try {
                input.json.decodeFromJsonElement(inner, element)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<T>) = throw SerializationException("Not supported")
}

// ---------------------------------------------------------------------------------------------
// player_api.php (no action): user_info + server_info
// ---------------------------------------------------------------------------------------------

/**
 * `user_info`. `username`/`password` are deliberately not mapped: they are the account secrets
 * and must not travel further than the request.
 *
 * @property expDate expiry as epoch seconds; null means "no expiry".
 */
@Serializable
data class XtreamUserInfo(
    @Serializable(LenientBooleanSerializer::class) val auth: Boolean? = null,
    @Serializable(LenientStringSerializer::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(LenientLongSerializer::class) val expDate: Long? = null,
    @SerialName("is_trial") @Serializable(LenientBooleanSerializer::class) val isTrial: Boolean? = null,
    @SerialName("active_cons") @Serializable(LenientIntSerializer::class) val activeConnections: Int? = null,
    @SerialName("max_connections") @Serializable(LenientIntSerializer::class) val maxConnections: Int? = null,
    @SerialName("created_at") @Serializable(LenientLongSerializer::class) val createdAt: Long? = null,
    @SerialName("allowed_output_formats") @Serializable(LenientStringListSerializer::class)
    val allowedOutputFormats: List<String> = emptyList(),
    @Serializable(LenientStringSerializer::class) val message: String? = null,
) {
    /** Expiry in epoch millis, or null for "no expiry" (`exp_date` null, `""` or 0). */
    val expiresAtMillis: Long? get() = expDate?.takeIf { it > 0 }?.let { it * 1000 }
}

/**
 * `server_info`. The host (`url`) and ports are not mapped: the account's base URL is the
 * authority, and the host is a secret.
 */
@Serializable
data class XtreamServerInfo(
    @Serializable(LenientStringSerializer::class) val timezone: String? = null,
    @SerialName("timestamp_now") @Serializable(LenientLongSerializer::class) val timestampNow: Long? = null,
    @SerialName("time_now") @Serializable(LenientStringSerializer::class) val timeNow: String? = null,
    @SerialName("server_protocol") @Serializable(LenientStringSerializer::class) val serverProtocol: String? = null,
) {
    /** `timezone` as a ZoneId (e.g. Europe/Amsterdam), or null when absent/unknown. */
    val zoneId: ZoneId?
        get() = timezone?.let {
            try {
                ZoneId.of(it.trim())
            } catch (_: DateTimeException) {
                null
            }
        }
}

/** `player_api.php` without an action. */
data class AccountInfo(
    val userInfo: XtreamUserInfo,
    val serverInfo: XtreamServerInfo?,
) {
    /** False when the panel rejected the credentials (`auth: 0`). */
    val isAuthenticated: Boolean get() = userInfo.auth != false && (userInfo.auth == true || userInfo.status != null)

    /** Authenticated and `status` is `Active` (a missing status with `auth: 1` counts as active). */
    val isActive: Boolean
        get() = isAuthenticated && (userInfo.status == null || userInfo.status.trim().equals(STATUS_ACTIVE, ignoreCase = true))

    companion object {
        const val STATUS_ACTIVE: String = "Active"
    }
}

@Serializable
internal data class AccountInfoDto(
    @SerialName("user_info") @Serializable(UserInfoObject::class) val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") @Serializable(ServerInfoObject::class) val serverInfo: XtreamServerInfo? = null,
)

internal object UserInfoObject : LenientObjectSerializer<XtreamUserInfo>(XtreamUserInfo.serializer())
internal object ServerInfoObject : LenientObjectSerializer<XtreamServerInfo>(XtreamServerInfo.serializer())

// ---------------------------------------------------------------------------------------------
// Categories and catalog lists
// ---------------------------------------------------------------------------------------------

/** One entry of get_live_categories / get_vod_categories / get_series_categories. */
@Serializable
data class XtreamCategory(
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(LenientStringSerializer::class) val categoryName: String? = null,
    @SerialName("parent_id") @Serializable(LenientIntSerializer::class) val parentId: Int? = null,
)

/** One entry of get_live_streams (only the fields the app uses). */
@Serializable
data class XtreamLiveStream(
    @Serializable(LenientIntSerializer::class) val num: Int? = null,
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(LenientLongSerializer::class) val streamId: Long? = null,
    @SerialName("stream_icon") @Serializable(LenientStringSerializer::class) val streamIcon: String? = null,
    @SerialName("epg_channel_id") @Serializable(LenientStringSerializer::class) val epgChannelId: String? = null,
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
    @SerialName("tv_archive") @Serializable(LenientBooleanSerializer::class) val tvArchive: Boolean? = null,
    @SerialName("tv_archive_duration") @Serializable(LenientIntSerializer::class) val tvArchiveDuration: Int? = null,
    @SerialName("is_adult") @Serializable(LenientBooleanSerializer::class) val isAdult: Boolean? = null,
) {
    /** Archive window in days; 0 when the stream has no archive. */
    val archiveDays: Int get() = if (tvArchive == true) (tvArchiveDuration ?: 0).coerceAtLeast(0) else 0
}

/** One entry of get_vod_streams. [tmdb] is normalised (see [XtreamValues.tmdb]). */
@Serializable
data class XtreamVodStream(
    @Serializable(LenientIntSerializer::class) val num: Int? = null,
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(LenientLongSerializer::class) val streamId: Long? = null,
    @SerialName("stream_icon") @Serializable(LenientStringSerializer::class) val streamIcon: String? = null,
    @Serializable(LenientDoubleSerializer::class) val rating: Double? = null,
    @SerialName("rating_5based") @Serializable(LenientDoubleSerializer::class) val rating5Based: Double? = null,
    @SerialName("tmdb") @Serializable(TmdbSerializer::class) val tmdbValue: String? = null,
    @SerialName("tmdb_id") @Serializable(TmdbSerializer::class) val tmdbIdValue: String? = null,
    /** Epoch seconds. */
    @Serializable(LenientLongSerializer::class) val added: Long? = null,
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
    @SerialName("container_extension") @Serializable(LenientStringSerializer::class) val containerExtension: String? = null,
    @SerialName("is_adult") @Serializable(LenientBooleanSerializer::class) val isAdult: Boolean? = null,
) {
    /** `tmdb` (list field) or `tmdb_id` (some panels), normalised; null when absent or 0. */
    val tmdb: String? get() = tmdbValue ?: tmdbIdValue
}

/** One entry of get_series. */
@Serializable
data class XtreamSeries(
    @Serializable(LenientIntSerializer::class) val num: Int? = null,
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @SerialName("series_id") @Serializable(LenientLongSerializer::class) val seriesId: Long? = null,
    @Serializable(LenientStringSerializer::class) val cover: String? = null,
    @Serializable(LenientStringSerializer::class) val plot: String? = null,
    @Serializable(LenientStringSerializer::class) val genre: String? = null,
    @SerialName("release_date") @Serializable(LenientStringSerializer::class) val releaseDateSnake: String? = null,
    @SerialName("releaseDate") @Serializable(LenientStringSerializer::class) val releaseDateCamel: String? = null,
    /** Epoch seconds. */
    @SerialName("last_modified") @Serializable(LenientLongSerializer::class) val lastModified: Long? = null,
    @Serializable(LenientDoubleSerializer::class) val rating: Double? = null,
    @SerialName("tmdb") @Serializable(TmdbSerializer::class) val tmdbValue: String? = null,
    @SerialName("tmdb_id") @Serializable(TmdbSerializer::class) val tmdbIdValue: String? = null,
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
) {
    val releaseDate: String? get() = releaseDateSnake ?: releaseDateCamel
    val tmdb: String? get() = tmdbValue ?: tmdbIdValue
}

// ---------------------------------------------------------------------------------------------
// get_series_info
// ---------------------------------------------------------------------------------------------

@Serializable
data class XtreamSeriesDetails(
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @Serializable(LenientStringSerializer::class) val cover: String? = null,
    @Serializable(LenientStringSerializer::class) val plot: String? = null,
    @Serializable(LenientStringSerializer::class) val cast: String? = null,
    @Serializable(LenientStringSerializer::class) val director: String? = null,
    @Serializable(LenientStringSerializer::class) val genre: String? = null,
    @SerialName("release_date") @Serializable(LenientStringSerializer::class) val releaseDateSnake: String? = null,
    @SerialName("releaseDate") @Serializable(LenientStringSerializer::class) val releaseDateCamel: String? = null,
    @Serializable(LenientDoubleSerializer::class) val rating: Double? = null,
    @SerialName("backdrop_path") @Serializable(LenientStringListSerializer::class) val backdropPath: List<String> = emptyList(),
    @SerialName("tmdb") @Serializable(TmdbSerializer::class) val tmdbValue: String? = null,
    @SerialName("tmdb_id") @Serializable(TmdbSerializer::class) val tmdbIdValue: String? = null,
    @SerialName("episode_run_time") @Serializable(LenientIntSerializer::class) val episodeRunTimeMinutes: Int? = null,
    @SerialName("youtube_trailer") @Serializable(LenientStringSerializer::class) val youtubeTrailer: String? = null,
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
) {
    val releaseDate: String? get() = releaseDateSnake ?: releaseDateCamel
    val tmdb: String? get() = tmdbValue ?: tmdbIdValue
}

/** `seasons[]` entry: metadata only; `episodes` is the source of truth for what exists. */
@Serializable
data class XtreamSeason(
    @SerialName("season_number") @Serializable(LenientIntSerializer::class) val seasonNumber: Int? = null,
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @SerialName("episode_count") @Serializable(LenientIntSerializer::class) val episodeCount: Int? = null,
    @SerialName("air_date") @Serializable(LenientStringSerializer::class) val airDate: String? = null,
    @Serializable(LenientStringSerializer::class) val overview: String? = null,
    @Serializable(LenientStringSerializer::class) val cover: String? = null,
    @SerialName("cover_big") @Serializable(LenientStringSerializer::class) val coverBig: String? = null,
)

@Serializable
data class XtreamEpisodeDetails(
    @SerialName("duration_secs") @Serializable(LenientLongSerializer::class) val durationSecs: Long? = null,
    @Serializable(LenientStringSerializer::class) val duration: String? = null,
    @SerialName("movie_image") @Serializable(LenientStringSerializer::class) val movieImage: String? = null,
    @Serializable(LenientStringSerializer::class) val plot: String? = null,
    @Serializable(LenientDoubleSerializer::class) val rating: Double? = null,
    @SerialName("air_date") @Serializable(LenientStringSerializer::class) val airDate: String? = null,
)

internal object EpisodeDetailsObject : LenientObjectSerializer<XtreamEpisodeDetails>(XtreamEpisodeDetails.serializer())

/**
 * One episode. In [XtreamSeriesInfo.episodes] the [season] is the numeric value of the
 * `episodes` key it was listed under (falling back to the episode's own `season` field).
 * [id] is the episode's stream id, used to build the `/series/...` URL at play time.
 */
@Serializable
data class XtreamEpisode(
    @Serializable(LenientStringSerializer::class) val id: String? = null,
    @SerialName("episode_num") @Serializable(LenientIntSerializer::class) val episodeNum: Int? = null,
    @Serializable(LenientIntSerializer::class) val season: Int? = null,
    @Serializable(LenientStringSerializer::class) val title: String? = null,
    @SerialName("container_extension") @Serializable(LenientStringSerializer::class) val containerExtension: String? = null,
    /** Epoch seconds. */
    @Serializable(LenientLongSerializer::class) val added: Long? = null,
    @Serializable(EpisodeDetailsObject::class) val info: XtreamEpisodeDetails? = null,
)

/** Decoded get_series_info. [episodes] are flattened and sorted by season, then episode number. */
data class XtreamSeriesInfo(
    val info: XtreamSeriesDetails,
    val seasons: List<XtreamSeason>,
    val episodes: List<XtreamEpisode>,
)

// ---------------------------------------------------------------------------------------------
// get_vod_info
// ---------------------------------------------------------------------------------------------

@Serializable
data class XtreamVodDetails(
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @SerialName("o_name") @Serializable(LenientStringSerializer::class) val originalName: String? = null,
    @SerialName("cover_big") @Serializable(LenientStringSerializer::class) val coverBig: String? = null,
    @SerialName("movie_image") @Serializable(LenientStringSerializer::class) val movieImage: String? = null,
    @SerialName("releasedate") @Serializable(LenientStringSerializer::class) val releaseDate: String? = null,
    @SerialName("duration_secs") @Serializable(LenientLongSerializer::class) val durationSecs: Long? = null,
    @Serializable(LenientStringSerializer::class) val duration: String? = null,
    @Serializable(LenientStringSerializer::class) val plot: String? = null,
    @Serializable(LenientStringSerializer::class) val description: String? = null,
    @Serializable(LenientStringSerializer::class) val cast: String? = null,
    @Serializable(LenientStringSerializer::class) val actors: String? = null,
    @Serializable(LenientStringSerializer::class) val director: String? = null,
    @Serializable(LenientStringSerializer::class) val genre: String? = null,
    @Serializable(LenientStringSerializer::class) val country: String? = null,
    @Serializable(LenientDoubleSerializer::class) val rating: Double? = null,
    @SerialName("backdrop_path") @Serializable(LenientStringListSerializer::class) val backdropPath: List<String> = emptyList(),
    @SerialName("tmdb_id") @Serializable(TmdbSerializer::class) val tmdbId: String? = null,
    @SerialName("youtube_trailer") @Serializable(LenientStringSerializer::class) val youtubeTrailer: String? = null,
) {
    val effectivePlot: String? get() = plot ?: description
    val effectiveCast: String? get() = cast ?: actors
    val effectiveCover: String? get() = coverBig ?: movieImage
}

@Serializable
data class XtreamMovieData(
    @SerialName("stream_id") @Serializable(LenientLongSerializer::class) val streamId: Long? = null,
    @Serializable(LenientStringSerializer::class) val name: String? = null,
    @SerialName("container_extension") @Serializable(LenientStringSerializer::class) val containerExtension: String? = null,
    @SerialName("category_id") @Serializable(LenientStringSerializer::class) val categoryId: String? = null,
    @Serializable(LenientLongSerializer::class) val added: Long? = null,
)

/** Decoded get_vod_info. Note: it does not fill tmdb gaps (provider spike §5). */
@Serializable
data class XtreamVodInfo(
    @Serializable(VodDetailsObject::class) val info: XtreamVodDetails? = null,
    @SerialName("movie_data") @Serializable(MovieDataObject::class) val movieData: XtreamMovieData? = null,
)

internal object VodDetailsObject : LenientObjectSerializer<XtreamVodDetails>(XtreamVodDetails.serializer())
internal object MovieDataObject : LenientObjectSerializer<XtreamMovieData>(XtreamMovieData.serializer())

// ---------------------------------------------------------------------------------------------
// get_short_epg / get_simple_data_table (mapped to :core:parsing XtreamEpgListing)
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class EpgListingDto(
    @Serializable(LenientStringSerializer::class) val id: String? = null,
    @SerialName("epg_id") @Serializable(LenientStringSerializer::class) val epgId: String? = null,
    @SerialName("channel_id") @Serializable(LenientStringSerializer::class) val channelId: String? = null,
    @SerialName("stream_id") @Serializable(LenientStringSerializer::class) val streamId: String? = null,
    @Serializable(LenientStringSerializer::class) val title: String? = null,
    @Serializable(LenientStringSerializer::class) val description: String? = null,
    @Serializable(LenientStringSerializer::class) val lang: String? = null,
    @Serializable(LenientStringSerializer::class) val start: String? = null,
    @Serializable(LenientStringSerializer::class) val end: String? = null,
    @SerialName("start_timestamp") @Serializable(LenientStringSerializer::class) val startTimestamp: String? = null,
    @SerialName("stop_timestamp") @Serializable(LenientStringSerializer::class) val stopTimestamp: String? = null,
    @SerialName("now_playing") @Serializable(LenientStringSerializer::class) val nowPlaying: String? = null,
    @SerialName("has_archive") @Serializable(LenientStringSerializer::class) val hasArchive: String? = null,
)

internal object EpgListingList : LenientListSerializer<EpgListingDto>(EpgListingDto.serializer())

@Serializable
internal data class EpgResponseDto(
    @SerialName("epg_listings") @Serializable(EpgListingList::class) val listings: List<EpgListingDto> = emptyList(),
)

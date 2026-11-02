package app.playlistplayer.feature.vod

import java.util.Locale
import java.util.MissingResourceException

/**
 * One audio or text track as the player reports it, without Media3 types (the UI layer's
 * `Media3Tracks` builds these from `Tracks`), so labelling is plain Kotlin and JVM-testable.
 *
 * @property channelCount audio channels, or a value ≤ 0 when unknown.
 * @property sampleMimeType e.g. `audio/eac3`, `application/cea-608`; [codecs] e.g. `ec-3` (HLS).
 * @property forced subtitle shown only for foreign-language parts (Media3 `SELECTION_FLAG_FORCED`).
 * @property sdh subtitles for the deaf and hard of hearing (`ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND`).
 */
data class RawTrack(
    val id: String,
    val type: TrackType,
    val language: String?,
    val label: String? = null,
    val channelCount: Int = 0,
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    val forced: Boolean = false,
    val sdh: Boolean = false,
    val selected: Boolean = false,
)

/** Language tags of tracks and of the track-language preferences. */
object TrackLanguage {
    private val UNDETERMINED = setOf("und", "zxx", "mul", "mis", "unknown")

    /** ISO 639-2/B codes that differ from the 639-2/T codes `Locale` knows. */
    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
        "tib" to "bo", "wel" to "cy",
    )

    /** Legacy two-letter codes some platforms still report. */
    private val LEGACY = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    private val iso3ToIso2: Map<String, String> by lazy {
        buildMap {
            for (code in Locale.getISOLanguages()) {
                val iso3 = try {
                    Locale.forLanguageTag(code).isO3Language
                } catch (_: MissingResourceException) {
                    continue
                }
                if (iso3.isNotEmpty()) put(iso3, LEGACY[code] ?: code)
            }
        }
    }

    /**
     * Lower-case BCP 47 tag with a two-letter primary subtag where one exists (`eng` → `en`,
     * `fre` → `fr`, `pt_BR` → `pt-br`), or null for missing / undetermined languages (`und`).
     */
    fun normalize(tag: String?): String? {
        val t = tag?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val parts = t.split('-')
        val primary = parts.first()
        if (primary.isEmpty() || primary in UNDETERMINED) return null
        val mapped = when (primary.length) {
            3 -> BIBLIOGRAPHIC[primary] ?: iso3ToIso2[primary] ?: primary
            else -> LEGACY[primary] ?: primary
        }
        return (listOf(mapped) + parts.drop(1)).joinToString("-")
    }

    /** Same primary language (`en-US` matches `en`). Unknown languages never match. */
    fun matches(trackLanguage: String?, preferred: String?): Boolean {
        val a = normalize(trackLanguage) ?: return false
        val b = normalize(preferred) ?: return false
        return a.substringBefore('-') == b.substringBefore('-')
    }

    /**
     * Display name in [displayLocale] ("English", "Portuguese (Brazil)"), or null when the tag is
     * missing, undetermined or not a language the platform can name.
     */
    fun displayName(tag: String?, displayLocale: Locale = Locale.getDefault()): String? {
        val norm = normalize(tag) ?: return null
        val locale = Locale.forLanguageTag(norm)
        val language = locale.getDisplayLanguage(displayLocale)
        if (language.isBlank() || language.equals(locale.language, ignoreCase = true)) return null
        val name = language.replaceFirstChar { it.titlecase(displayLocale) }
        val country = locale.getDisplayCountry(displayLocale).takeIf { locale.country.isNotEmpty() && it.isNotBlank() }
        return if (country != null) "$name ($country)" else name
    }
}

/**
 * Builds [TrackOption] labels: language display name plus hints, joined with " · ":
 * - audio: channel layout (Mono, Stereo, 5.1, 7.1) and surround codecs (Dolby Digital, DTS, …),
 *   e.g. "English · 5.1 · Dolby Digital+", "French · Stereo";
 * - text: "Forced", "SDH", "CC" (CEA-608/708);
 * - the stream's own track name when it adds something ("English · Commentary · Stereo");
 * - unknown or `und` language: the stream's name, else "Audio 2" / "Subtitles 1" (position
 *   within its type, 1-based);
 * - identical labels within a type get " (2)", " (3)", ….
 */
object TrackLabeler {
    const val SEPARATOR = " · "

    fun options(tracks: List<RawTrack>, displayLocale: Locale = Locale.getDefault()): List<TrackOption> {
        val counters = mutableMapOf<TrackType, Int>()
        val labelled = tracks.map { raw ->
            val position = (counters[raw.type] ?: 0) + 1
            counters[raw.type] = position
            raw to label(raw, position, displayLocale)
        }
        val seen = mutableMapOf<Pair<TrackType, String>, Int>()
        return labelled.map { (raw, label) ->
            val key = raw.type to label
            val count = (seen[key] ?: 0) + 1
            seen[key] = count
            TrackOption(
                id = raw.id,
                type = raw.type,
                label = if (count > 1) "$label ($count)" else label,
                language = TrackLanguage.normalize(raw.language),
                selected = raw.selected,
                forced = raw.forced,
            )
        }
    }

    private fun label(raw: RawTrack, position: Int, displayLocale: Locale): String {
        val language = TrackLanguage.displayName(raw.language, displayLocale)
        val name = raw.label?.trim()?.takeIf { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        when {
            language != null -> {
                parts += language
                if (name != null && addsInformation(name, language, raw.language)) parts += name
            }
            name != null -> parts += name
            else -> parts += "${if (raw.type == TrackType.AUDIO) AUDIO else SUBTITLES} $position"
        }
        parts += hints(raw).filter { hint -> parts.none { it.contains(hint, ignoreCase = true) } }
        return parts.joinToString(SEPARATOR)
    }

    /** False when the stream's name only repeats the language ("English", "ENG", "English 5.1"). */
    private fun addsInformation(name: String, language: String, tag: String?): Boolean {
        if (name.contains(language, ignoreCase = true)) return false
        val code = tag?.trim()?.lowercase(Locale.ROOT)
        val n = name.lowercase(Locale.ROOT)
        return n != code && n != TrackLanguage.normalize(tag)
    }

    private fun hints(raw: RawTrack): List<String> = when (raw.type) {
        TrackType.AUDIO -> listOfNotNull(channels(raw.channelCount), surroundCodec(raw.sampleMimeType, raw.codecs))
        TrackType.TEXT -> listOfNotNull(
            FORCED.takeIf { raw.forced },
            SDH.takeIf { raw.sdh },
            CC.takeIf { raw.sampleMimeType == "application/cea-608" || raw.sampleMimeType == "application/cea-708" },
        )
    }

    private fun channels(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "Mono"
        count == 2 -> "Stereo"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count ch"
    }

    /** Only codecs worth telling apart; plain AAC / MP3 / Opus stay unlabelled. */
    private fun surroundCodec(mime: String?, codecs: String?): String? {
        val m = mime?.lowercase(Locale.ROOT)
        val c = codecs?.lowercase(Locale.ROOT)
        return when {
            m == "audio/eac3-joc" || c?.startsWith("ec+3") == true -> "Dolby Atmos"
            m == "audio/eac3" || c?.startsWith("ec-3") == true -> "Dolby Digital+"
            m == "audio/ac3" || c?.startsWith("ac-3") == true -> "Dolby Digital"
            m == "audio/ac4" || c?.startsWith("ac-4") == true -> "Dolby AC-4"
            m == "audio/true-hd" -> "Dolby TrueHD"
            m == "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
            m?.startsWith("audio/vnd.dts.hd") == true || c?.startsWith("dtsh") == true -> "DTS-HD"
            m == "audio/vnd.dts" || c?.startsWith("dtsc") == true -> "DTS"
            else -> null
        }
    }

    private const val AUDIO = "Audio"
    private const val SUBTITLES = "Subtitles"
    private const val FORCED = "Forced"
    private const val SDH = "SDH"
    private const val CC = "CC"
}

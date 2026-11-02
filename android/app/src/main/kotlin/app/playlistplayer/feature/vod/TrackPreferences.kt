package app.playlistplayer.feature.vod

import app.playlistplayer.feature.settings.PreferencesRepository
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Audio and subtitle language preference for on-demand playback (R40).
 *
 * @property audioLanguage preferred audio language tag; null = the device language.
 * @property subtitleLanguage preferred subtitle language tag; null = subtitles off.
 * @property deviceLanguage the device's language, tried after [audioLanguage]; English is the last fallback.
 */
data class TrackPreference(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val deviceLanguage: String = DEFAULT_LANGUAGE,
) {
    /** Audio languages to try, best first (for Media3 `setPreferredAudioLanguages`). */
    val audioLanguages: List<String>
        get() = listOf(audioLanguage, deviceLanguage, DEFAULT_LANGUAGE).mapNotNull(TrackLanguage::normalize).distinct()

    /** Subtitle languages to try (for Media3 `setPreferredTextLanguages`); empty when off. */
    val subtitleLanguages: List<String>
        get() = listOfNotNull(TrackLanguage.normalize(subtitleLanguage))

    val subtitlesOff: Boolean get() = subtitleLanguages.isEmpty()

    /** After the user picks [track] for audio: its language (when known) becomes the preference. */
    fun withAudioChoice(track: TrackOption): TrackPreference =
        TrackLanguage.normalize(track.language)?.let { copy(audioLanguage = it) } ?: this

    /** After the user picks a subtitle ([track] null = Off). An unknown-language track keeps the preference. */
    fun withSubtitleChoice(track: TrackOption?): TrackPreference = when (track) {
        null -> copy(subtitleLanguage = null)
        else -> TrackLanguage.normalize(track.language)?.let { copy(subtitleLanguage = it) } ?: this
    }

    companion object {
        const val DEFAULT_LANGUAGE = "en"
    }
}

/**
 * Picks the tracks that match a [TrackPreference]:
 * - audio: the first of [TrackPreference.audioLanguages] that the stream has (the player's
 *   current track when it is in that language, else the first such track); without a match the
 *   player's selection, else the first track;
 * - subtitles: off when the preference is off or the language is absent; otherwise the player's
 *   current track in that language, else a full (non-forced) track, else any track in it.
 */
object TrackPreferenceResolver {

    fun audio(options: List<TrackOption>, preference: TrackPreference): String? {
        if (options.isEmpty()) return null
        for (language in preference.audioLanguages) {
            val matching = options.filter { TrackLanguage.matches(it.language, language) }
            if (matching.isNotEmpty()) return (matching.firstOrNull { it.selected } ?: matching.first()).id
        }
        return (options.firstOrNull { it.selected } ?: options.first()).id
    }

    fun subtitle(options: List<TrackOption>, preference: TrackPreference): String? {
        val language = preference.subtitleLanguages.firstOrNull() ?: return null
        val matching = options.filter { TrackLanguage.matches(it.language, language) }
        if (matching.isEmpty()) return null
        return (matching.firstOrNull { it.selected } ?: matching.firstOrNull { !it.forced } ?: matching.first()).id
    }
}

/** Where the track-language preference lives (Settings → Playback; updated by manual choices). */
interface TrackPreferenceStore {
    suspend fun current(): TrackPreference
    suspend fun save(preference: TrackPreference)
}

/** [TrackPreferenceStore.current], or the defaults when the store cannot be read (playback must still start). */
suspend fun TrackPreferenceStore.currentOrDefault(): TrackPreference = try {
    current()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    TrackPreference()
}

/** [TrackPreferenceStore.save]; a failed write only loses the remembered choice. */
suspend fun TrackPreferenceStore.saveQuietly(preference: TrackPreference) {
    try {
        save(preference)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Not fatal: the choice still applies to this playback session.
    }
}

/** In-memory [TrackPreferenceStore] (previews and tests). */
class InMemoryTrackPreferenceStore(var preference: TrackPreference = TrackPreference()) : TrackPreferenceStore {
    override suspend fun current(): TrackPreference = preference

    override suspend fun save(preference: TrackPreference) {
        this.preference = preference
    }
}

/**
 * [TrackPreferenceStore] over the app preferences (`PlaybackPreferences.audioLanguage` /
 * `subtitleLanguage`), so Settings and in-player choices share one persisted value.
 */
class PreferencesTrackPreferenceStore(
    private val preferences: PreferencesRepository,
    private val deviceLanguage: () -> String = { Locale.getDefault().language },
) : TrackPreferenceStore {
    override suspend fun current(): TrackPreference {
        val playback = preferences.current().playback
        val device = TrackLanguage.normalize(deviceLanguage()) ?: TrackPreference.DEFAULT_LANGUAGE
        return TrackPreference(playback.audioLanguage, playback.subtitleLanguage, device)
    }

    override suspend fun save(preference: TrackPreference) {
        preferences.setTrackLanguages(preference.audioLanguage, preference.subtitleLanguage)
    }
}

/** Track languages offered by Settings → Playback (ISO 639-1 codes; labels from the platform). */
object TrackLanguageCatalog {
    val codes: List<String> = listOf(
        "en", "fr", "de", "es", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "cs", "hu", "ro", "el",
        "tr", "ru", "uk", "ar", "he", "fa", "hi", "ur", "bn", "pa", "ta", "te", "ml", "kn", "mr", "gu",
        "zh", "ja", "ko", "th", "vi", "id",
    )

    /** Display name of [code] (falls back to the upper-case code). */
    fun label(code: String, displayLocale: Locale = Locale.getDefault()): String =
        TrackLanguage.displayName(code, displayLocale) ?: code.uppercase(Locale.ROOT)
}

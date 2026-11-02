package app.playlistplayer.feature.settings

import app.playlistplayer.core.player.catchup.CatchupUrlStyle
import app.playlistplayer.core.player.url.LiveFormatPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * Durable string-map storage for [PreferencesRepository] (the [PreferencesCodec] map).
 * Production binds [SecretSlotPreferencesStorage] (a slot of the Preferences DataStore behind
 * `SecureStore`); tests may use an in-memory map.
 */
interface PreferencesStorage {
    suspend fun read(): Map<String, String>
    suspend fun write(values: Map<String, String>)
    suspend fun clear()
}

/**
 * Stores the encoded preferences as one record in a [SecretSlotStore] slot, i.e. in the
 * Preferences DataStore file behind `SecureStore` (`:app` has no direct DataStore dependency).
 * An unreadable or corrupt record reads as empty, which [PreferencesCodec] turns into defaults:
 * preferences are not security-relevant, so failing to defaults is correct here. "Reset app"
 * (`SecureStore.clear`) wipes them with the other app state.
 *
 * Record format: `v1` header, then one `key=base64url(value)` line per entry.
 */
class SecretSlotPreferencesStorage(
    private val slots: SecretSlotStore,
    private val slotName: String = SLOT,
) : PreferencesStorage {
    override suspend fun read(): Map<String, String> {
        val text = (slots.read(slotName) as? SlotRead.Present)?.value ?: return emptyMap()
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return emptyMap()
        val decoder = Base64.getUrlDecoder()
        return buildMap {
            for (line in lines.drop(1)) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val value = try {
                    String(decoder.decode(line.substring(eq + 1)), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                put(line.substring(0, eq), value)
            }
        }
    }

    override suspend fun write(values: Map<String, String>) {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val text = buildString {
            append(HEADER)
            values.toSortedMap().forEach { (key, value) ->
                require('=' !in key && '\n' !in key) { "invalid preference key" }
                append('\n').append(key).append('=').append(encoder.encodeToString(value.toByteArray(Charsets.UTF_8)))
            }
        }
        slots.put(slotName, text)
    }

    override suspend fun clear() = slots.remove(slotName)

    companion object {
        const val SLOT = "app.preferences"
        private const val HEADER = "v1"
    }
}

/**
 * App preferences (U12): content (language list, quality), playback (live format, frame-rate
 * matching, tunneling, catch-up offset/style, audio/subtitle language) and sync (refresh
 * interval, stale warning).
 *
 * Reads are lenient ([PreferencesCodec.decode]); every update validates its input: numbers are
 * clamped into their range (the applied value is returned), language tags are normalised and
 * invalid ones rejected. Updates are serialised and written through to [storage] before
 * [preferences] emits, so a new instance over the same storage sees them (persist across restart).
 */
class PreferencesRepository(private val storage: PreferencesStorage) {
    private val mutex = Mutex()
    private val cache = MutableStateFlow<AppPreferences?>(null)

    /** Current preferences; loads from storage on first collection. */
    val preferences: Flow<AppPreferences> = flow {
        current()
        emitAll(cache.filterNotNull())
    }

    /** The cached value, or null before the first [load] / [current]. */
    val snapshot: StateFlow<AppPreferences?> = cache.asStateFlow()

    /** Loads (once) and returns the current preferences. */
    suspend fun current(): AppPreferences = cache.value ?: load()

    /** (Re)reads storage. */
    suspend fun load(): AppPreferences = mutex.withLock {
        cache.value ?: PreferencesCodec.decode(storage.read()).also { cache.value = it }
    }

    // ---- content ----

    /** Replaces the language list (normalised, de-duplicated). False when no valid tag remains. */
    suspend fun setLanguages(languages: List<String>): Boolean {
        val normalized = languages.map(ContentPreferences::normalizeLanguage).distinct()
        if (normalized.isEmpty() || !normalized.all { ContentPreferences.LANGUAGE.matches(it) }) return false
        update { it.copy(content = it.content.copy(languages = normalized)) }
        return true
    }

    /** Adds a language at the end. False for an invalid tag. */
    suspend fun addLanguage(tag: String): Boolean {
        val code = ContentPreferences.normalizeLanguage(tag)
        if (!ContentPreferences.LANGUAGE.matches(code)) return false
        update { it.copy(content = it.content.addLanguage(code)) }
        return true
    }

    /** Removes a language; the last one stays. */
    suspend fun removeLanguage(tag: String) = update { it.copy(content = it.content.removeLanguage(tag)) }

    /** Moves the language at [from] to [to]; out-of-range indices are ignored (returns false). */
    suspend fun moveLanguage(from: Int, to: Int): Boolean {
        var moved = false
        update { prefs ->
            val list = prefs.content.languages
            if (from in list.indices && to in list.indices) {
                moved = true
                prefs.copy(content = prefs.content.moveLanguage(from, to))
            } else {
                prefs
            }
        }
        return moved
    }

    suspend fun setQuality(quality: QualityPreference) = update { it.copy(content = it.content.copy(quality = quality)) }

    // ---- playback ----

    suspend fun setLiveFormat(format: LiveFormatPreference) = update { it.copy(playback = it.playback.copy(liveFormat = format)) }

    suspend fun setMatchFrameRate(enabled: Boolean) = update { it.copy(playback = it.playback.copy(matchFrameRate = enabled)) }

    suspend fun setTunneling(enabled: Boolean) = update { it.copy(playback = it.playback.copy(tunneling = enabled)) }

    /** Clamped into [PlaybackPreferences.CATCHUP_OFFSET_RANGE]; returns the applied value. */
    suspend fun setCatchupOffsetHours(hours: Int): Int {
        val applied = hours.coerceIn(PlaybackPreferences.CATCHUP_OFFSET_RANGE)
        update { it.copy(playback = it.playback.copy(catchupOffsetHours = applied)) }
        return applied
    }

    suspend fun setCatchupUrlStyle(style: CatchupUrlStyle) = update { it.copy(playback = it.playback.copy(catchupUrlStyle = style)) }

    /** Preferred audio language of movies and episodes; null = device language. False for an invalid tag. */
    suspend fun setAudioLanguage(tag: String?): Boolean {
        val code = validLanguageOrNull(tag) ?: return false
        update { it.copy(playback = it.playback.copy(audioLanguage = code.value)) }
        return true
    }

    /** Preferred subtitle language of movies and episodes; null = subtitles off. False for an invalid tag. */
    suspend fun setSubtitleLanguage(tag: String?): Boolean {
        val code = validLanguageOrNull(tag) ?: return false
        update { it.copy(playback = it.playback.copy(subtitleLanguage = code.value)) }
        return true
    }

    /** Both track languages at once (an in-player choice). False, and nothing stored, when a tag is invalid. */
    suspend fun setTrackLanguages(audio: String?, subtitle: String?): Boolean {
        val a = validLanguageOrNull(audio) ?: return false
        val s = validLanguageOrNull(subtitle) ?: return false
        update { it.copy(playback = it.playback.copy(audioLanguage = a.value, subtitleLanguage = s.value)) }
        return true
    }

    /** Wrapped so "valid null" (cleared) differs from "invalid" (Kotlin null). */
    private class Language(val value: String?)

    private fun validLanguageOrNull(tag: String?): Language? {
        if (tag == null) return Language(null)
        val code = ContentPreferences.normalizeLanguage(tag)
        return if (ContentPreferences.LANGUAGE.matches(code)) Language(code) else null
    }

    // ---- sync ----

    /** Clamped into [SyncPreferences.REFRESH_RANGE]; returns the applied value. */
    suspend fun setRefreshIntervalHours(hours: Int): Int {
        val applied = hours.coerceIn(SyncPreferences.REFRESH_RANGE)
        update { it.copy(sync = it.sync.copy(refreshIntervalHours = applied)) }
        return applied
    }

    /** Clamped into [SyncPreferences.STALE_RANGE]; returns the applied value. */
    suspend fun setStaleWarningDays(days: Int): Int {
        val applied = days.coerceIn(SyncPreferences.STALE_RANGE)
        update { it.copy(sync = it.sync.copy(staleWarningDays = applied)) }
        return applied
    }

    /** Back to defaults and removes the stored record ("Reset app"). */
    suspend fun reset() = mutex.withLock {
        storage.clear()
        cache.value = AppPreferences()
    }

    /** Applies [transform] to the current value, persists it, then publishes it. */
    suspend fun update(transform: (AppPreferences) -> AppPreferences): AppPreferences = mutex.withLock {
        val current = cache.value ?: PreferencesCodec.decode(storage.read())
        val next = transform(current)
        if (next != current || cache.value == null) {
            if (next != current) storage.write(PreferencesCodec.encode(next))
            cache.value = next
        }
        next
    }
}

/** In-memory [PreferencesStorage] (previews and tests). */
class InMemoryPreferencesStorage(initial: Map<String, String> = emptyMap()) : PreferencesStorage {
    @Volatile var values: Map<String, String> = initial
        private set

    override suspend fun read(): Map<String, String> = values

    override suspend fun write(values: Map<String, String>) {
        this.values = values.toMap()
    }

    override suspend fun clear() {
        values = emptyMap()
    }
}

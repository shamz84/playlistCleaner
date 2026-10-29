package app.playlistplayer.feature.settings

/**
 * Encrypted string slots that survive app restarts. The settings feature depends only on this
 * interface; the app binds it to the generic slots of `:core:sync` `SecureStore`
 * (`getString` / `putString` / `remove`), which are AES-GCM encrypted with a Keystore key.
 *
 * Slot names used by this package all start with `parental.` so they never collide with the
 * per-playlist `playlist.<id>.` slots.
 */
interface SecretSlotStore {
    suspend fun get(name: String): String?
    suspend fun put(name: String, value: String)
    suspend fun remove(name: String)
}

/** Wall-clock source, injectable so lockout timing is testable. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

package app.playlistplayer.feature.settings

/**
 * Encrypted string slots that survive app restarts. The settings feature depends only on this
 * interface; the app binds it to the generic slots of `:core:sync` `SecureStore` with
 * [SecureStoreSecretSlots] (AES-GCM encrypted with a Keystore key).
 *
 * Slot names used by this package all start with `parental.` or `app.` so they never collide
 * with the per-playlist `playlist.<id>.` slots.
 */
interface SecretSlotStore {
    /** The value, or null when the slot is missing or unreadable. Prefer [read] for security decisions. */
    suspend fun get(name: String): String? = (read(name) as? SlotRead.Present)?.value

    /**
     * Reads a slot and tells "never written" from "present but undecryptable" (Keystore key
     * invalidated, corrupt storage), so callers can fail closed on [SlotRead.Unreadable].
     */
    suspend fun read(name: String): SlotRead

    suspend fun put(name: String, value: String)
    suspend fun remove(name: String)
}

/** Result of [SecretSlotStore.read]. */
sealed interface SlotRead {
    data object Missing : SlotRead

    /** Present but cannot be decrypted or decoded. */
    data object Unreadable : SlotRead

    /** [toString] never prints the value. */
    class Present(val value: String) : SlotRead {
        override fun equals(other: Any?): Boolean = other is Present && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Present(<redacted>)"
    }
}

/** Wall-clock source, injectable so lockout timing is testable. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

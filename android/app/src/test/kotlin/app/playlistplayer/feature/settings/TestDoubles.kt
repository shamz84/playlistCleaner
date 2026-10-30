package app.playlistplayer.feature.settings

class InMemorySecretSlotStore : SecretSlotStore {
    val slots = mutableMapOf<String, String>()

    /** Slots that are present but read as undecryptable (e.g. an invalidated Keystore key). */
    val unreadable = mutableSetOf<String>()

    override suspend fun read(name: String): SlotRead = when {
        name in unreadable -> SlotRead.Unreadable
        else -> slots[name]?.let { SlotRead.Present(it) } ?: SlotRead.Missing
    }

    override suspend fun put(name: String, value: String) {
        unreadable.remove(name)
        slots[name] = value
    }

    override suspend fun remove(name: String) {
        unreadable.remove(name)
        slots.remove(name)
    }
}

class FakeClock(var now: Long = 1_800_000_000_000L) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

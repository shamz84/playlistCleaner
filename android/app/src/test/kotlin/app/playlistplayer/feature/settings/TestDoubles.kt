package app.playlistplayer.feature.settings

class InMemorySecretSlotStore : SecretSlotStore {
    val slots = mutableMapOf<String, String>()

    override suspend fun get(name: String): String? = slots[name]

    override suspend fun put(name: String, value: String) {
        slots[name] = value
    }

    override suspend fun remove(name: String) {
        slots.remove(name)
    }
}

class FakeClock(var now: Long = 1_800_000_000_000L) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

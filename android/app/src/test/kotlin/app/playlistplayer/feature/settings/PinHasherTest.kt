package app.playlistplayer.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinHasherTest {

    private val hasher = PinHasher(iterations = 1_000)

    @Test
    fun `hash verifies the same pin and rejects others`() {
        val encoded = hasher.hash("1234")
        assertThat(hasher.verify("1234", encoded)).isTrue()
        assertThat(hasher.verify("1235", encoded)).isFalse()
        assertThat(hasher.verify("", encoded)).isFalse()
    }

    @Test
    fun `encoding is versioned with iterations salt and hash`() {
        val parts = hasher.hash("1234").split("$")
        assertThat(parts).hasSize(4)
        assertThat(parts[0]).isEqualTo("v1")
        assertThat(parts[1]).isEqualTo("1000")
        assertThat(java.util.Base64.getDecoder().decode(parts[2])).hasLength(PinHasher.SALT_BYTES)
        assertThat(java.util.Base64.getDecoder().decode(parts[3])).hasLength(PinHasher.HASH_BYTES)
    }

    @Test
    fun `salt is random so equal pins hash differently`() {
        assertThat(hasher.hash("1234")).isNotEqualTo(hasher.hash("1234"))
    }

    @Test
    fun `iterations travel with the hash and weaker hashes need rehash`() {
        val old = PinHasher(iterations = 500).hash("4321")
        assertThat(hasher.verify("4321", old)).isTrue()
        assertThat(hasher.needsRehash(old)).isTrue()
        assertThat(hasher.needsRehash(hasher.hash("4321"))).isFalse()
    }

    @Test
    fun `malformed or tampered encodings never verify`() {
        val good = hasher.hash("1234")
        val parts = good.split("$")
        listOf(
            "",
            "garbage",
            "v2$1000$${parts[2]}$${parts[3]}",
            "v1$0$${parts[2]}$${parts[3]}",
            "v1$999999999$${parts[2]}$${parts[3]}",
            "v1$1000$!!!$${parts[3]}",
            "v1$1000$${parts[2]}",
        ).forEach { assertThat(hasher.verify("1234", it)).isFalse() }
    }

    @Test
    fun `default iteration count is the documented value`() {
        val encoded = PinHasher().hash("0000")
        assertThat(encoded).startsWith("v1$${PinHasher.DEFAULT_ITERATIONS}$")
        assertThat(PinHasher().verify("0000", encoded)).isTrue()
    }
}

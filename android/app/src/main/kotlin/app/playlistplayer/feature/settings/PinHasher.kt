package app.playlistplayer.feature.settings

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 hashing for the parental PIN.
 *
 * Encoded form: `v1$<iterations>$<base64 salt>$<base64 hash>` (standard Base64, no padding).
 * The iteration count travels with the hash, so [DEFAULT_ITERATIONS] can be raised later and
 * old hashes still verify ([needsRehash] tells the caller to re-hash after a successful check).
 *
 * Iteration count: [DEFAULT_ITERATIONS] = 120 000. On Android, PBKDF2WithHmacSHA256 runs in
 * Conscrypt/BoringSSL natively; one iteration is two SHA-256 compressions, roughly 1–2 µs on
 * the Cortex-A53/A55 cores of Fire TV sticks, so a verify costs about 120–250 ms, inside the
 * < 300 ms budget for a D-pad PIN entry. A 4–8 digit PIN has at most 10^8 values, so the hash
 * cost is defence in depth only: the hash lives in a Keystore-encrypted slot, and the online
 * defence is the persisted escalating lockout in [ParentalControl].
 *
 * Comparison uses [MessageDigest.isEqual], which is constant-time for equal-length inputs.
 */
class PinHasher(
    val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
    }

    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(pin, salt, iterations, HASH_BYTES)
        return listOf(VERSION, iterations.toString(), b64(salt), b64(derived)).joinToString(SEPARATOR)
    }

    /** True when [pin] matches [encoded]. A malformed [encoded] value never matches. */
    fun verify(pin: String, encoded: String): Boolean {
        val parsed = parse(encoded) ?: return false
        val derived = try {
            derive(pin, parsed.salt, parsed.iterations, parsed.hash.size)
        } catch (_: GeneralSecurityException) {
            return false
        }
        return MessageDigest.isEqual(derived, parsed.hash)
    }

    /** True when [encoded] was produced with weaker parameters than this hasher's. */
    fun needsRehash(encoded: String): Boolean {
        val parsed = parse(encoded) ?: return true
        return parsed.iterations < iterations || parsed.salt.size < SALT_BYTES
    }

    private class Parsed(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(encoded: String): Parsed? {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != VERSION) return null
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return null
        val decoder = Base64.getDecoder()
        val salt = try { decoder.decode(parts[2]) } catch (_: IllegalArgumentException) { return null }
        val hash = try { decoder.decode(parts[3]) } catch (_: IllegalArgumentException) { return null }
        if (salt.size < MIN_SALT_BYTES || hash.size !in MIN_HASH_BYTES..MAX_HASH_BYTES) return null
        return Parsed(iterations, salt, hash)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, lengthBytes * 8)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        const val DEFAULT_ITERATIONS = 120_000
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val VERSION = "v1"
        private const val SEPARATOR = "$"
        private const val MIN_SALT_BYTES = 8
        private const val MIN_HASH_BYTES = 16
        private const val MAX_HASH_BYTES = 64

        /** Guards against a tampered slot forcing an unbounded hash computation. */
        const val MAX_ITERATIONS = 5_000_000
    }
}

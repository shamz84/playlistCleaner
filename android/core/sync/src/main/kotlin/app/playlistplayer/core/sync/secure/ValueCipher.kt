package app.playlistplayer.core.sync.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated encryption of small secret values. [aad] binds a ciphertext to its storage key. */
interface ValueCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** @throws GeneralSecurityException when the blob was tampered with or the key changed. */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a random 12-byte IV per value. Blob layout: `[version=1][ivLength][iv][ciphertext+tag]`.
 * The key comes from [keyProvider]: the Android Keystore in production ([AndroidKeystoreKeys]),
 * a software key in JVM tests.
 */
class AesGcmValueCipher(private val keyProvider: () -> SecretKey) : ValueCipher {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore generates the IV itself (randomized encryption is enforced).
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plaintext)
        return ByteArray(2 + iv.size + sealed.size).also {
            it[0] = FORMAT_VERSION
            it[1] = iv.size.toByte()
            iv.copyInto(it, 2)
            sealed.copyInto(it, 2 + iv.size)
        }
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 2 || blob[0] != FORMAT_VERSION) throw GeneralSecurityException("Unknown secure value format")
        val ivLength = blob[1].toInt() and 0xFF
        if (ivLength == 0 || blob.size < 2 + ivLength + TAG_BYTES) throw GeneralSecurityException("Truncated secure value")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BYTES * 8, blob, 2, ivLength))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, 2 + ivLength, blob.size - 2 - ivLength)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val TAG_BYTES = 16
    }
}

/**
 * Non-exportable AES-256 key in the Android Keystore, created on first use. Deliberately not
 * androidx.security-crypto (deprecated).
 */
object AndroidKeystoreKeys {
    const val DEFAULT_ALIAS: String = "app.playlistplayer.secure_store.v1"
    private const val PROVIDER = "AndroidKeyStore"

    @Synchronized
    fun getOrCreate(alias: String = DEFAULT_ALIAS): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun cipher(alias: String = DEFAULT_ALIAS): ValueCipher {
        val key by lazy { getOrCreate(alias) }
        return AesGcmValueCipher { key }
    }
}

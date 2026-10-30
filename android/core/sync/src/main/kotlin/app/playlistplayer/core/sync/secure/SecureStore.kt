package app.playlistplayer.core.sync.secure

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.model.XtreamAccount
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

/**
 * Encrypted key/value store for bearer secrets: each playlist's source reference (Drive file id
 * or M3U URL) and its derived Xtream account, plus app-level secrets (e.g. the PIN hash, U12).
 *
 * Values are AES-GCM encrypted with an Android Keystore key ([ValueCipher]) and the ciphertext is
 * held in a Preferences DataStore. The storage key is bound as associated data, so a ciphertext
 * cannot be moved to another key.
 *
 * Missing vs damaged: [readString] tells a slot that was never written ([SecretRead.Missing]) from
 * one that is present but cannot be decrypted or decoded ([SecretRead.Unreadable], e.g. the
 * Keystore key was invalidated). Security decisions (the parental PIN) must use [readString] and
 * fail closed on Unreadable. The convenience reads ([getString], [sourceReference], [account],
 * [epgOverrideUrl]) keep treating an unreadable value as absent, which is safe for them: a lost
 * source or account only forces the user to re-enter it.
 *
 * Exactly one instance must exist per DataStore file (DataStore requirement).
 */
class SecureStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: ValueCipher,
) {
    suspend fun putSourceReference(playlistId: Long, reference: SourceReference) {
        val json = when (reference) {
            is SourceReference.Drive -> buildJsonObject {
                put("type", "DRIVE")
                put("fileId", reference.fileId)
                reference.resourceKey?.let { put("resourceKey", it) }
            }
            is SourceReference.M3uUrl -> buildJsonObject {
                put("type", "M3U_URL")
                put("url", reference.url)
            }
        }
        putString(sourceKey(playlistId), json.toString())
    }

    suspend fun sourceReference(playlistId: Long): SourceReference? {
        val obj = getString(sourceKey(playlistId))?.let(::parseObject) ?: return null
        return when (obj.string("type")) {
            "DRIVE" -> obj.string("fileId")?.let { SourceReference.Drive(it, obj.string("resourceKey")) }
            "M3U_URL" -> obj.string("url")?.let { SourceReference.M3uUrl(it) }
            else -> null
        }
    }

    suspend fun putAccount(playlistId: Long, account: XtreamAccount) {
        val json = buildJsonObject {
            put("scheme", account.scheme)
            put("host", account.host)
            account.port?.let { put("port", it) }
            put("username", account.username)
            put("password", account.password)
        }
        putString(accountKey(playlistId), json.toString())
    }

    suspend fun account(playlistId: Long): XtreamAccount? {
        val obj = getString(accountKey(playlistId))?.let(::parseObject) ?: return null
        return XtreamAccount(
            scheme = obj.string("scheme") ?: return null,
            host = obj.string("host") ?: return null,
            port = (obj["port"] as? JsonPrimitive)?.intOrNull,
            username = obj.string("username") ?: return null,
            password = obj.string("password") ?: return null,
        )
    }

    suspend fun removeAccount(playlistId: Long) = remove(accountKey(playlistId))

    /** The user's XMLTV override URL for [playlistId] (may carry a token, hence stored here). */
    suspend fun putEpgOverrideUrl(playlistId: Long, url: String) = putString(epgOverrideKey(playlistId), url)

    suspend fun epgOverrideUrl(playlistId: Long): String? = getString(epgOverrideKey(playlistId))?.trim()?.ifEmpty { null }

    suspend fun removeEpgOverrideUrl(playlistId: Long) = remove(epgOverrideKey(playlistId))

    /** Removes every secret held for [playlistId]. */
    suspend fun removePlaylist(playlistId: Long) {
        val prefix = playlistPrefix(playlistId)
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { prefs.remove(it) }
        }
    }

    /** Generic encrypted string slot for other secrets (e.g. `app.pinHash`). */
    suspend fun putString(name: String, value: String) {
        val encrypted = cipher.encrypt(value.toByteArray(Charsets.UTF_8), name.toByteArray(Charsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        dataStore.edit { it[stringPreferencesKey(name)] = encoded }
    }

    /** The decrypted value, or null when the slot is missing **or unreadable** (see [readString]). */
    suspend fun getString(name: String): String? = (readString(name) as? SecretRead.Value)?.value

    /** Reads a generic slot, distinguishing a missing slot from a present-but-undecryptable one. */
    suspend fun readString(name: String): SecretRead {
        val encoded = dataStore.data.first()[stringPreferencesKey(name)] ?: return SecretRead.Missing
        return try {
            val blob = Base64.getDecoder().decode(encoded)
            SecretRead.Value(cipher.decrypt(blob, name.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            SecretRead.Unreadable
        } catch (_: IllegalArgumentException) {
            SecretRead.Unreadable
        } catch (_: ProviderException) {
            // Android Keystore reports some key failures (e.g. a wiped key blob) as ProviderException.
            SecretRead.Unreadable
        }
    }

    suspend fun remove(name: String) {
        dataStore.edit { it.remove(stringPreferencesKey(name)) }
    }

    /** Wipes every secret ("Reset app"). */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val FILE_NAME: String = "secure_store"

        private fun playlistPrefix(playlistId: Long) = "playlist.$playlistId."
        private fun sourceKey(playlistId: Long) = "${playlistPrefix(playlistId)}source"
        private fun accountKey(playlistId: Long) = "${playlistPrefix(playlistId)}account"
        private fun epgOverrideKey(playlistId: Long) = "${playlistPrefix(playlistId)}epgOverride"

        /** Production instance: Keystore-backed cipher over `files/datastore/secure_store.preferences_pb`. */
        fun create(context: Context): SecureStore {
            val appContext = context.applicationContext
            val dataStore = PreferenceDataStoreFactory.create(
                produceFile = { appContext.preferencesDataStoreFile(FILE_NAME) },
            )
            return SecureStore(dataStore, AndroidKeystoreKeys.cipher())
        }
    }
}

/** Result of [SecureStore.readString]. */
sealed interface SecretRead {
    /** The slot was never written (or was removed). */
    data object Missing : SecretRead

    /** The slot holds ciphertext that no longer decrypts or decodes (key invalidated, corruption). */
    data object Unreadable : SecretRead

    /** The decrypted value. [toString] never prints it. */
    class Value(val value: String) : SecretRead {
        override fun equals(other: Any?): Boolean = other is Value && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Value(<redacted>)"
    }
}

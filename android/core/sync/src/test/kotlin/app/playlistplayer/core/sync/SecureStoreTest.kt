package app.playlistplayer.core.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.sync.secure.AesGcmValueCipher
import app.playlistplayer.core.sync.secure.SecureStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * SecureStore with a software AES key (Robolectric has no AndroidKeyStore provider). Runs under
 * Robolectric because DataStore's plain-JVM file rename fails on Windows hosts.
 */
@RunWith(RobolectricTestRunner::class)
class SecureStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val key = newKey()

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(file: File, key: SecretKey = this.key): SecureStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return SecureStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }), AesGcmValueCipher { key })
    }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    @Test
    fun roundTripsReferencesAndAccountsEncrypted() = runBlocking<Unit> {
        val file = File(temp.root, "a.preferences_pb")
        val store = store(file)
        val account = XtreamAccount("http", "example.invalid", 8080, "alice", "s3cret")

        store.putSourceReference(1, SourceReference.Drive("DRIVE_FILE_ID", "RESKEY"))
        store.putSourceReference(2, SourceReference.M3uUrl("http://example.invalid/get.php?username=alice&password=s3cret"))
        store.putAccount(1, account)

        assertThat(store.sourceReference(1)).isEqualTo(SourceReference.Drive("DRIVE_FILE_ID", "RESKEY"))
        assertThat(store.sourceReference(2)).isEqualTo(SourceReference.M3uUrl("http://example.invalid/get.php?username=alice&password=s3cret"))
        assertThat(store.account(1)).isEqualTo(account)
        assertThat(store.account(2)).isNull()

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("DRIVE_FILE_ID", "RESKEY", "example.invalid", "alice", "s3cret")) {
            assertThat(raw).doesNotContain(secret)
        }
    }

    @Test
    fun removePlaylistDeletesOnlyThatPlaylist() = runBlocking<Unit> {
        val store = store(File(temp.root, "b.preferences_pb"))
        store.putSourceReference(1, SourceReference.Drive("A"))
        store.putAccount(1, XtreamAccount("http", "h", null, "u", "p"))
        store.putSourceReference(11, SourceReference.Drive("B")) // prefix "playlist.1" must not match 11
        store.putString("app.pinHash", "hash")

        store.removePlaylist(1)

        assertThat(store.sourceReference(1)).isNull()
        assertThat(store.account(1)).isNull()
        assertThat(store.sourceReference(11)).isEqualTo(SourceReference.Drive("B"))
        assertThat(store.getString("app.pinHash")).isEqualTo("hash")

        store.clear()
        assertThat(store.sourceReference(11)).isNull()
    }

    @Test
    fun wrongKeyReadsAsAbsent() = runBlocking<Unit> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temp.root, "c.preferences_pb") })
        SecureStore(dataStore, AesGcmValueCipher { key }).putString("x", "value")

        // Same storage, different key (e.g. Keystore key invalidated): the value reads as absent.
        val otherKey = newKey()
        assertThat(SecureStore(dataStore, AesGcmValueCipher { otherKey }).getString("x")).isNull()
        assertThat(SecureStore(dataStore, AesGcmValueCipher { key }).getString("x")).isEqualTo("value")
    }

    @Test
    fun ciphertextIsBoundToItsKeyName() {
        val cipher = AesGcmValueCipher { key }
        val blob = cipher.encrypt("secret".toByteArray(), "name.a".toByteArray())
        assertThat(cipher.decrypt(blob, "name.a".toByteArray()).toString(Charsets.UTF_8)).isEqualTo("secret")
        val moved = runCatching { cipher.decrypt(blob, "name.b".toByteArray()) }
        assertThat(moved.exceptionOrNull()).isInstanceOf(GeneralSecurityException::class.java)
        // Fresh IV each time.
        assertThat(cipher.encrypt("secret".toByteArray(), "n".toByteArray()))
            .isNotEqualTo(cipher.encrypt("secret".toByteArray(), "n".toByteArray()))
    }
}

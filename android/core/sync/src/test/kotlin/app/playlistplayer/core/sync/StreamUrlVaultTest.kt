package app.playlistplayer.core.sync

import app.playlistplayer.core.sync.secure.AesGcmValueCipher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/** Plain JVM test: the vault uses only java.io and javax.crypto. Synthetic URLs only. */
class StreamUrlVaultTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val active = HashMap<Long, Long>()

    private fun vault(dir: File = File(temp.root, "vault"), chunkSize: Int = 8) =
        StreamUrlVault(dir, AesGcmValueCipher { key }, { active[it] }, chunkSize = chunkSize)

    private fun url(i: Int, tag: String = "a") = "https://cdn.example.invalid/token-secretuser-$tag/stream$i.m3u8?auth=topsecret$i"

    private fun StreamUrlVault.write(playlistId: Long, generation: Long, ordinals: Iterable<Int>, tag: String = "a") {
        val w = writer(playlistId, generation)
        ordinals.forEach { w.add(it, url(it, tag)) }
        w.finish()
    }

    @Test
    fun writeThenReadSparseOrdinalsAcrossChunks() = runBlocking<Unit> {
        val v = vault()
        val ordinals = (0 until 100).filter { it % 3 != 0 }
        v.write(1, 1, ordinals)
        active[1] = 1

        for (i in 0 until 100) {
            val expected = if (i % 3 != 0) url(i) else null
            assertThat(v.streamUrl(1, i)).isEqualTo(expected)
        }
        assertThat(v.streamUrl(1, 500)).isNull()
        assertThat(v.streamUrl(1, -1)).isNull()
        assertThat(v.streamUrl(2, 1)).isNull() // no active generation
    }

    @Test
    fun retainSwapsToTheNewGenerationAndDiscardDeletesIt() = runBlocking<Unit> {
        val v = vault()
        v.write(1, 1, 0 until 10, tag = "old")
        active[1] = 1
        v.write(1, 2, 0 until 10, tag = "new")
        // Until the flip, readers keep seeing generation 1.
        assertThat(v.streamUrl(1, 3)).isEqualTo(url(3, "old"))

        active[1] = 2
        v.retain(1, 2)
        assertThat(v.hasGeneration(1, 1)).isFalse()
        assertThat(v.streamUrl(1, 3)).isEqualTo(url(3, "new"))

        v.write(1, 3, 0 until 10, tag = "rejected")
        v.discard(1, 3)
        assertThat(v.hasGeneration(1, 3)).isFalse()
        assertThat(v.hasGeneration(1, 2)).isTrue()
        assertThat(v.streamUrl(1, 3)).isEqualTo(url(3, "new"))
    }

    @Test
    fun abortLeavesNoFileAndEmptyGenerationWritesNothing() = runBlocking<Unit> {
        val v = vault()
        val w = v.writer(1, 1)
        (0 until 20).forEach { w.add(it, url(it)) }
        w.abort()
        assertThat(File(temp.root, "vault/p1").listFiles().orEmpty().toList()).isEmpty()

        v.writer(1, 2).finish()
        assertThat(v.hasGeneration(1, 2)).isFalse()
    }

    @Test
    fun deletePlaylistRemovesOnlyThatPlaylist() = runBlocking<Unit> {
        val v = vault()
        v.write(1, 1, 0 until 5)
        v.write(2, 1, 0 until 5)
        active[1] = 1
        active[2] = 1

        v.deletePlaylist(1)

        assertThat(v.streamUrl(1, 0)).isNull()
        assertThat(v.streamUrl(2, 0)).isEqualTo(url(0))
        assertThat(File(temp.root, "vault/p1").exists()).isFalse()
    }

    @Test
    fun nothingPlaintextLandsOnDisk() = runBlocking<Unit> {
        val v = vault(chunkSize = 4)
        v.write(1, 1, 0 until 50)
        // An in-progress temp file must not leak either.
        val w = v.writer(1, 2)
        (0 until 50).forEach { w.add(it, url(it)) }

        val files = File(temp.root, "vault").walkTopDown().filter { it.isFile }.toList()
        assertThat(files).isNotEmpty()
        val needles = listOf("secretuser", "topsecret", "cdn.example.invalid", "stream1", "https://")
        for (file in files) {
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            for (needle in needles) assertThat(text).doesNotContain(needle)
            // UTF-16 would also be a leak.
            assertThat(file.readBytes().toString(Charsets.UTF_16LE)).doesNotContain("secretuser")
        }
        w.abort()
    }

    @Test
    fun tamperedOrMovedFilesReadAsAbsent() = runBlocking<Unit> {
        val v = vault()
        v.write(1, 1, 0 until 10)
        active[1] = 1
        val file = File(temp.root, "vault/p1/g1.vault")

        // Moved to another playlist/generation: the wrapped key is bound to (playlist, generation).
        File(temp.root, "vault/p2").mkdirs()
        file.copyTo(File(temp.root, "vault/p2/g1.vault"))
        active[2] = 1
        assertThat(v.streamUrl(2, 1)).isNull()

        // A flipped ciphertext byte fails authentication instead of returning garbage.
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        file.writeBytes(bytes)
        file.setLastModified(file.lastModified() + 5_000)
        val fresh = vault()
        assertThat(fresh.streamUrl(1, 1)).isNull()
    }

    @Test
    fun clearRemovesEverything() = runBlocking<Unit> {
        val v = vault()
        v.write(1, 1, 0 until 3)
        v.clear()
        assertThat(File(temp.root, "vault").exists()).isFalse()
    }
}

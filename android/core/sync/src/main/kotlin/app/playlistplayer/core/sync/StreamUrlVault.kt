package app.playlistplayer.core.sync

import app.playlistplayer.core.sync.secure.ValueCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted per-playlist store of stream URLs for entries that cannot be rebuilt from an Xtream
 * account (generic, non-Xtream-shaped M3U entries: `Channel.streamId == null`). Room never holds
 * URLs because they may embed credentials or tokens, so these entries are playable only through
 * [streamUrl].
 *
 * **Layout.** One file per (playlist, generation): `<dir>/p<playlistId>/g<generation>.vault`,
 * written alongside the Room generation by [PlaylistSyncer] and swapped/deleted with it
 * ([retain] after activation, [discard] on failure, [deletePlaylist] on removal). A generation
 * without such entries has no file.
 *
 * **Encryption.** Envelope scheme: each file has a random AES-256 data key, wrapped by the
 * Keystore-backed [ValueCipher] (associated data binds it to the playlist and generation).
 * Entries are grouped by ordinal into chunks of [chunkSize]; each chunk is AES-GCM encrypted with
 * the data key, a fresh IV and the chunk's identity as associated data, so a lookup decrypts one
 * small chunk rather than the whole file. Only chunk offsets are stored in clear. Nothing
 * plaintext is ever written to disk (the writer encrypts in memory before writing).
 *
 * File format: `"SUV1" | int wrappedKeyLength | wrappedKey | chunk* | index | long indexOffset | "SUVE"`,
 * chunk = `iv(12) | ciphertext+tag` over `int count | (int ordinal | int length | utf8 url)*`,
 * index = `int chunkCount | (int chunkNumber | long offset | int length)*`.
 */
class StreamUrlVault(
    private val directory: File,
    private val cipher: ValueCipher,
    private val activeGeneration: suspend (playlistId: Long) -> Long?,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    private class OpenFile(val path: File, val modified: Long, val key: SecretKey, val index: Map<Int, LongArray>)

    private val lock = Any()
    private var cached: OpenFile? = null

    /** Starts writing [generation] of [playlistId]. Add entries in increasing ordinal order. */
    fun writer(playlistId: Long, generation: Long): Writer = Writer(playlistId, generation)

    /**
     * The stream URL of the entry at [ordinal] in the active generation of [playlistId], or null
     * when it has none (Xtream entries, headers) or the vault cannot be read (key invalidated:
     * the next playlist sync rewrites it). Secret: never log the result.
     */
    suspend fun streamUrl(playlistId: Long, ordinal: Int): String? {
        val generation = activeGeneration(playlistId) ?: return null
        return withContext(Dispatchers.IO) { read(playlistId, generation, ordinal) }
    }

    /** True when a committed vault file exists for this generation (tests/diagnostics). */
    fun hasGeneration(playlistId: Long, generation: Long): Boolean = fileFor(playlistId, generation).isFile

    /** Keeps only [generation]'s file for [playlistId] (after the generation was activated). */
    suspend fun retain(playlistId: Long, generation: Long) = withContext(Dispatchers.IO) {
        val keep = fileFor(playlistId, generation).name
        playlistDir(playlistId).listFiles()?.filter { it.name != keep }?.forEach { it.delete() }
        invalidate()
    }

    /** Deletes [generation]'s file (incomplete or rejected generation). */
    suspend fun discard(playlistId: Long, generation: Long) = withContext(Dispatchers.IO) {
        fileFor(playlistId, generation).delete()
        tempFor(playlistId, generation).delete()
        invalidate()
    }

    /** Deletes every file of [playlistId] (playlist removed). */
    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDir(playlistId).deleteRecursively()
        invalidate()
    }

    /** Deletes everything ("Reset app"). */
    suspend fun clear() = withContext(Dispatchers.IO) {
        directory.deleteRecursively()
        invalidate()
    }

    private fun invalidate() = synchronized(lock) { cached = null }

    private fun playlistDir(playlistId: Long) = File(directory, "p$playlistId")

    private fun fileFor(playlistId: Long, generation: Long) = File(playlistDir(playlistId), "g$generation$SUFFIX")

    private fun tempFor(playlistId: Long, generation: Long) = File(playlistDir(playlistId), "g$generation$SUFFIX.tmp")

    private fun read(playlistId: Long, generation: Long, ordinal: Int): String? {
        val path = fileFor(playlistId, generation)
        if (!path.isFile || ordinal < 0) return null
        return try {
            val open = open(playlistId, generation, path)
            val chunk = ordinal / chunkSize
            val location = open.index[chunk] ?: return null
            val sealed = ByteArray(location[1].toInt())
            RandomAccessFile(path, "r").use { file ->
                file.seek(location[0])
                file.readFully(sealed)
            }
            val plain = openChunk(open.key, sealed, chunkAad(playlistId, generation, chunk))
            findEntry(plain, ordinal)
        } catch (_: IOException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun open(playlistId: Long, generation: Long, path: File): OpenFile {
        synchronized(lock) {
            cached?.let { if (it.path == path && it.modified == path.lastModified()) return it }
        }
        val opened = RandomAccessFile(path, "r").use { file ->
            val magic = ByteArray(4).also { file.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "Not a vault file" }
            val wrappedLength = file.readInt()
            require(wrappedLength in 1..MAX_WRAPPED_KEY) { "Bad key length" }
            val wrapped = ByteArray(wrappedLength).also { file.readFully(it) }
            val key = SecretKeySpec(cipher.decrypt(wrapped, keyAad(playlistId, generation)), "AES")

            val length = file.length()
            require(length >= TRAILER_BYTES) { "Truncated vault file" }
            file.seek(length - TRAILER_BYTES)
            val indexOffset = file.readLong()
            val endMagic = ByteArray(4).also { file.readFully(it) }
            require(endMagic.contentEquals(END_MAGIC)) { "Truncated vault file" }
            require(indexOffset in 0 until length) { "Bad index offset" }
            file.seek(indexOffset)
            val count = file.readInt()
            require(count >= 0 && count <= (length - indexOffset) / 16) { "Bad index" }
            val index = HashMap<Int, LongArray>(count * 2)
            repeat(count) {
                val chunk = file.readInt()
                val offset = file.readLong()
                val size = file.readInt()
                require(offset >= 0 && size > 0 && offset + size <= indexOffset) { "Bad chunk location" }
                index[chunk] = longArrayOf(offset, size.toLong())
            }
            OpenFile(path, path.lastModified(), key, index)
        }
        synchronized(lock) { cached = opened }
        return opened
    }

    private fun findEntry(plain: ByteArray, ordinal: Int): String? {
        val input = DataInputStream(plain.inputStream())
        val count = input.readInt()
        repeat(count) {
            val entryOrdinal = input.readInt()
            val length = input.readInt()
            if (entryOrdinal == ordinal) {
                val bytes = ByteArray(length).also { input.readFully(it) }
                return bytes.toString(Charsets.UTF_8)
            }
            if (input.skipBytes(length) != length) return null
        }
        return null
    }

    private fun openChunk(key: SecretKey, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "Truncated chunk" }
        val gcm = Cipher.getInstance(TRANSFORMATION)
        gcm.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        gcm.updateAAD(aad)
        return gcm.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    /**
     * Streams one generation's URLs into an encrypted temp file; [finish] commits it, [abort]
     * deletes it. Not thread-safe; used from the single parse loop.
     */
    inner class Writer internal constructor(private val playlistId: Long, private val generation: Long) {
        private val temp = tempFor(playlistId, generation)
        private var out: CountingStream? = null
        private var key: SecretKey? = null
        private val index = ArrayList<Triple<Int, Long, Int>>()
        private var chunk = -1
        private val pending = ByteArrayOutputStream()
        private var pendingCount = 0
        private var lastOrdinal = -1
        private var closed = false

        var entriesWritten: Int = 0
            private set

        fun add(ordinal: Int, url: String) {
            check(!closed) { "Writer is closed" }
            require(ordinal > lastOrdinal) { "Ordinals must increase" }
            lastOrdinal = ordinal
            val target = ordinal / chunkSize
            if (target != chunk) {
                flushChunk()
                chunk = target
            }
            val bytes = url.toByteArray(Charsets.UTF_8)
            DataOutputStream(pending).apply {
                writeInt(ordinal)
                writeInt(bytes.size)
                write(bytes)
            }
            pendingCount++
            entriesWritten++
        }

        /** Completes the file. With no entries nothing is written (and any old file is left to [retain]). */
        fun finish() {
            check(!closed) { "Writer is closed" }
            closed = true
            if (entriesWritten == 0) {
                temp.delete()
                return
            }
            try {
                flushChunk()
                val stream = checkNotNull(out)
                val indexOffset = stream.count
                DataOutputStream(stream).apply {
                    writeInt(index.size)
                    index.forEach { (c, offset, length) ->
                        writeInt(c)
                        writeLong(offset)
                        writeInt(length)
                    }
                    writeLong(indexOffset)
                    write(END_MAGIC)
                    flush()
                }
                stream.syncAndClose()
                out = null
                val target = fileFor(playlistId, generation)
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("Could not commit the stream URL vault")
                }
                invalidate()
            } catch (e: Exception) {
                cleanup()
                throw e
            }
        }

        /** Discards the partial file. Safe to call more than once or after [finish]. */
        fun abort() {
            closed = true
            cleanup()
        }

        private fun cleanup() {
            try {
                out?.close()
            } catch (_: IOException) {
            }
            out = null
            temp.delete()
        }

        private fun stream(): CountingStream {
            out?.let { return it }
            temp.parentFile?.mkdirs()
            val dataKey = ByteArray(KEY_BYTES).also(random::nextBytes)
            val wrapped = cipher.encrypt(dataKey, keyAad(playlistId, generation))
            key = SecretKeySpec(dataKey, "AES")
            dataKey.fill(0)
            val stream = CountingStream(FileOutputStream(temp))
            DataOutputStream(stream).apply {
                write(MAGIC)
                writeInt(wrapped.size)
                write(wrapped)
                flush()
            }
            out = stream
            return stream
        }

        private fun flushChunk() {
            if (pendingCount == 0) return
            val stream = stream()
            val plain = ByteArrayOutputStream(pending.size() + 4)
            DataOutputStream(plain).apply {
                writeInt(pendingCount)
                write(pending.toByteArray())
            }
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val gcm = Cipher.getInstance(TRANSFORMATION)
            gcm.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            gcm.updateAAD(chunkAad(playlistId, generation, chunk))
            val sealed = gcm.doFinal(plain.toByteArray())
            val offset = stream.count
            stream.write(iv)
            stream.write(sealed)
            index.add(Triple(chunk, offset, IV_BYTES + sealed.size))
            pending.reset()
            pendingCount = 0
        }
    }

    /** Buffered file stream that tracks its position. */
    private class CountingStream(private val file: FileOutputStream) : OutputStream() {
        private val buffered = BufferedOutputStream(file, 64 * 1024)
        var count: Long = 0
            private set

        override fun write(b: Int) {
            buffered.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            buffered.write(b, off, len)
            count += len
        }

        override fun flush() = buffered.flush()

        fun syncAndClose() {
            buffered.flush()
            file.fd.sync()
            buffered.close()
        }

        override fun close() = buffered.close()
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE: Int = 256
        const val DIRECTORY_NAME: String = "stream_url_vault"
        private const val SUFFIX = ".vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
        private const val MAX_WRAPPED_KEY = 1024
        private const val TRAILER_BYTES = 12L
        private val MAGIC = "SUV1".toByteArray(Charsets.US_ASCII)
        private val END_MAGIC = "SUVE".toByteArray(Charsets.US_ASCII)

        private fun keyAad(playlistId: Long, generation: Long) = "vault:$playlistId:$generation".toByteArray(Charsets.UTF_8)

        private fun chunkAad(playlistId: Long, generation: Long, chunk: Int) =
            "vault:$playlistId:$generation:$chunk".toByteArray(Charsets.UTF_8)
    }
}

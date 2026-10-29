package app.playlistplayer.core.network.xtream

import java.io.InputStream

/** Spike fixtures (hosts scrubbed to example.invalid, credentials to user/pass). */
object Fixtures {
    fun bytes(name: String): ByteArray =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)
}

/**
 * A synthetic get_vod_streams body shaped like the provider's (string/int tmdb mix, string
 * ratings), generated lazily so large bodies never sit in test heap unless asked for.
 */
object SyntheticVodBody {
    fun item(i: Int): String {
        val tmdb = when (i % 4) {
            0 -> "\"${100_000 + i / 3}\""
            1 -> "${100_000 + i / 3}"
            2 -> "\"\""
            else -> "0"
        }
        return """{"num":$i,"name":"EN - Synthetic Movie $i (${1950 + i % 75})","stream_type":"movie","stream_id":$i,""" +
            """"stream_icon":"https://example.invalid/t/p/w600/$i.jpg","rating":"${i % 10}.5","rating_5based":${(i % 10) / 2.0},""" +
            """"tmdb":$tmdb,"trailer":"","added":"${1_700_000_000 + i}","is_adult":0,"category_id":"${100 + i % 50}",""" +
            """"category_ids":[${100 + i % 50}],"container_extension":"${if (i % 3 == 0) "mp4" else "mkv"}","custom_sid":null,"direct_source":""}"""
    }

    fun bytes(count: Int): ByteArray {
        val sb = StringBuilder(count * 420)
        sb.append("[\n")
        for (i in 1..count) {
            if (i > 1) sb.append(",\n")
            sb.append(item(i))
        }
        sb.append("\n]")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Streams `[item1, ..., itemN]` chunk by chunk and counts the bytes handed out. */
    class Stream(private val count: Int) : InputStream() {
        private var next = 1
        private var chunk: ByteArray = "[\n".toByteArray()
        private var pos = 0
        private var finished = false
        var bytesRead: Long = 0
            private set

        private fun refill(): Boolean {
            if (finished) return false
            if (next > count) {
                chunk = "\n]".toByteArray()
                finished = true
            } else {
                val sb = StringBuilder(32 * 1024)
                while (next <= count && sb.length < 30_000) {
                    if (next > 1) sb.append(",\n")
                    sb.append(item(next++))
                }
                chunk = sb.toString().toByteArray(Charsets.UTF_8)
            }
            pos = 0
            return true
        }

        override fun read(): Int {
            while (pos >= chunk.size) if (!refill()) return -1
            bytesRead++
            return chunk[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) if (!refill()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            bytesRead += n
            return n
        }
    }
}

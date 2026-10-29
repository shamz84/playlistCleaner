package app.playlistplayer.core.parsing.xmltv

import java.io.InputStream

internal object TestFixtures {
    fun stream(name: String): InputStream =
        requireNotNull(TestFixtures::class.java.getResourceAsStream("/xmltv/$name")) { "missing fixture $name" }

    fun bytes(name: String): ByteArray = stream(name).use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    /** Maps a fixture's `epg_listings` to [XtreamEpgListing] the way the network module will. */
    @Suppress("UNCHECKED_CAST")
    fun listings(name: String): List<XtreamEpgListing> {
        val root = MiniJson.parse(text(name)) as Map<String, Any?>
        val items = root["epg_listings"] as List<Map<String, Any?>>
        return items.map { m ->
            fun s(key: String): String? = m[key]?.toString()
            XtreamEpgListing(
                id = s("id"),
                epgId = s("epg_id"),
                channelId = s("channel_id"),
                streamId = s("stream_id"),
                title = s("title"),
                description = s("description"),
                lang = s("lang"),
                start = s("start"),
                end = s("end"),
                startTimestamp = s("start_timestamp"),
                stopTimestamp = s("stop_timestamp"),
                nowPlaying = s("now_playing"),
                hasArchive = s("has_archive"),
            )
        }
    }
}

/** Test-only JSON reader (objects, arrays, strings, numbers as their literal text, bool, null). */
internal class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run { value().also { ws(); check(i == s.length) } }
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> {
                check(c == '-' || c.isDigit()) { "unexpected '$c' at $i" }
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                s.substring(start, i)
            }
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        check(s.startsWith(word, i)); i += word.length; return v
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws(); check(s[i++] == ':')
            m[k] = value(); ws()
            when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("bad object at $i") }
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l += value(); ws()
            when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("bad array at $i") }
        }
    }

    private fun str(): String {
        check(s[i++] == '"')
        val b = StringBuilder()
        while (true) {
            when (val c = s[i++]) {
                '"' -> return b.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                    'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
                else -> b.append(c)
            }
        }
    }
}

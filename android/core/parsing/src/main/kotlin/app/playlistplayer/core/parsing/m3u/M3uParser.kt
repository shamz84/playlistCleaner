package app.playlistplayer.core.parsing.m3u

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets

/**
 * One parsed `#EXTINF` + URL pair.
 *
 * [url] is a credential-bearing secret kept only for the lifetime of the parse (stream id
 * extraction and account derivation). Never persist or log it; [toString] omits it.
 */
class M3uEntry(
    /** 0-based position among emitted entries, i.e. file order across all groups. */
    val ordinal: Int,
    val groupOrdinal: Int,
    val groupTitle: String,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val tvgLogo: String?,
    val cuid: String?,
    val tvgChno: String?,
    val catchupType: String?,
    val catchupDays: Int,
    val isHeader: Boolean,
    val streamId: Long?,
    val containerExtension: String?,
    val url: String,
    /** All attributes as written (lower-cased keys). */
    val attributes: Map<String, String>,
) {
    override fun toString(): String =
        "M3uEntry(ordinal=$ordinal, group=$groupOrdinal, name=$name, streamId=$streamId, isHeader=$isHeader)"
}

/**
 * Streaming M3U/M3U8 parser. Reads line by line, so memory stays bounded by the current entry no
 * matter how large the playlist is.
 *
 * Rules:
 * - Group and channel ordinals follow file order; groups are never re-sorted.
 * - UTF-8 throughout (a leading BOM is skipped), so emoji group names survive unchanged.
 * - An `#EXTINF` without a following URL (another `#EXTINF` or EOF comes first) is skipped and
 *   counted in [M3uReader.skippedWithoutUrl].
 * - Other directives between `#EXTINF` and the URL (`#EXTVLCOPT`, ...) are ignored; `#EXTGRP`
 *   supplies the group when `group-title` is absent.
 * - Separator/placeholder entries (names such as `### SPORTS ###`) are flagged [M3uEntry.isHeader].
 */
class M3uParser {
    fun open(input: InputStream): M3uReader =
        M3uReader(BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), BUFFER_SIZE))

    fun open(reader: Reader): M3uReader =
        M3uReader(reader as? BufferedReader ?: BufferedReader(reader, BUFFER_SIZE))

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** Group used when an entry names none. */
        const val UNGROUPED: String = "Ungrouped"

        private val HEADER_NAME = Regex("""^\s*([#=*~_\-]{2,}).*?([#=*~_\-]{2,})\s*$""")

        /** True for separator/placeholder names such as `### SPORTS ###` or `===== NEWS =====`. */
        fun isHeaderName(name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.all { it in "#=*~_- " }) return true
            return HEADER_NAME.matches(trimmed)
        }
    }
}

class M3uReader internal constructor(private val reader: BufferedReader) : Iterator<M3uEntry>, Closeable {
    private val groupIndex = LinkedHashMap<String, Int>()
    private val groupCounts = ArrayList<Int>()
    private var next: M3uEntry? = null
    private var done = false
    private var firstLine = true
    private var emitted = 0

    /** `#EXTINF` lines dropped because no URL followed them. */
    var skippedWithoutUrl: Int = 0
        private set

    /** Attributes from the `#EXTM3U` header line, if present (e.g. `url-tvg`). */
    var headerAttributes: Map<String, String> = emptyMap()
        private set

    /** Groups in first-appearance order with their entry counts (complete once iteration ends). */
    val groups: List<Pair<String, Int>>
        get() = groupIndex.keys.mapIndexed { index, name -> name to groupCounts[index] }

    val entryCount: Int get() = emitted

    override fun hasNext(): Boolean {
        if (next == null && !done) next = readNext()
        return next != null
    }

    override fun next(): M3uEntry {
        if (!hasNext()) throw NoSuchElementException()
        val entry = next!!
        next = null
        return entry
    }

    override fun close() = reader.close()

    private fun readNext(): M3uEntry? {
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        while (true) {
            var line = reader.readLine()
            if (line == null) {
                if (pendingInfo != null) skippedWithoutUrl++
                done = true
                return null
            }
            if (firstLine) {
                firstLine = false
                if (line.startsWith('﻿')) line = line.substring(1)
            }
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (pendingInfo != null) skippedWithoutUrl++
                    pendingInfo = line
                    pendingGroup = null
                }
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    headerAttributes = parseAttributes(line, start = "#EXTM3U".length, end = line.length)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substring("#EXTGRP:".length).trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit // other directives and comments
                else -> {
                    val info = pendingInfo ?: continue // URL without #EXTINF: not a listed entry
                    return buildEntry(info, pendingGroup, url = line)
                }
            }
        }
    }

    private fun buildEntry(info: String, extGroup: String?, url: String): M3uEntry {
        val colon = info.indexOf(':')
        val bodyStart = if (colon < 0) info.length else colon + 1
        val comma = findNameComma(info, bodyStart)
        val attributes = parseAttributes(info, bodyStart, if (comma < 0) info.length else comma)
        val rawName = if (comma < 0) "" else info.substring(comma + 1).trim()

        val tvgName = attributes["tvg-name"].clean()
        val name = rawName.ifEmpty { tvgName ?: "" }
        val groupTitle = attributes["group-title"].clean() ?: extGroup ?: M3uParser.UNGROUPED
        val groupOrdinal = groupIndex.getOrPut(groupTitle) {
            groupCounts.add(0)
            groupIndex.size
        }
        groupCounts[groupOrdinal] = groupCounts[groupOrdinal] + 1

        val parsedUrl = XtreamUrl.parse(url)
        val streamId = parsedUrl?.takeIf { it.kind != XtreamUrl.Kind.API }?.streamId
        val extension = parsedUrl?.takeIf { it.kind != XtreamUrl.Kind.API }?.extension
            ?: url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
                .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }?.lowercase()

        val catchupDays = attributes["catchup-days"].clean()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val catchupType = attributes["catchup-type"].clean() ?: attributes["catchup"].clean()

        return M3uEntry(
            ordinal = emitted++,
            groupOrdinal = groupOrdinal,
            groupTitle = groupTitle,
            name = name,
            tvgId = attributes["tvg-id"].clean(),
            tvgName = tvgName,
            tvgLogo = attributes["tvg-logo"].clean(),
            cuid = attributes["cuid"].clean(),
            tvgChno = attributes["tvg-chno"].clean(),
            catchupType = catchupType,
            catchupDays = catchupDays,
            isHeader = M3uParser.isHeaderName(name),
            streamId = streamId,
            containerExtension = extension,
            url = url,
            attributes = attributes,
        )
    }

    /** Index of the comma separating attributes from the display name, ignoring quoted commas. */
    private fun findNameComma(line: String, start: Int): Int {
        var inQuotes = false
        for (i in start until line.length) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    /** Parses `key="value"` (or `key=value`) pairs between [start] and [end]; keys lower-cased. */
    private fun parseAttributes(line: String, start: Int, end: Int): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = start
        while (i < end) {
            while (i < end && line[i].isWhitespace()) i++
            val keyStart = i
            while (i < end && line[i] != '=' && !line[i].isWhitespace()) i++
            if (i >= end || line[i] != '=') {
                // A bare token such as the duration "-1": skip it.
                continue
            }
            val key = line.substring(keyStart, i).lowercase()
            i++ // '='
            val value: String
            if (i < end && line[i] == '"') {
                val close = line.indexOf('"', i + 1).let { if (it < 0 || it > end) end else it }
                value = line.substring(i + 1, close)
                i = close + 1
            } else {
                val valueStart = i
                while (i < end && !line[i].isWhitespace()) i++
                value = line.substring(valueStart, i)
            }
            if (key.isNotEmpty()) result.putIfAbsent(key, value)
        }
        return result
    }

    private fun String?.clean(): String? {
        val value = this?.trim() ?: return null
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return null
        return value
    }
}

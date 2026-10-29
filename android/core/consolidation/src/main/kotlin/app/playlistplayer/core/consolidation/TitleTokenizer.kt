package app.playlistplayer.core.consolidation

import java.text.Normalizer
import java.util.Locale

/** Live channel names and VOD titles follow different conventions; see [TitleTokenizer]. */
enum class TokenizeMode { LIVE, VOD }

/**
 * Result of [TitleTokenizer.tokenize].
 *
 * @property cleanTitle the name with prefixes and tags removed, original casing, single spaces.
 * @property baseName normalized matching key: Unicode-folded, diacritics stripped, lowercase,
 *   letters and digits only, single spaces, with a space at every letter/digit boundary
 *   (`More4` → `more 4`). `&` becomes `and` and `+` becomes `plus`.
 *   Empty when nothing title-like is left (an "unparseable" name).
 * @property language language tag (`en`, `fr`, `in`, `in-tl`, ...) from a language prefix or
 *   bracket tag; never from a live country prefix.
 * @property platforms VOD source/platform codes (`NF`, `TOP`, `D+`, ...), upper case.
 * @property region live country prefix (`uk`, `us`, `in`) or a VOD trailing country (`gb`).
 * @property resolution vertical lines from an explicit label (3840p is read as 2160), else
 *   the tier's nominal height, else `null`.
 * @property codec `hevc` or `avc` when labelled.
 * @property date VOD event date as `yyyy-<b>-<a>` from `a.b.yyyy` / `a/b/yy` (equality only).
 * @property timeshiftHours `N` for a live `+N` channel, else 0.
 * @property isSeparator `#`-delimited header entries such as `### SPORTS ###`.
 */
data class TitleTokens(
    val raw: String,
    val cleanTitle: String,
    val baseName: String,
    val language: String?,
    val platforms: List<String>,
    val region: String?,
    val quality: QualityTier,
    val resolution: Int?,
    val hdr: Boolean,
    val codec: String?,
    val fps: Int?,
    val flags: Set<VersionFlag>,
    val year: Int?,
    val date: String?,
    val timeshiftHours: Int,
    val isSeparator: Boolean,
) {
    val isBackup: Boolean get() = VersionFlag.BACKUP in flags
    val isParseable: Boolean get() = baseName.isNotEmpty()
}

/**
 * Splits provider names into a matching key plus language / quality / flag tokens.
 *
 * Processing order:
 * 1. Unicode folding before any token extraction: superscript and modifier letters
 *    (`ᵁᴴᴰ ᴴᴰᴿ`, `ᴿᴬᵂ`, `³⁸⁴⁰ᴾ`, `⁴ᴷ`) and small capitals (`ꜱᴋʏ`) become ASCII, a space is
 *    inserted where a superscript run touches ordinary text, then NFKD with combining marks
 *    removed, and typographic dashes/quotes become ASCII.
 * 2. Prefixes. VOD: `[4K-]CODE[-CODE] - ` where every code is a known language (`EN`, `IN`,
 *    `IN-xx`) or platform (`NF`, `SOC`, `TOP`, `AMZ`, `D+`, ...); `EN:` also works. Live:
 *    `UK:` / `US:` / `IN:` are regions, `ENGLISH:` is a language. `|FR|` / `[EN]` tags are
 *    languages in both modes.
 * 3. Tags anywhere: event dates and `(yyyy)` years (VOD), `1080p50`, `2160p`, `50fps`,
 *    `8K/4K/UHD/FHD/HD/SD`, `RAW` (live, upper case), `HDR`, `HEVC/H265`, `MULTI-SUB`,
 *    `MULTI`, `DUAL`, bracketed or trailing `Backup`, bracketed `Telesync/CAM`, live `+N`.
 *
 * Articles: kept as part of the title (`the bride` and `bride` stay different keys); a
 * trailing `, The` / `, A` / `, An` is moved to the front so both spellings share a key.
 *
 * Words that double as tags in real titles are guarded: `RAW` is a tag only in live mode and
 * only in upper case (`WWE Raw` keeps its name), `DUAL`/`MULTI` only in upper case
 * (`Dual (2022)`), `CAM`/`TS` only inside brackets (`Cam (2018)`), `Backup` only when
 * bracketed or the last word.
 */
object TitleTokenizer {

    /** Languages recognised in prefixes and bracket tags (upper-case provider codes). */
    private val LANGUAGE_CODES = setOf(
        "EN", "FR", "DE", "ES", "IT", "PT", "NL", "AR", "TR", "PL", "RU", "SV", "DA", "FI",
        "EL", "HE", "HI", "IN", "AS",
    )

    /** Full-word languages in live `WORD:` prefixes. */
    private val LANGUAGE_WORDS = mapOf(
        "ENGLISH" to "en", "FRENCH" to "fr", "GERMAN" to "de", "SPANISH" to "es",
        "ITALIAN" to "it", "PORTUGUESE" to "pt", "DUTCH" to "nl", "ARABIC" to "ar",
        "TURKISH" to "tr", "POLISH" to "pl",
    )

    /** VOD source/platform codes, which carry no language (provider spike §3). */
    private val PLATFORM_CODES = setOf(
        "NF", "SOC", "TOP", "AMZ", "D+", "A+", "UNV", "PRMT", "VP", "MRVL",
    )

    /** Countries accepted as a VOD trailing `(GB)` region suffix. */
    private val VOD_REGION_SUFFIXES = setOf(
        "GB", "UK", "US", "CA", "AU", "IE", "NZ", "FR", "DE", "ES", "IT", "IN",
    )

    private val SMALL_CAPS: Map<Char, Char> = mapOf(
        'ᴀ' to 'A', 'ʙ' to 'B', 'ᴄ' to 'C', 'ᴅ' to 'D', 'ᴇ' to 'E', 'ꜰ' to 'F', 'ɢ' to 'G',
        'ʜ' to 'H', 'ɪ' to 'I', 'ᴊ' to 'J', 'ᴋ' to 'K', 'ʟ' to 'L', 'ᴍ' to 'M', 'ɴ' to 'N',
        'ᴏ' to 'O', 'ᴘ' to 'P', 'ǫ' to 'Q', 'ʀ' to 'R', 'ꜱ' to 'S', 'ᴛ' to 'T', 'ᴜ' to 'U',
        'ᴠ' to 'V', 'ᴡ' to 'W', 'ʏ' to 'Y', 'ᴢ' to 'Z',
    )

    private const val NB = "(?<![\\p{L}\\p{N}])" // not preceded by a letter/digit
    private const val NA = "(?![\\p{L}\\p{N}])" // not followed by a letter/digit

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212]")
    private val QUOTES = Regex("[\\u2018\\u2019\\u201B\\u02BC`´]")
    private val SPACES = Regex("\\s+")

    private val SEPARATOR = Regex("^#{2,}")
    private val VOD_PREFIX = Regex("^(4K-)?([A-Z][A-Z+]{0,4}(?:-[A-Z][A-Z+]{0,4})*)\\s*[-:]\\s*")
    private val LIVE_PREFIX = Regex("^([A-Za-z]{2,10})\\s*:\\s*")
    private val REGION_CODE = Regex("^[A-Z]{2,3}$")
    private val TOP_RANK = Regex("^\\d{1,3}\\.\\s*")
    private val BRACKET_TAG = Regex("[|\\[]\\s*([A-Za-z]{2,3})\\s*[|\\]]")

    private val DATE = Regex("\\(?${NB}(\\d{1,2})[./](\\d{1,2})[./](\\d{4}|\\d{2})${NA}\\)?")
    private val PAREN_YEAR = Regex("\\(\\s*((?:19|20)\\d{2})\\s*\\)")
    private val TRAILING_YEAR = Regex("${NB}((?:19|20)\\d{2})\\s*$")
    private val VOD_REGION = Regex("\\(([A-Z]{2})\\)\\s*$")

    private val RES_FPS = Regex("(?i)$NB(\\d{3,4})[pi](\\d{2,3})$NA")
    private val RES = Regex("(?i)$NB(4320|3840|2160|1440|1080|720|576|480|360)[pi]$NA")
    private val FPS = Regex("(?i)$NB(\\d{2,3})\\s?fps$NA")
    private val NAMED_QUALITY = Regex("(?i)$NB(8K|4K|UHD|FHD|HD|SD)$NA")
    private val RAW_LABEL = Regex("${NB}RAW$NA")
    private val HDR = Regex("(?i)${NB}(HDR10\\+?|HDR|DOLBY\\s+VISION)$NA")
    private val HEVC = Regex("(?i)$NB(HEVC|[HX]\\.?265)$NA")
    private val AVC = Regex("(?i)$NB([HX]\\.?264)$NA")
    private val MULTI_SUB = Regex("${NB}MULTI[- ]?SUBS?$NA")
    private val MULTI_AUDIO = Regex("${NB}MULTI(?:[- ]AUDIO)?$NA")
    private val DUAL = Regex("${NB}DUAL(?:[- ]AUDIO)?$NA")
    private val BACKUP_BRACKETED = Regex("(?i)[(\\[]\\s*back[- ]?up\\s*\\d*\\s*[)\\]]")
    private val BACKUP_TRAILING = Regex("(?i)${NB}back[- ]?up\\s*\\d*\\s*$")
    private val LOW_SOURCE_WORD = Regex("(?i)$NB(TELESYNC|TELECINE|HDCAM|CAMRIP|HDTS)$NA")
    private val LOW_SOURCE_BRACKETED = Regex("(?i)[(\\[]\\s*(CAM|TS|TC)\\s*[)\\]]")
    private val TIMESHIFT = Regex("\\+\\s*([1-9])\\s*$")

    private val EMPTY_BRACKETS = Regex("\\(\\s*\\)|\\[\\s*]|\\|\\s*\\|")
    private val EDGE_PUNCT = Regex("^[\\s\\-:|,.]+|[\\s\\-:|,]+$")
    private val TRAILING_ARTICLE = Regex("^(.*?),\\s*(The|A|An)$", RegexOption.IGNORE_CASE)

    fun tokenize(name: String, mode: TokenizeMode): TitleTokens {
        val folded = fold(name).trim()
        val isSeparator = SEPARATOR.containsMatchIn(folded) ||
            (folded.length > 1 && folded.startsWith('#') && folded.endsWith('#'))
        var text = if (isSeparator) folded.trim('#', ' ') else folded

        var language: String? = null
        var region: String? = null
        val platforms = mutableListOf<String>()

        // 2. Prefixes.
        when (mode) {
            TokenizeMode.VOD -> {
                VOD_PREFIX.find(text)?.let { match ->
                    val parsed = parseVodPrefix(match.groupValues[2])
                    if (parsed != null) {
                        language = parsed.first
                        platforms += parsed.second
                        if (match.groupValues[1].isNotEmpty()) platforms.add(0, "4K")
                        text = text.substring(match.range.last + 1)
                        if ("TOP" in platforms) text = text.replaceFirst(TOP_RANK, "")
                    }
                }
            }
            TokenizeMode.LIVE -> {
                LIVE_PREFIX.find(text)?.let { match ->
                    val word = match.groupValues[1]
                    val wordLanguage = LANGUAGE_WORDS[word.uppercase(Locale.ROOT)]
                    when {
                        wordLanguage != null -> language = wordLanguage
                        REGION_CODE.matches(word) -> region = word.lowercase(Locale.ROOT)
                    }
                    if (wordLanguage != null || REGION_CODE.matches(word)) {
                        text = text.substring(match.range.last + 1)
                    }
                }
            }
        }

        text = BRACKET_TAG.replace(text) { m ->
            val code = m.groupValues[1].uppercase(Locale.ROOT)
            when {
                code in LANGUAGE_CODES -> {
                    if (language == null) language = code.lowercase(Locale.ROOT)
                    " "
                }
                mode == TokenizeMode.LIVE && code.length == 2 && m.groupValues[1] == code -> {
                    if (region == null) region = code.lowercase(Locale.ROOT)
                    " "
                }
                else -> m.value
            }
        }

        // 3. Tags.
        var year: Int? = null
        var date: String? = null
        if (mode == TokenizeMode.VOD) {
            text = DATE.replace(text) { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                if (a !in 1..31 || b !in 1..31 || (a > 12 && b > 12)) return@replace m.value
                val y = m.groupValues[3].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
                if (date == null) {
                    date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, b, a)
                    year = y
                }
                " "
            }
            PAREN_YEAR.findAll(text).lastOrNull()?.let { year = year ?: it.groupValues[1].toInt() }
            text = PAREN_YEAR.replace(text, " ")
            val trimmed = text.trim()
            if (' ' !in trimmed && trimmed.count { it == '.' || it == '_' } >= 2) {
                text = trimmed.replace('.', ' ').replace('_', ' ')
                if (year == null) {
                    TRAILING_YEAR.find(text)?.let { m ->
                        year = m.groupValues[1].toInt()
                        text = text.substring(0, m.range.first)
                    }
                }
            }
            VOD_REGION.find(text)?.let { m ->
                if (m.groupValues[1] in VOD_REGION_SUFFIXES) {
                    region = m.groupValues[1].lowercase(Locale.ROOT)
                    text = text.substring(0, m.range.first)
                }
            }
        }

        val flags = mutableSetOf<VersionFlag>()
        var bestTier: QualityTier? = null
        var resolution: Int? = null
        var fps: Int? = null
        fun offerTier(tier: QualityTier) {
            if (bestTier == null || tier.rank > bestTier!!.rank) bestTier = tier
        }
        fun offerResolution(lines: Int) {
            val (tier, height) = tierForLines(lines)
            offerTier(tier)
            if (resolution == null || height > resolution!!) resolution = height
        }

        text = LOW_SOURCE_WORD.replace(text) { flags += VersionFlag.LOW_SOURCE; " " }
        text = LOW_SOURCE_BRACKETED.replace(text) { flags += VersionFlag.LOW_SOURCE; " " }
        text = RES_FPS.replace(text) { m ->
            offerResolution(m.groupValues[1].toInt())
            fps = m.groupValues[2].toInt()
            " "
        }
        text = RES.replace(text) { m -> offerResolution(m.groupValues[1].toInt()); " " }
        text = FPS.replace(text) { m -> fps = fps ?: m.groupValues[1].toInt(); " " }
        var hdr = false
        text = HDR.replace(text) { hdr = true; " " }
        text = NAMED_QUALITY.replace(text) { m ->
            offerTier(
                when (m.groupValues[1].uppercase(Locale.ROOT)) {
                    "8K" -> QualityTier.UHD_8K
                    "4K", "UHD" -> QualityTier.UHD
                    "FHD" -> QualityTier.FHD
                    "HD" -> QualityTier.HD
                    else -> QualityTier.SD
                },
            )
            " "
        }
        if ("4K" in platforms) {
            platforms.remove("4K")
            offerTier(QualityTier.UHD)
        }
        if (mode == TokenizeMode.LIVE) {
            text = RAW_LABEL.replace(text) { flags += VersionFlag.RAW_SOURCE; " " }
        }
        var codec: String? = null
        text = HEVC.replace(text) { codec = "hevc"; " " }
        text = AVC.replace(text) { codec = codec ?: "avc"; " " }
        text = MULTI_SUB.replace(text) { flags += VersionFlag.MULTI_SUB; " " }
        text = MULTI_AUDIO.replace(text) { flags += VersionFlag.MULTI_AUDIO; " " }
        text = DUAL.replace(text) { flags += VersionFlag.DUAL_AUDIO; " " }
        text = BACKUP_BRACKETED.replace(text) { flags += VersionFlag.BACKUP; " " }
        text = cleanup(text)
        text = BACKUP_TRAILING.replace(text) { flags += VersionFlag.BACKUP; " " }
        text = cleanup(text)

        var timeshift = 0
        if (mode == TokenizeMode.LIVE) {
            TIMESHIFT.find(text)?.let { m ->
                timeshift = m.groupValues[1].toInt()
                text = cleanup(text.substring(0, m.range.first))
            }
        }

        val tier = when {
            VersionFlag.LOW_SOURCE in flags -> QualityTier.LOW_SOURCE
            bestTier != null -> bestTier
            VersionFlag.RAW_SOURCE in flags -> QualityTier.RAW
            else -> QualityTier.UNLABELED
        }

        val cleanTitle = TRAILING_ARTICLE.matchEntire(text)
            ?.let { "${it.groupValues[2]} ${it.groupValues[1]}" }
            ?: text

        return TitleTokens(
            raw = name,
            cleanTitle = cleanTitle,
            baseName = normalize(cleanTitle),
            language = language,
            platforms = platforms.toList(),
            region = region,
            quality = tier,
            resolution = resolution ?: tier.nominalHeight,
            hdr = hdr,
            codec = codec,
            fps = fps,
            flags = flags.toSet(),
            year = year,
            date = date,
            timeshiftHours = timeshift,
            isSeparator = isSeparator,
        )
    }

    /**
     * Language signalled by a VOD category name (`EN - MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ` → `en`,
     * `IN - HINDI` → `in`), or `null`. Used when a title's own prefix is a platform code.
     */
    fun languageOfCategory(categoryName: String?): String? =
        categoryName?.takeIf { it.isNotBlank() }?.let { tokenize(it, TokenizeMode.VOD).language }

    /** Matching key for free text: fold, strip diacritics, lowercase, letters/digits only. */
    fun normalize(text: String): String {
        val lower = fold(text).lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length + 8)
        var prev = ' '
        for (c in lower) {
            when {
                c == '\'' -> Unit
                c == '&' -> sb.append(" and ")
                c == '+' -> sb.append(" plus ")
                c.isLetterOrDigit() -> {
                    // Split letter/digit boundaries so "More4"/"More 4" and "FS1"/"FS 1" agree.
                    if (prev.isLetterOrDigit() && c.isDigit() != prev.isDigit()) sb.append(' ')
                    sb.append(c)
                }
                else -> sb.append(' ')
            }
            if (c != '\'') prev = c
        }
        return SPACES.replace(sb, " ").trim()
    }

    /**
     * ASCII-folds superscripts, modifier letters and small capitals, then NFKD with combining
     * marks removed. Superscript runs glued to ordinary text get a separating space.
     */
    internal fun fold(input: String): String {
        val sb = StringBuilder(input.length + 4)
        var prevSuper = false
        var prevAlnum = false
        for (ch in input) {
            val isSuper = isSuperscript(ch)
            val alnum = isSuper || ch.isLetterOrDigit() || ch in SMALL_CAPS
            if (alnum && prevAlnum && isSuper != prevSuper) sb.append(' ')
            sb.append(SMALL_CAPS[ch] ?: ch)
            prevSuper = isSuper
            prevAlnum = alnum
        }
        var s = Normalizer.normalize(sb, Normalizer.Form.NFKD)
        s = COMBINING_MARKS.replace(s, "")
        s = DASHES.replace(s, "-")
        s = QUOTES.replace(s, "'")
        return s
    }

    private fun isSuperscript(c: Char): Boolean =
        c in 'ᴬ'..'ᵪ' || c in 'ᶛ'..'ᶿ' || c in '⁰'..'₟' ||
            c == '²' || c == '³' || c == '¹' || c == 'ⱽ' ||
            c in 'ʰ'..'ʸ' || c == 'ꟸ' || c == 'ꟹ'

    /** Returns (language, platforms) or `null` when any code is unknown. */
    private fun parseVodPrefix(codes: String): Pair<String?, List<String>>? {
        val segments = codes.split('-')
        var language: String? = null
        val platforms = mutableListOf<String>()
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            val next = segments.getOrNull(i + 1)
            when {
                seg == "IN" && next != null && next.length == 2 && next !in PLATFORM_CODES -> {
                    if (language == null) language = if (next == "EN") "en" else "in-${next.lowercase(Locale.ROOT)}"
                    i++
                }
                seg in LANGUAGE_CODES -> if (language == null) language = seg.lowercase(Locale.ROOT)
                seg in PLATFORM_CODES -> platforms += seg
                else -> return null
            }
            i++
        }
        return language to platforms
    }

    private fun tierForLines(lines: Int): Pair<QualityTier, Int> = when {
        lines >= 4320 -> QualityTier.UHD_8K to 4320
        lines == 3840 || lines >= 2160 -> QualityTier.UHD to 2160
        lines >= 1080 -> QualityTier.FHD to lines
        lines >= 720 -> QualityTier.HD to lines
        else -> QualityTier.SD to lines
    }

    private fun cleanup(text: String): String {
        var s = text
        while (true) {
            val next = EMPTY_BRACKETS.replace(s, " ")
            if (next == s) break
            s = next
        }
        s = SPACES.replace(s, " ")
        return EDGE_PUNCT.replace(s, "")
    }
}

package app.playlistplayer.core.parsing.xmltv

/**
 * Parses XMLTV timestamps to UTC epoch milliseconds without allocating java.time objects (the
 * provider feed has hundreds of thousands of programmes).
 *
 * Accepted: `YYYYMMDDhhmmss ±HHMM` (the XMLTV form; the provider sends `+0100`), the same without
 * a space before the offset, `±HH:MM`, `±HH`, `Z`/`UTC`/`GMT`, and no offset at all (treated as UTC).
 * Trailing date/time fields may be omitted (`YYYYMMDDhhmm`, `YYYYMMDDhh`, `YYYYMMDD`, `YYYYMM`,
 * `YYYY`); missing fields default to the start of the period. Returns `null` for anything else,
 * including out-of-range fields (month 13, 31 February, minute 60).
 */
object XmltvTime {

    fun parseMillis(value: String?): Long? {
        if (value == null) return null
        val s = value.trim()
        var digits = 0
        while (digits < s.length && digits < 14 && s[digits].isAsciiDigit()) digits++
        if (digits < 4 || digits % 2 != 0) return null
        if (digits < s.length && s[digits].isAsciiDigit()) return null // more than 14 digits

        val year = num(s, 0, 4)
        val month = if (digits >= 6) num(s, 4, 2) else 1
        val day = if (digits >= 8) num(s, 6, 2) else 1
        val hour = if (digits >= 10) num(s, 8, 2) else 0
        val minute = if (digits >= 12) num(s, 10, 2) else 0
        val second = if (digits >= 14) num(s, 12, 2) else 0
        if (month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

        val offsetMinutes = parseOffsetMinutes(s.substring(digits).trim()) ?: return null
        val epochDay = epochDay(year, month, day)
        val seconds = epochDay * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1_000L
    }

    /** Offset in minutes east of UTC, `0` for an empty string, `null` if unrecognised. */
    internal fun parseOffsetMinutes(raw: String): Int? {
        if (raw.isEmpty()) return 0
        when (raw.uppercase()) {
            "Z", "UTC", "GMT", "UT" -> return 0
        }
        val sign = when (raw[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val body = raw.substring(1).replace(":", "")
        if (body.isEmpty() || !body.all { it.isAsciiDigit() }) return null
        val (h, m) = when (body.length) {
            2 -> body.toInt() to 0
            4 -> body.substring(0, 2).toInt() to body.substring(2, 4).toInt()
            else -> return null
        }
        if (h > 18 || m > 59) return null
        return sign * (h * 60 + m)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun num(s: String, start: Int, len: Int): Int {
        var v = 0
        for (i in start until start + len) v = v * 10 + (s[i] - '0')
        return v
    }

    private fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if (isLeap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's days_from_civil). */
    private fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}

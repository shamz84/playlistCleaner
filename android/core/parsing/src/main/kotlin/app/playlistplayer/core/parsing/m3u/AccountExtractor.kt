package app.playlistplayer.core.parsing.m3u

import app.playlistplayer.core.model.XtreamAccount

/**
 * Result of account derivation over a playlist's URLs.
 *
 * @property account the majority account, or null when no URL was Xtream-shaped.
 * @property mixedAccounts true when more than one distinct account (host/port/user/pass) appeared.
 *   The playlist is still accepted; only the majority account is used.
 * @property matchingUrls URLs that used [account].
 * @property xtreamUrls URLs that were Xtream-shaped at all.
 * @property totalUrls every URL seen.
 */
data class AccountExtraction(
    val account: XtreamAccount?,
    val mixedAccounts: Boolean,
    val distinctAccounts: Int,
    val matchingUrls: Int,
    val xtreamUrls: Int,
    val totalUrls: Int,
) {
    /** True when most URLs were Xtream-shaped, so Xtream processing can default on. */
    val isXtreamShaped: Boolean get() = account != null && xtreamUrls * 2 > totalUrls

    override fun toString(): String =
        "AccountExtraction(account=${if (account == null) "null" else "***"}, mixedAccounts=$mixedAccounts, " +
            "distinctAccounts=$distinctAccounts, matchingUrls=$matchingUrls, xtreamUrls=$xtreamUrls, totalUrls=$totalUrls)"
}

/**
 * Derives the Xtream account from playlist URLs by majority vote. Feed every URL with [add]
 * while streaming the playlist, then call [result]. Memory is bounded by the number of distinct
 * accounts, not the number of URLs.
 */
class AccountExtractor {
    private val counts = LinkedHashMap<XtreamAccount, Int>()
    private var xtreamUrls = 0
    private var totalUrls = 0

    fun add(url: String) {
        totalUrls++
        val parsed = XtreamUrl.parse(url) ?: return
        xtreamUrls++
        val account = parsed.account
        counts[account] = (counts[account] ?: 0) + 1
    }

    fun result(): AccountExtraction {
        // maxBy keeps the first maximum, so ties resolve to the account seen first in the file.
        val majority = counts.entries.maxByOrNull { it.value }
        return AccountExtraction(
            account = majority?.key,
            mixedAccounts = counts.size > 1,
            distinctAccounts = counts.size,
            matchingUrls = majority?.value ?: 0,
            xtreamUrls = xtreamUrls,
            totalUrls = totalUrls,
        )
    }

    companion object {
        /** Derives the account from a single URL, e.g. an M3U URL `get.php?username=&password=`. */
        fun fromUrl(url: String): XtreamAccount? = XtreamUrl.parse(url)?.account

        fun fromUrls(urls: Iterable<String>): AccountExtraction =
            AccountExtractor().apply { urls.forEach(::add) }.result()
    }
}

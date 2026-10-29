package app.playlistplayer.core.parsing.xmltv

import java.util.Locale

/** An XMLTV `<channel>`. [displayNames] keep document order; used for name-based matching. */
data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String?,
)

/** One `<episode-num>`; [system] is e.g. `xmltv_ns` or `onscreen`. */
data class XmltvEpisodeNumber(
    val system: String?,
    val value: String,
)

/**
 * An XMLTV `<programme>`. [channelId] is the `channel` attribute exactly as in the document (the
 * caller maps it to playlist entries, see [EpgChannelMatcher]). Times are UTC epoch millis.
 * [stopMillis] is `null` when the document omits `stop` (allowed by the DTD); the caller may close
 * it at the next programme's start. Only the first `title`/`sub-title`/`desc` is kept.
 */
data class XmltvProgramme(
    val channelId: String,
    val startMillis: Long,
    val stopMillis: Long?,
    val title: String?,
    val subTitle: String?,
    val description: String?,
    val categories: List<String>,
    val episodeNumbers: List<XmltvEpisodeNumber>,
)

/**
 * What to keep from an XMLTV document.
 *
 * @param wantedChannelIds channel ids to keep, compared case-insensitively. `null` keeps every
 *   `<channel>` (use this when name-based matching needs all display names; the programme set can
 *   then be narrowed in [XmltvHandler.onChannelsComplete]). Programmes for ids outside the final
 *   set are skipped without being materialized.
 * @param fromMillis inclusive window start (UTC epoch millis). Set it in the past to retain
 *   programmes inside the catch-up archive window.
 * @param toMillis exclusive window end. A programme is kept when it overlaps `[from, to)`:
 *   `stop > from && start < to` (or `from <= start < to` when it has no stop).
 * @param batchSize programmes per [XmltvHandler.onProgrammes] call.
 */
data class XmltvParseOptions(
    val wantedChannelIds: Set<String>?,
    val fromMillis: Long = Long.MIN_VALUE,
    val toMillis: Long = Long.MAX_VALUE,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(fromMillis < toMillis) { "empty window" }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE: Int = 1000
    }
}

/**
 * Receives parser output. Calls happen on the parsing thread, in document order.
 *
 * **Transaction contract:** batches are delivered while parsing continues, so a later failure can
 * follow batches that were already delivered. The caller must write batches into a staging
 * generation (not the live one) and only publish it after [XmltvParser.parse] returns normally. If
 * `parse` throws [XmltvParseException], discard the staging generation; the exception's
 * [XmltvParseException.batchesEmitted] says how much had been delivered.
 */
interface XmltvHandler {
    /** A wanted `<channel>`. */
    fun onChannel(channel: XmltvChannel) {}

    /**
     * Called exactly once: just before the first `<programme>` is evaluated, or at the end of the
     * document if there are none. XMLTV puts all channels before programmes, so at this point every
     * channel has been seen. Return the channel ids whose programmes should be kept (compared
     * case-insensitively), e.g. the result of [EpgChannelMatcher], or `null` to keep using
     * [XmltvParseOptions.wantedChannelIds]. Channels appearing after programmes are still reported
     * to [onChannel] but do not change the programme set.
     */
    fun onChannelsComplete(): Set<String>? = null

    /** A batch of at most [XmltvParseOptions.batchSize] kept programmes. The list is not reused. */
    fun onProgrammes(batch: List<XmltvProgramme>)
}

/** Counters for one successful parse. */
data class XmltvParseResult(
    val channelsSeen: Int,
    val channelsKept: Int,
    val programmesSeen: Int,
    val programmesKept: Int,
    /** Programmes skipped because of a missing/unparseable start or channel, or stop <= start. */
    val programmesInvalid: Int,
    val batchesEmitted: Int,
)

/**
 * The document is not a well-formed XMLTV `<tv>` document (malformed or truncated XML, HTML/JSON
 * error page, wrong root element, corrupt gzip, read failure). [batchesEmitted] /
 * [programmesEmitted] report what reached [XmltvHandler.onProgrammes] before the failure; the
 * partially filled batch is dropped. See [XmltvHandler] for the roll-back contract.
 */
class XmltvParseException(
    message: String,
    val location: String?,
    val batchesEmitted: Int,
    val programmesEmitted: Int,
    cause: Throwable? = null,
) : Exception(if (location != null) "$message ($location)" else message, cause)

internal fun String.idKey(): String = trim().lowercase(Locale.ROOT)

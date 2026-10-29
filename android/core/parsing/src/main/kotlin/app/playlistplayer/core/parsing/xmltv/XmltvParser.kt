package app.playlistplayer.core.parsing.xmltv

import java.io.IOException
import java.io.InputStream

/**
 * Single-pass streaming XMLTV reader.
 *
 * - Gzip is detected by magic bytes ([GzipSniffer]), regardless of HTTP headers.
 * - `<channel>` elements outside [XmltvParseOptions.wantedChannelIds] and `<programme>` elements
 *   for unwanted channels or outside the time window are skipped by depth counting, without
 *   building strings for their children.
 * - Kept programmes are delivered in batches through [XmltvHandler.onProgrammes]; nothing else is
 *   accumulated, so heap use is bounded by the batch size plus the kept channels.
 *
 * The XML backend is pluggable ([XmlPullSourceFactory]): [StaxXmlPullSource] on the JVM, an
 * `android.util.Xml` adapter on Android.
 */
class XmltvParser(
    private val pullFactory: XmlPullSourceFactory,
) {

    /**
     * Parses [input] (not closed by this call). Returns counters on success.
     *
     * @throws XmltvParseException if the document is malformed, truncated or not XMLTV. Batches may
     *   already have been delivered; see the [XmltvHandler] roll-back contract.
     */
    fun parse(input: InputStream, options: XmltvParseOptions, handler: XmltvHandler): XmltvParseResult =
        Run(options, handler).execute(input)

    private inner class Run(
        private val options: XmltvParseOptions,
        private val handler: XmltvHandler,
    ) {
        private val channelKeys: Set<String>? = options.wantedChannelIds?.mapTo(HashSet()) { it.idKey() }
        private var programmeKeys: Set<String>? = null
        private var programmeKeysResolved = false

        private var batch = ArrayList<XmltvProgramme>(options.batchSize)
        private var channelsSeen = 0
        private var channelsKept = 0
        private var programmesSeen = 0
        private var programmesKept = 0
        private var programmesInvalid = 0
        private var batchesEmitted = 0
        private var programmesEmitted = 0

        private lateinit var pull: XmlPullSource

        fun execute(input: InputStream): XmltvParseResult {
            val stream = try {
                GzipSniffer.wrap(input)
            } catch (e: IOException) {
                throw failure("Unreadable or corrupt gzip stream: ${e.message}", null, e)
            }
            pull = try {
                pullFactory.open(stream)
            } catch (e: XmlPullException) {
                throw failure("Not an XML document: ${e.message}", e.location, e)
            }
            try {
                readDocument()
            } catch (e: XmlPullException) {
                throw failure("Malformed XMLTV: ${e.message}", e.location, e)
            } finally {
                pull.close()
            }
            resolveProgrammeKeys()
            flush()
            return XmltvParseResult(
                channelsSeen = channelsSeen,
                channelsKept = channelsKept,
                programmesSeen = programmesSeen,
                programmesKept = programmesKept,
                programmesInvalid = programmesInvalid,
                batchesEmitted = batchesEmitted,
            )
        }

        private fun readDocument() {
            var event = pull.next()
            while (event == XmlPullEvent.TEXT) event = pull.next()
            if (event != XmlPullEvent.START_TAG) {
                throw failure("Document has no root element", pull.location)
            }
            if (pull.name != "tv") {
                throw failure("Root element is <${pull.name}>, expected <tv>", pull.location)
            }
            while (true) {
                when (pull.next()) {
                    XmlPullEvent.START_TAG -> when (pull.name) {
                        "channel" -> readChannel()
                        "programme" -> readProgramme()
                        else -> skipElement()
                    }
                    XmlPullEvent.END_TAG -> return // </tv>
                    XmlPullEvent.TEXT -> Unit
                    XmlPullEvent.END_DOCUMENT -> throw truncated()
                }
            }
        }

        private fun readChannel() {
            channelsSeen++
            val id = pull.attribute("id")?.trim()
            if (id.isNullOrEmpty() || (channelKeys != null && id.idKey() !in channelKeys)) {
                skipElement()
                return
            }
            val names = ArrayList<String>(2)
            var icon: String? = null
            forEachChild { child ->
                when (child) {
                    "display-name" -> readText()?.let { names += it }
                    "icon" -> {
                        if (icon == null) icon = pull.attribute("src")?.trim()?.ifEmpty { null }
                        skipElement()
                    }
                    else -> skipElement()
                }
            }
            channelsKept++
            handler.onChannel(XmltvChannel(id, names, icon))
        }

        private fun readProgramme() {
            programmesSeen++
            resolveProgrammeKeys()
            val channel = pull.attribute("channel")?.trim()
            if (channel.isNullOrEmpty()) {
                programmesInvalid++
                skipElement()
                return
            }
            val keys = programmeKeys
            if (keys != null && channel.idKey() !in keys) {
                skipElement()
                return
            }
            val start = XmltvTime.parseMillis(pull.attribute("start"))
            val stop = XmltvTime.parseMillis(pull.attribute("stop"))
            if (start == null || (stop != null && stop <= start)) {
                programmesInvalid++
                skipElement()
                return
            }
            val inWindow = if (stop != null) {
                stop > options.fromMillis && start < options.toMillis
            } else {
                start >= options.fromMillis && start < options.toMillis
            }
            if (!inWindow) {
                skipElement()
                return
            }

            var title: String? = null
            var subTitle: String? = null
            var desc: String? = null
            var categories: MutableList<String>? = null
            var episodes: MutableList<XmltvEpisodeNumber>? = null
            forEachChild { child ->
                when (child) {
                    "title" -> readText().let { if (title == null) title = it }
                    "sub-title" -> readText().let { if (subTitle == null) subTitle = it }
                    "desc" -> readText().let { if (desc == null) desc = it }
                    "category" -> readText()?.let {
                        (categories ?: ArrayList<String>(2).also { list -> categories = list }).add(it)
                    }
                    "episode-num" -> {
                        val system = pull.attribute("system")?.trim()?.ifEmpty { null }
                        readText()?.let {
                            (episodes ?: ArrayList<XmltvEpisodeNumber>(2).also { list -> episodes = list }).add(
                                XmltvEpisodeNumber(system, it),
                            )
                        }
                    }
                    else -> skipElement()
                }
            }
            programmesKept++
            batch += XmltvProgramme(
                channelId = channel,
                startMillis = start,
                stopMillis = stop,
                title = title,
                subTitle = subTitle,
                description = desc,
                categories = categories ?: emptyList(),
                episodeNumbers = episodes ?: emptyList(),
            )
            if (batch.size >= options.batchSize) flush()
        }

        private fun resolveProgrammeKeys() {
            if (programmeKeysResolved) return
            programmeKeysResolved = true
            programmeKeys = handler.onChannelsComplete()?.mapTo(HashSet()) { it.idKey() } ?: channelKeys
        }

        private fun flush() {
            if (batch.isEmpty()) return
            val out = batch
            batch = ArrayList(options.batchSize)
            handler.onProgrammes(out)
            batchesEmitted++
            programmesEmitted += out.size
        }

        /** Iterates direct child elements of the current element; [block] must consume each one. */
        private inline fun forEachChild(block: (String) -> Unit) {
            while (true) {
                when (pull.next()) {
                    XmlPullEvent.START_TAG -> block(pull.name)
                    XmlPullEvent.END_TAG -> return
                    XmlPullEvent.TEXT -> Unit
                    XmlPullEvent.END_DOCUMENT -> throw truncated()
                }
            }
        }

        /** Reads the text of the current element (nested elements skipped); trimmed, null if blank. */
        private fun readText(): String? {
            var first: String? = null
            var builder: StringBuilder? = null
            var depth = 1
            while (true) {
                when (pull.next()) {
                    XmlPullEvent.TEXT -> if (depth == 1) {
                        val t = pull.text
                        when {
                            first == null -> first = t
                            builder == null -> builder = StringBuilder(first).append(t)
                            else -> builder.append(t)
                        }
                    }
                    XmlPullEvent.START_TAG -> depth++
                    XmlPullEvent.END_TAG -> if (--depth == 0) {
                        val text = (builder?.toString() ?: first)?.trim()
                        return if (text.isNullOrEmpty()) null else text
                    }
                    XmlPullEvent.END_DOCUMENT -> throw truncated()
                }
            }
        }

        /** Skips the current element and its subtree. */
        private fun skipElement() {
            var depth = 1
            while (depth > 0) {
                when (pull.next()) {
                    XmlPullEvent.START_TAG -> depth++
                    XmlPullEvent.END_TAG -> depth--
                    XmlPullEvent.TEXT -> Unit
                    XmlPullEvent.END_DOCUMENT -> throw truncated()
                }
            }
        }

        private fun truncated() = failure("Unexpected end of document (truncated XMLTV)", pull.location)

        private fun failure(message: String, location: String?, cause: Throwable? = null) =
            XmltvParseException(message, location, batchesEmitted, programmesEmitted, cause)
    }
}

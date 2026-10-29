package app.playlistplayer.core.parsing.xmltv

import java.io.Closeable
import java.io.InputStream

/**
 * Minimal pull-parser abstraction used by [XmltvParser].
 *
 * `:core:parsing` is a pure JVM module, but Android does not ship `javax.xml.stream`, and the JVM
 * does not ship `org.xmlpull.v1.XmlPullParser`. The parser therefore only talks to this interface:
 *
 * - JVM (unit tests, tooling): [StaxXmlPullSource] over `javax.xml.stream.XMLStreamReader`.
 * - Android: an adapter over `android.util.Xml.newPullParser()` (namespaces off), living in an
 *   Android module, mapping `XmlPullParser.next()` events: `START_TAG` -> [XmlPullEvent.START_TAG],
 *   `END_TAG` -> [XmlPullEvent.END_TAG], `TEXT` -> [XmlPullEvent.TEXT], `END_DOCUMENT` ->
 *   [XmlPullEvent.END_DOCUMENT]; `XmlPullParserException`/`IOException` -> [XmlPullException].
 *
 * Implementations must not resolve external entities or fetch DTDs.
 */
interface XmlPullSource : Closeable {
    /**
     * Advances to the next event. Comments, processing instructions, the DOCTYPE and the document
     * start are skipped. Adjacent text/CDATA may be reported as one or several [XmlPullEvent.TEXT]
     * events. Throws [XmlPullException] on malformed input or an I/O failure.
     */
    fun next(): XmlPullEvent

    /** Local element name; valid after [XmlPullEvent.START_TAG] or [XmlPullEvent.END_TAG]. */
    val name: String

    /** Attribute value by (unprefixed) name; valid after [XmlPullEvent.START_TAG]. */
    fun attribute(name: String): String?

    /** Character data; valid after [XmlPullEvent.TEXT]. */
    val text: String

    /** Human-readable position for error messages, e.g. `line 12, column 40`, if known. */
    val location: String?
}

enum class XmlPullEvent { START_TAG, END_TAG, TEXT, END_DOCUMENT }

/** Opens an [XmlPullSource] over an already-decompressed byte stream (encoding from the prolog). */
fun interface XmlPullSourceFactory {
    fun open(input: InputStream): XmlPullSource
}

/** Malformed XML or a read failure reported by an [XmlPullSource]. */
class XmlPullException(
    message: String,
    val location: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

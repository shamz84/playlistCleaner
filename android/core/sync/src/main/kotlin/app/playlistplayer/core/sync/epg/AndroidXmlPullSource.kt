package app.playlistplayer.core.sync.epg

import android.util.Xml
import app.playlistplayer.core.parsing.xmltv.XmlPullEvent
import app.playlistplayer.core.parsing.xmltv.XmlPullException
import app.playlistplayer.core.parsing.xmltv.XmlPullSource
import app.playlistplayer.core.parsing.xmltv.XmlPullSourceFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/**
 * Android [XmlPullSource] over `android.util.Xml.newPullParser()` (KXmlParser), namespaces off.
 * `next()` already resolves entity references, reports CDATA as text and skips comments,
 * processing instructions and the DOCTYPE (external DTDs are never fetched).
 */
class AndroidXmlPullSource private constructor(
    private val parser: XmlPullParser,
) : XmlPullSource {

    override fun next(): XmlPullEvent {
        try {
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> return XmlPullEvent.START_TAG
                    XmlPullParser.END_TAG -> return XmlPullEvent.END_TAG
                    XmlPullParser.TEXT -> return XmlPullEvent.TEXT
                    XmlPullParser.END_DOCUMENT -> return XmlPullEvent.END_DOCUMENT
                    else -> Unit
                }
            }
        } catch (e: XmlPullParserException) {
            throw XmlPullException(e.message ?: "Malformed XML", location, e)
        } catch (e: IOException) {
            throw XmlPullException(e.message ?: "Read failed", location, e)
        } catch (e: RuntimeException) {
            // KXmlParser reports some malformed input (bad entities, truncated multi-byte) this way.
            throw XmlPullException(e.message ?: "Malformed XML", location, e)
        }
    }

    override val name: String
        get() = parser.name

    override fun attribute(name: String): String? = parser.getAttributeValue(null, name)

    override val text: String
        get() = parser.text.orEmpty()

    override val location: String?
        get() = parser.lineNumber.takeIf { it >= 0 }?.let { "line $it, column ${parser.columnNumber}" }

    /** The caller owns the input stream; the parser holds no other resources. */
    override fun close() = Unit

    companion object Factory : XmlPullSourceFactory {
        override fun open(input: InputStream): XmlPullSource {
            val parser = Xml.newPullParser()
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                // null encoding: detect from the BOM / XML prolog (UTF-8 by default).
                parser.setInput(input, null)
            } catch (e: XmlPullParserException) {
                throw XmlPullException(e.message ?: "Not XML", null, e)
            }
            return AndroidXmlPullSource(parser)
        }
    }
}

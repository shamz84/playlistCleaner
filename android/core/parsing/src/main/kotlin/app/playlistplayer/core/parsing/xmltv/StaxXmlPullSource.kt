package app.playlistplayer.core.parsing.xmltv

import java.io.IOException
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * JVM [XmlPullSource] over `javax.xml.stream` (StAX). Not available on Android; see [XmlPullSource]
 * for the Android adapter contract. DTD processing and external entities are disabled, so only
 * the five predefined XML entities and numeric character references are accepted.
 */
class StaxXmlPullSource private constructor(
    private val reader: XMLStreamReader,
) : XmlPullSource {

    override fun next(): XmlPullEvent {
        try {
            while (true) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> return XmlPullEvent.START_TAG
                    XMLStreamConstants.END_ELEMENT -> return XmlPullEvent.END_TAG
                    XMLStreamConstants.CHARACTERS,
                    XMLStreamConstants.CDATA,
                    XMLStreamConstants.SPACE,
                    -> return XmlPullEvent.TEXT
                    XMLStreamConstants.END_DOCUMENT -> return XmlPullEvent.END_DOCUMENT
                    else -> Unit // comment, PI, DTD, entity declaration: skip
                }
            }
        } catch (e: XMLStreamException) {
            throw XmlPullException(e.message ?: "Malformed XML", location, e)
        } catch (e: IOException) {
            throw XmlPullException(e.message ?: "Read failed", location, e)
        }
    }

    override val name: String
        get() = reader.localName

    override fun attribute(name: String): String? = reader.getAttributeValue(null, name)

    override val text: String
        get() = reader.text

    override val location: String?
        get() = reader.location?.let { "line ${it.lineNumber}, column ${it.columnNumber}" }

    override fun close() {
        try {
            reader.close()
        } catch (_: XMLStreamException) {
            // Closing the reader does not close the underlying stream; nothing to report.
        }
    }

    companion object Factory : XmlPullSourceFactory {
        private val inputFactory: XMLInputFactory by lazy {
            XMLInputFactory.newDefaultFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
                setProperty(XMLInputFactory.IS_COALESCING, true)
            }
        }

        override fun open(input: InputStream): XmlPullSource {
            try {
                return StaxXmlPullSource(inputFactory.createXMLStreamReader(input))
            } catch (e: XMLStreamException) {
                throw XmlPullException(e.message ?: "Not XML", e.location?.let { "line ${it.lineNumber}" }, e)
            }
        }
    }
}

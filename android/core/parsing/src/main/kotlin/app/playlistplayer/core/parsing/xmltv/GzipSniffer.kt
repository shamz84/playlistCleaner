package app.playlistplayer.core.parsing.xmltv

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Detects gzip by its magic bytes (`1f 8b`) rather than trusting `Content-Type`/`Content-Encoding`:
 * EPG override hosts commonly serve `.xml.gz` as `application/octet-stream` or `text/xml`.
 * A body that the HTTP client already transparently decompressed has no magic and passes through.
 */
object GzipSniffer {
    private const val BUFFER = 64 * 1024

    /** Returns a stream yielding the decompressed bytes if [input] is gzip, else [input]'s bytes. */
    fun wrap(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, BUFFER)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = if (b0 == -1) -1 else buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered, BUFFER) else buffered
    }
}

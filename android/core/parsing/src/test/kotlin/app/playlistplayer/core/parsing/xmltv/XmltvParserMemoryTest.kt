package app.playlistplayer.core.parsing.xmltv

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.InputStream

/**
 * Streams a synthetic ~50 MB XMLTV document (generated lazily, never held in memory or committed)
 * with a small wanted set, and checks that retained heap stays bounded mid-parse: if the parser
 * kept every programme, ~150k programme objects with descriptions would retain well over 100 MB.
 */
class XmltvParserMemoryTest {

    private class SyntheticXmltv(
        private val channels: Int,
        private val programmesPerChannel: Int,
        private val onProgress: (bytes: Long) -> Unit,
    ) : InputStream() {
        private var chunk = HEADER.toByteArray()
        private var pos = 0
        private var channel = 0
        private var programmeChannel = 0
        private var done = false
        var bytesProduced = 0L
            private set
        var programmesProduced = 0
            private set

        private fun refill(): Boolean {
            if (done) return false
            val sb = StringBuilder(64 * 1024)
            when {
                channel < channels -> {
                    while (channel < channels && sb.length < 60_000) {
                        sb.append("<channel id=\"ch").append(channel).append(".test\"><display-name>Test Channel ")
                            .append(channel).append(" HD</display-name><icon src=\"http://example.invalid/")
                            .append(channel).append(".png\"/></channel>\n")
                        channel++
                    }
                }
                programmeChannel < channels -> {
                    val c = programmeChannel++
                    for (n in 0 until programmesPerChannel) {
                        sb.append("<programme start=\"").append(XmltvTestData.stamp(n * 30))
                            .append("\" stop=\"").append(XmltvTestData.stamp(n * 30 + 30))
                            .append("\" channel=\"ch").append(c).append(".test\" ><title>Programme ")
                            .append(n).append(" on ").append(c).append("</title><desc>")
                            .append(DESC).append(n).append("</desc><category>Category ").append(n % 7)
                            .append("</category></programme>\n")
                        programmesProduced++
                    }
                }
                else -> {
                    sb.append("</tv>\n")
                    done = true
                }
            }
            chunk = sb.toString().toByteArray()
            pos = 0
            return true
        }

        override fun read(): Int {
            while (pos >= chunk.size) if (!refill()) return -1
            bytesProduced++
            return chunk[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) if (!refill()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            bytesProduced += n
            onProgress(bytesProduced)
            return n
        }

        companion object {
            private const val HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><!DOCTYPE tv SYSTEM \"xmltv.dtd\">" +
                "<tv generator-info-name=\"synthetic\">\n"
            private val DESC = "A synthetic description that is long enough to look like a real guide entry, " +
                "with cast, plot and a little more padding so each programme is about four hundred bytes. "
        }
    }

    private fun usedHeapAfterGc(): Long {
        val rt = Runtime.getRuntime()
        repeat(2) { System.gc() }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test(timeout = 60_000)
    fun fiftyMegabyteStreamWithSmallWantedSetUsesBoundedMemory() {
        val channels = 2_000
        val perChannel = 75 // 150k programmes of ~370 bytes, ~55 MB
        val wanted = (0 until 10).map { "CH${it * 150}.TEST" }.toSet() // case-insensitive

        val baseline = usedHeapAfterGc()
        var probed = false
        var midParseRetained = 0L
        val stream = SyntheticXmltv(channels, perChannel) { bytes ->
            if (!probed && bytes >= 45L * 1024 * 1024) {
                probed = true
                midParseRetained = usedHeapAfterGc() - baseline // may be slightly negative (GC noise)
            }
        }

        var maxBatch = 0
        var kept = 0
        val handlerChannels = mutableListOf<XmltvChannel>()
        val started = System.nanoTime()
        val result = XmltvParser(StaxXmlPullSource).parse(
            stream,
            XmltvParseOptions(wantedChannelIds = wanted, batchSize = 500),
            object : XmltvHandler {
                override fun onChannel(channel: XmltvChannel) {
                    handlerChannels += channel
                }

                override fun onProgrammes(batch: List<XmltvProgramme>) {
                    maxBatch = maxOf(maxBatch, batch.size)
                    kept += batch.size // simulate a DB write: do not retain the batch
                }
            },
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        println(
            "Synthetic XMLTV: ${stream.bytesProduced / (1024 * 1024)} MB, ${stream.programmesProduced} programmes, " +
                "kept $kept, ${elapsedMs} ms, retained mid-parse ${midParseRetained / (1024 * 1024)} MB",
        )
        assertThat(stream.bytesProduced).isAtLeast(50L * 1024 * 1024)
        assertThat(result.programmesSeen).isEqualTo(channels * perChannel)
        assertThat(result.channelsSeen).isEqualTo(channels)
        assertThat(handlerChannels).hasSize(10)
        assertThat(kept).isEqualTo(10 * perChannel)
        assertThat(result.programmesKept).isEqualTo(kept)
        assertThat(maxBatch).isAtMost(500)
        assertThat(probed).isTrue()
        assertThat(midParseRetained).isLessThan(32L * 1024 * 1024)
    }
}

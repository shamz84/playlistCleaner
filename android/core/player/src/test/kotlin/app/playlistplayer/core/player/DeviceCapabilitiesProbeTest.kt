package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceCapabilitiesProbeTest {

    private val hdr10 = 2 // Display.HdrCapabilities.HDR_TYPE_HDR10

    private fun dec(mime: String, height: Int, hdr: Boolean = false, hw: Boolean = true) =
        VideoDecoderInfo(mime, height, hdr, hw)

    @Test
    fun fireTv4kMax_like() {
        val caps = DeviceCapabilitiesProbe.derive(
            decoders = listOf(dec("video/avc", 2160), dec("video/hevc", 2160, hdr = true)),
            displayMaxShortEdge = 2160,
            displayHdrTypes = listOf(hdr10),
        )
        assertThat(caps.maxVideoHeight).isEqualTo(2160)
        assertThat(caps.supportsHevc).isTrue()
        assertThat(caps.supportsHdr).isTrue()
    }

    @Test
    fun display1080p_capsHeight_evenWith4kDecoder() {
        val caps = DeviceCapabilitiesProbe.derive(
            decoders = listOf(dec("video/hevc", 2160, hdr = true)),
            displayMaxShortEdge = 1080,
            displayHdrTypes = emptyList(),
        )
        assertThat(caps.maxVideoHeight).isEqualTo(1080)
        assertThat(caps.supportsHdr).isFalse()
    }

    @Test
    fun softwareOnlyHevc_isIgnoredWhenHardwareDecodersExist() {
        val caps = DeviceCapabilitiesProbe.derive(
            decoders = listOf(dec("video/avc", 1080), dec("video/hevc", 2160, hw = false)),
            displayMaxShortEdge = 2160,
            displayHdrTypes = listOf(hdr10),
        )
        assertThat(caps.supportsHevc).isFalse()
        assertThat(caps.maxVideoHeight).isEqualTo(1080)
    }

    @Test
    fun noDecoderInfo_isUnrestrictedHeight() {
        val caps = DeviceCapabilitiesProbe.derive(emptyList(), null, emptyList())
        assertThat(caps.maxVideoHeight).isEqualTo(Int.MAX_VALUE)
        assertThat(caps.supportsHevc).isFalse()
        assertThat(caps.supportsHdr).isFalse()
    }
}

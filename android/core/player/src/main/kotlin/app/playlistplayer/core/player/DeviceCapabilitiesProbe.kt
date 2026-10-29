package app.playlistplayer.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.MimeTypes
import app.playlistplayer.core.consolidation.DeviceCapabilities

/** One video decoder, reduced to what the capability cap needs. */
data class VideoDecoderInfo(
    val mimeType: String,
    /** Largest standard 16:9 height the decoder supports (2160, 1080, 720, ...), or 0. */
    val maxStandardHeight: Int,
    /** Declares an HDR10/HLG-capable profile (HEVC Main10 HDR10, VP9 profile 2 HDR, AV1 HDR10, Dolby Vision). */
    val supportsHdrProfile: Boolean,
    val isHardwareAccelerated: Boolean,
)

/**
 * Derives the consolidation comparator's [DeviceCapabilities] from the device's decoders and
 * display, so versions above what the device can show rank below versions that fit.
 */
object DeviceCapabilitiesProbe {

    /** Standard (width, height) sizes probed against each decoder, largest first. */
    val STANDARD_SIZES: List<Pair<Int, Int>> = listOf(
        7680 to 4320, 3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 1024 to 576, 720 to 480,
    )

    private val PRIMARY_VIDEO_MIMES = setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)

    /**
     * Pure derivation.
     * - maxVideoHeight: largest height a hardware H.264/HEVC decoder handles (all decoders when no
     *   hardware one is reported), capped by the display's largest mode short edge.
     * - supportsHevc: a (preferably hardware) HEVC decoder exists.
     * - supportsHdr: the display reports HDR types and a decoder declares an HDR profile.
     */
    fun derive(
        decoders: List<VideoDecoderInfo>,
        displayMaxShortEdge: Int?,
        displayHdrTypes: Collection<Int>,
    ): DeviceCapabilities {
        val hardware = decoders.filter { it.isHardwareAccelerated }
        val usable = hardware.ifEmpty { decoders }
        val decoderMax = usable.filter { it.mimeType in PRIMARY_VIDEO_MIMES }.maxOfOrNull { it.maxStandardHeight }
            ?: usable.maxOfOrNull { it.maxStandardHeight }
        val maxHeight = listOfNotNull(decoderMax?.takeIf { it > 0 }, displayMaxShortEdge?.takeIf { it > 0 })
            .minOrNull() ?: Int.MAX_VALUE
        return DeviceCapabilities(
            maxVideoHeight = maxHeight,
            supportsHdr = displayHdrTypes.isNotEmpty() && usable.any { it.supportsHdrProfile },
            supportsHevc = usable.any { it.mimeType == MimeTypes.VIDEO_H265 },
        )
    }

    /** Probes MediaCodecList and the default display. */
    fun probe(context: Context): DeviceCapabilities {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return derive(
            decoders = readDecoders(),
            displayMaxShortEdge = display?.supportedModes?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) },
            displayHdrTypes = display?.let(::hdrTypes).orEmpty(),
        )
    }

    private fun readDecoders(): List<VideoDecoderInfo> {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return emptyList()
        return infos.asSequence()
            .filter { !it.isEncoder }
            .flatMap { info -> info.supportedTypes.asSequence().filter { MimeTypes.isVideo(it) }.map { info to it } }
            .mapNotNull { (info, mime) -> runCatching { describe(info, mime) }.getOrNull() }
            .toList()
    }

    private fun describe(info: MediaCodecInfo, mime: String): VideoDecoderInfo {
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities
        val maxHeight = STANDARD_SIZES.firstOrNull { (w, h) -> video?.isSizeSupported(w, h) == true }?.second ?: 0
        val hdr = mime == MimeTypes.VIDEO_DOLBY_VISION || caps.profileLevels.any { isHdrProfile(mime, it.profile) }
        return VideoDecoderInfo(
            mimeType = MimeTypes.normalizeMimeType(mime),
            maxStandardHeight = maxHeight,
            supportsHdrProfile = hdr,
            isHardwareAccelerated = isHardware(info),
        )
    }

    private fun isHdrProfile(mime: String, profile: Int): Boolean = when (MimeTypes.normalizeMimeType(mime)) {
        MimeTypes.VIDEO_H265 -> profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
            profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        MimeTypes.VIDEO_VP9 -> profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
            profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR
        MimeTypes.VIDEO_AV1 -> profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
            profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
        else -> false
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.startsWith("omx.ffmpeg."))
    }

    @Suppress("DEPRECATION")
    private fun hdrTypes(display: Display): Set<Int> {
        val legacy = runCatching { display.hdrCapabilities?.supportedHdrTypes?.toSet() }.getOrNull().orEmpty()
        val perMode = if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes.toSet() else emptySet()
        return legacy + perMode
    }
}

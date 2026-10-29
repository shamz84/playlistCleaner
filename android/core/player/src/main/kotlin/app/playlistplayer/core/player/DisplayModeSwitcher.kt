package app.playlistplayer.core.player

import android.view.Display
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/** A display mode reduced to what matching needs. */
data class DisplayModeSpec(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
) {
    /** Landscape-normalised long and short edges. */
    val longEdge: Int get() = maxOf(width, height)
    val shortEdge: Int get() = minOf(width, height)
}

/**
 * Pure display-mode selection for "match frame rate".
 *
 * 1. Candidates are modes at least as large as the content (both edges); if none, all modes.
 * 2. A candidate must have a refresh rate equal to the content frame rate (within [tolerance],
 *    relative), or an integer multiple of it (25 fps -> 50 Hz, 24 -> 48/72/120 Hz).
 *    Modes with no match are excluded; with no candidate left the result is null (leave as is).
 * 3. Order: exact refresh over a multiple; then the current mode's resolution (avoids a needless
 *    resolution switch, e.g. a 4K UI keeps 4K for 1080p50 content); then the smaller multiple;
 *    then the larger resolution (the TV usually scales best).
 */
object DisplayModeSelector {

    const val DEFAULT_TOLERANCE: Float = 0.0005f

    fun select(
        modes: List<DisplayModeSpec>,
        current: DisplayModeSpec?,
        contentWidth: Int,
        contentHeight: Int,
        contentFrameRate: Float?,
        tolerance: Float = DEFAULT_TOLERANCE,
    ): DisplayModeSpec? {
        if (modes.isEmpty() || contentFrameRate == null || contentFrameRate <= 0f) return null
        val contentLong = maxOf(contentWidth, contentHeight)
        val contentShort = minOf(contentWidth, contentHeight)
        val bigEnough = modes.filter { it.longEdge >= contentLong && it.shortEdge >= contentShort }
        val pool = bigEnough.ifEmpty { modes }

        data class Scored(val mode: DisplayModeSpec, val multiple: Int)

        val scored = pool.mapNotNull { mode ->
            refreshMultiple(mode.refreshRate, contentFrameRate, tolerance)?.let { Scored(mode, it) }
        }
        if (scored.isEmpty()) return null

        return scored.sortedWith(
            compareBy<Scored> { if (it.multiple == 1) 0 else 1 }
                .thenBy { if (current != null && sameResolution(it.mode, current)) 0 else 1 }
                .thenBy { it.multiple }
                .thenByDescending { it.mode.longEdge.toLong() * it.mode.shortEdge }
                .thenBy { it.mode.modeId },
        ).first().mode
    }

    /** 1 for an exact match, n >= 2 for an integer multiple, null otherwise. */
    fun refreshMultiple(refreshRate: Float, frameRate: Float, tolerance: Float = DEFAULT_TOLERANCE): Int? {
        if (refreshRate <= 0f || frameRate <= 0f) return null
        val n = (refreshRate / frameRate).roundToInt()
        if (n < 1) return null
        val expected = n * frameRate
        return if (abs(refreshRate - expected) / refreshRate <= tolerance) n else null
    }

    private fun sameResolution(a: DisplayModeSpec, b: DisplayModeSpec): Boolean =
        a.longEdge == b.longEdge && a.shortEdge == b.shortEdge
}

/**
 * Applies a matching display mode through `WindowManager.LayoutParams.preferredDisplayModeId`
 * when "match frame rate" is [enabled]. `Surface.setFrameRate` needs API 30/31, which Fire OS 7
 * lacks, so this uses the mode-id path that works from API 23. No-op when the display offers no
 * matching mode. Call [reset] when playback leaves the screen.
 */
class DisplayModeSwitcher(
    private val window: Window,
    var enabled: Boolean,
) {
    /** Picks and applies a mode for [format]; returns the chosen mode or null when unchanged. */
    fun apply(format: VideoFormatInfo?): DisplayModeSpec? {
        if (!enabled || format == null) return null
        val display = displayOrNull() ?: return null
        val modes = display.supportedModes.map { it.toSpec() }
        if (modes.size < 2) return null
        val current = display.mode.toSpec()
        val target = DisplayModeSelector.select(modes, current, format.width, format.height, format.frameRate)
            ?: return null
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId != target.modeId) {
            attributes.preferredDisplayModeId = target.modeId
            window.attributes = attributes
        }
        return target
    }

    /** Returns the window to the system's default mode choice. */
    fun reset() {
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId != 0) {
            attributes.preferredDisplayModeId = 0
            window.attributes = attributes
        }
    }

    @Suppress("DEPRECATION")
    private fun displayOrNull(): Display? =
        window.decorView.display ?: window.windowManager?.defaultDisplay

    private fun Display.Mode.toSpec() = DisplayModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)
}

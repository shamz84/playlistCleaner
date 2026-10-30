package app.playlistplayer.ui.mobile.player

import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.feature.vod.SeekAccelerator
import app.playlistplayer.feature.vod.SeekDirection

/** Horizontal third of the video area a touch landed in. */
enum class TapZone { Left, Center, Right }

/** What a touch gesture on the video asks for. The screen maps it onto the overlay / player. */
sealed interface GestureCommand {
    data object ToggleControls : GestureCommand

    data object PlayPause : GestureCommand

    /** One seek step, the same −10 s / +30 s steps as a D-pad press on TV ([SeekAccelerator]). */
    data class Seek(val direction: SeekDirection) : GestureCommand

    data object None : GestureCommand
}

/**
 * Touch gestures of the phone player (pure mapping, no Compose):
 * - single tap toggles the controls (live and VOD);
 * - double tap on the left third seeks −10 s, on the right third +30 s, in the middle play/pause
 *   (VOD only; live has no double tap so single taps respond immediately);
 * - horizontal drag seeks VOD: a full-width drag moves [DRAG_FULL_WIDTH_MS], clamped to the title.
 */
object PlayerGestures {
    /** A drag across the whole width seeks this far (2 min), so short drags give fine control. */
    const val DRAG_FULL_WIDTH_MS: Long = 120_000

    /** Controls hide after this long without interaction while playing. */
    const val CONTROLS_TIMEOUT_MS: Long = 4_000

    fun zoneOf(x: Float, width: Float): TapZone {
        if (width <= 0f) return TapZone.Center
        val f = x / width
        return when {
            f < 1f / 3f -> TapZone.Left
            f > 2f / 3f -> TapZone.Right
            else -> TapZone.Center
        }
    }

    fun onTap(): GestureCommand = GestureCommand.ToggleControls

    fun onDoubleTap(x: Float, width: Float, isLive: Boolean): GestureCommand {
        if (isLive) return GestureCommand.None
        return when (zoneOf(x, width)) {
            TapZone.Left -> GestureCommand.Seek(SeekDirection.BACK)
            TapZone.Right -> GestureCommand.Seek(SeekDirection.FORWARD)
            TapZone.Center -> GestureCommand.PlayPause
        }
    }

    /** Signed seek step of a double tap, for the "−10 s" / "+30 s" feedback label. */
    fun stepMs(direction: SeekDirection): Long = SeekAccelerator.stepMs(direction, repeatCount = 0)

    /**
     * Target of a horizontal drag of [dragPx] (positive = right) over a [widthPx]-wide surface,
     * starting at [startMs]. Clamped to `0..durationMs` (lower bound only when the duration is unknown).
     */
    fun dragTargetMs(startMs: Long, dragPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f) return startMs
        val delta = (dragPx / widthPx * DRAG_FULL_WIDTH_MS).toLong()
        val target = startMs + delta
        return if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0)
    }
}

/**
 * Manual version choice in the live "Other versions" sheet: [pickedId] plays first, the remaining
 * versions keep their order behind it as failover candidates. Unknown ids leave the list unchanged.
 */
fun withPickedFirst(sources: List<PlayableSource>, pickedId: String): List<PlayableSource> {
    val picked = sources.firstOrNull { it.id == pickedId } ?: return sources
    return listOf(picked) + sources.filter { it.id != pickedId }
}

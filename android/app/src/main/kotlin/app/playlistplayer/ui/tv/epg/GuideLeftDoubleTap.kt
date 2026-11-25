package app.playlistplayer.ui.tv.epg

import android.view.KeyEvent

internal class GuideLeftDoubleTap(private val timeoutMillis: Long) {
    private var firstDownAt: Long? = null
    private var firstReleased = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.action == KeyEvent.ACTION_DOWN) reset()
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) {
                    reset()
                    return false
                }
                val previous = firstDownAt
                val elapsed = previous?.let { event.eventTime - it }
                if (firstReleased && elapsed != null && elapsed in 0..timeoutMillis) {
                    reset()
                    return true
                }
                firstDownAt = event.eventTime
                firstReleased = false
            }
            KeyEvent.ACTION_UP -> {
                if (event.isCanceled) reset()
                else if (firstDownAt != null) firstReleased = true
            }
        }
        return false
    }

    private fun reset() {
        firstDownAt = null
        firstReleased = false
    }
}

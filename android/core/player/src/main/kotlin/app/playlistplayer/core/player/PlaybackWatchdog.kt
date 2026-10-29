package app.playlistplayer.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fires [onTimeout] once when no [kick] arrives for the armed timeout.
 *
 * Deadline-based: [kick] only moves the deadline, so frequent progress reports do not create
 * jobs. After firing, the watchdog is disarmed until [arm] is called again, which guarantees one
 * re-prepare per stall rather than overlapping ones.
 */
class PlaybackWatchdog(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onTimeout: () -> Unit,
) {
    private var job: Job? = null
    private var deadline = 0L
    private var timeoutMs = 0L

    val isArmed: Boolean get() = job != null

    /** Arms (or re-arms) with [timeoutMs] from now. */
    fun arm(timeoutMs: Long) {
        this.timeoutMs = timeoutMs
        deadline = clock() + timeoutMs
        job?.cancel()
        job = scope.launch {
            while (true) {
                val wait = deadline - clock()
                if (wait <= 0) break
                delay(wait)
            }
            job = null
            onTimeout()
        }
    }

    /** Progress seen: pushes the deadline out by the armed timeout. No-op when disarmed. */
    fun kick() {
        if (job != null) deadline = clock() + timeoutMs
    }

    fun disarm() {
        job?.cancel()
        job = null
    }
}

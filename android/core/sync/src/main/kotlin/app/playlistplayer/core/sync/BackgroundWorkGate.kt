package app.playlistplayer.core.sync

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Serialises the heavy background jobs of the process (R34, U20): catalog sync, EPG import, the
 * merged-title index build and the search index rebuild never run at the same time, whichever
 * path started them (pipeline step, legacy worker, onboarding, index maintenance). One shared
 * instance lives in the app container; each job wraps its whole run in [run].
 *
 * Jobs queue in FIFO order (a fair [Mutex]). The gate is **not re-entrant**: a gated job must
 * never call another gated job (it would wait for itself).
 *
 * With a [dispatcher] every job runs there; the app passes [lowPriorityDispatcher], a single
 * background-priority thread, so these jobs yield the CPU to the UI and playback.
 */
class BackgroundWorkGate(private val dispatcher: CoroutineDispatcher? = null) {
    private val mutex = Mutex()

    /** Name of the job holding the gate, or null (diagnostics). */
    @Volatile var running: String? = null
        private set

    suspend fun <T> run(job: String, block: suspend () -> T): T = mutex.withLock {
        running = job
        try {
            if (dispatcher == null) block() else withContext(dispatcher) { block() }
        } finally {
            running = null
        }
    }

    companion object {
        /**
         * One daemon thread at Android background priority (`THREAD_PRIORITY_BACKGROUND`) and
         * Java's minimum priority. Off-device (JVM tests) only the Java priority applies.
         */
        fun lowPriorityDispatcher(name: String = "background-work"): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread({
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    } catch (e: Throwable) {
                        // Not on Android (unit tests): Java priority below is all we can set.
                    }
                    runnable.run()
                }, name).apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }.asCoroutineDispatcher()
    }
}

package app.playlistplayer.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * One open PIN prompt.
 *
 * @property reason why the prompt is (re)shown: [Authorization.PinRequired] first, then
 *   [Authorization.Denied] after a wrong PIN or [Authorization.LockedOut] during a lockout.
 */
data class PinPromptRequest(
    val id: Long,
    val action: ProtectedAction,
    val reason: Authorization,
)

/**
 * App-scoped hand-off between code that needs a PIN ([ParentalPlaylistAuthorizer]) and the UI
 * that shows the PIN pad ([SettingsViewModel] surfaces [request]). One prompt at a time; a second
 * caller waits for the first to finish. The PIN never leaves memory.
 */
class PinPromptBroker {
    private val mutex = Mutex()
    private val ids = AtomicLong()
    private val _request = MutableStateFlow<PinPromptRequest?>(null)

    @Volatile private var pending: CompletableDeferred<String?>? = null

    /** The open prompt, or null. */
    val request: StateFlow<PinPromptRequest?> = _request.asStateFlow()

    /**
     * Shows a prompt for [action] and suspends until the user submits a PIN ([submit]) or
     * cancels ([cancel]; returns null). Cancelling the caller closes the prompt.
     */
    suspend fun requestPin(action: ProtectedAction, reason: Authorization): String? = mutex.withLock {
        val answer = CompletableDeferred<String?>()
        pending = answer
        _request.value = PinPromptRequest(ids.incrementAndGet(), action, reason)
        try {
            answer.await()
        } finally {
            pending = null
            _request.value = null
        }
    }

    /** Answers the open prompt. Ignored when no prompt is open or [requestId] is stale. */
    fun submit(requestId: Long, pin: String) {
        if (_request.value?.id != requestId) return
        pending?.complete(pin)
    }

    /** Dismisses the open prompt (the protected action is not performed). */
    fun cancel(requestId: Long? = null) {
        if (requestId != null && _request.value?.id != requestId) return
        pending?.complete(null)
    }
}

/**
 * Gate for protected actions with the parental PIN (U12). Bind it as U15's `PlaylistAuthorizer`
 * (`container.playlistAuthorizer = { authorizer(it) }`) and use it for every other protected
 * Settings action.
 *
 * Without a PIN, or inside an unlocked session for actions that do not need a fresh PIN, it
 * returns true at once. Otherwise it asks [prompts] for the PIN and retries until the PIN is
 * accepted (true) or the prompt is cancelled (false). Wrong PINs re-open the prompt with the
 * remaining attempts; a lockout re-opens it with the retry time. Damaged parental storage
 * returns false without a prompt (only "Reset app" helps).
 */
class ParentalPlaylistAuthorizer(
    private val parental: ParentalControl,
    private val prompts: PinPromptBroker,
) {
    suspend operator fun invoke(action: ProtectedAction): Boolean {
        var pin: String? = null
        while (true) {
            when (val auth = parental.authorize(action, pin)) {
                Authorization.Allowed -> return true
                Authorization.ResetRequired -> return false
                Authorization.PinRequired, is Authorization.Denied, is Authorization.LockedOut ->
                    pin = prompts.requestPin(action, auth)?.takeIf { it.isNotEmpty() } ?: return false
            }
        }
    }
}

package app.playlistplayer.ui.tv.live

/**
 * Remote number entry (U9): digits typed on the remote jump to the Nth playable channel of the
 * current group (position within the group, not a global channel number).
 *
 * Pure and clock-free: callers pass `nowMillis`. The entry commits when
 * - [timeoutMillis] (1.5 s) passes after the last digit ([onTimeout]),
 * - OK is pressed ([commit]),
 * - no further digit could name a channel in range (e.g. "7" in a group of 50 commits at once,
 *   as does "13" in a group of 120), or [maxDigits] digits were typed.
 *
 * A committed number outside `1..channelCount` is ignored ([Outcome.Ignored]): no jump.
 */
class NumberEntryController(
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val maxDigits: Int = DEFAULT_MAX_DIGITS,
) {
    data class State(val digits: String = "", val deadlineMillis: Long? = null) {
        val isActive: Boolean get() = digits.isNotEmpty()
    }

    sealed interface Outcome {
        /** Jump to the channel numbered [number] (1-based). */
        data class Jump(val number: Int) : Outcome

        /** The typed [digits] name no channel in this group. */
        data class Ignored(val digits: String) : Outcome
    }

    var state: State = State()
        private set

    /**
     * Adds [digit] (0–9). Returns an outcome when the entry committed immediately, otherwise null
     * (the overlay keeps showing [State.digits] until [onTimeout] or [commit]).
     */
    fun onDigit(digit: Int, nowMillis: Long, channelCount: Int): Outcome? {
        require(digit in 0..9) { "digit must be 0..9" }
        val previous = state.deadlineMillis
        val base = if (previous != null && nowMillis >= previous) "" else state.digits
        val digits = base + digit
        state = State(digits, nowMillis + timeoutMillis)
        val value = digits.toLong()
        val noLongerNumberFits = value * 10 > channelCount
        return if (digits.length >= maxDigits || noLongerNumberFits) commit(channelCount) else null
    }

    /** Commits when the deadline has passed; null while still waiting or when nothing is pending. */
    fun onTimeout(nowMillis: Long, channelCount: Int): Outcome? {
        val deadline = state.deadlineMillis ?: return null
        return if (nowMillis >= deadline) commit(channelCount) else null
    }

    /** Commits now (OK pressed). Null when nothing is pending. */
    fun commit(channelCount: Int): Outcome? {
        val digits = state.digits
        state = State()
        if (digits.isEmpty()) return null
        val value = digits.toLongOrNull()
        return if (value != null && value in 1..channelCount.toLong()) {
            Outcome.Jump(value.toInt())
        } else {
            Outcome.Ignored(digits)
        }
    }

    /** Drops the pending entry (Back pressed). */
    fun cancel() {
        state = State()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
        const val DEFAULT_MAX_DIGITS = 4
    }
}

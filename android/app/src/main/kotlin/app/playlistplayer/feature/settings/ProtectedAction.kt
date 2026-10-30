package app.playlistplayer.feature.settings

/**
 * Settings actions gated by the parental PIN when one is set. With no PIN every action is
 * allowed.
 *
 * @property requiresFreshPin true when an unlocked session is not enough and the PIN must be
 *   typed for this action (changing or removing the PIN itself, revealing a source reference).
 */
enum class ProtectedAction(val requiresFreshPin: Boolean) {
    ChangePin(true),
    RemovePin(true),
    ChangeGroupVisibility(false),
    EditPlaylist(false),
    RemovePlaylist(false),
    ChangeXtreamProcessing(false),
    RevealSourceReference(true),
}

/** Outcome of [ParentalControl.authorize] and [ParentalControl.verify]. */
sealed interface Authorization {
    /** Go ahead. */
    data object Allowed : Authorization

    /** A PIN is set and must be entered; no attempt was counted. */
    data object PinRequired : Authorization

    /** Too many wrong PINs; no attempt is accepted before [retryAtMillis] (epoch millis). */
    data class LockedOut(val retryAtMillis: Long) : Authorization

    /** Wrong PIN. [attemptsBeforeLockout] more wrong PINs trigger the next lockout. */
    data class Denied(val attemptsBeforeLockout: Int) : Authorization

    /** Parental storage is unreadable; nothing is allowed until "Reset app" ([ParentalControl.resetAll]). */
    data object ResetRequired : Authorization
}

/** Outcome of PIN and locked-group changes. */
sealed interface ParentalResult {
    data object Done : ParentalResult

    /** The new PIN is not 4–8 ASCII digits. No attempt was counted. */
    data object InvalidPinFormat : ParentalResult

    /** The operation needs an existing PIN (change, remove, lock groups) and none is set. */
    data object NoPinSet : ParentalResult

    /** Authorization failed; [authorization] is never [Authorization.Allowed]. */
    data class Rejected(val authorization: Authorization) : ParentalResult
}

/** Parental-control state. Unlock is in memory only, so a restart always starts [Locked]. */
sealed interface ParentalState {
    data object NoPin : ParentalState

    data object Locked : ParentalState

    /** @property sessionUntilMillis end of the unlock, or null for "until locked or app exit". */
    data class Unlocked(val sessionUntilMillis: Long?) : ParentalState

    /**
     * The PIN, attempts or locked-groups storage is present but unreadable. Everything is hidden
     * and only "Reset app" ([ParentalControl.resetAll]) recovers.
     */
    data object ResetRequired : ParentalState
}

/** A group or category of one playlist. [groupKey] is the stable group key the catalog uses. */
data class LockedGroup(val playlistId: Long, val groupKey: String)

object PinFormat {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
}

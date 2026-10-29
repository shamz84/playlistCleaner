package app.playlistplayer.feature.settings

import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Parental PIN, locked groups and the protected-action gate (R20, U12).
 *
 * - The PIN is stored only as a salted [PinHasher] hash in [SecretSlotStore].
 * - Failed attempts and lockouts are persisted before the PIN is compared, so killing the app
 *   mid-check cannot reset the counter (a check interrupted that way counts as a failure; the
 *   gate fails closed). A corrupt attempt record also fails closed (lockout at the cap).
 * - Unlocking is in memory only and lasts until [lock], app exit, or [unlockDurationMillis].
 * - Locked groups are hidden while not unlocked; [isGroupHidden] is non-suspending so browse,
 *   search, home rows and the guide can filter synchronously.
 * - A correct PIN entered for any protected action also unlocks the session (the parent is
 *   present).
 * - Forgotten PIN: [resetAll] wipes PIN, attempts and locked groups without a PIN; the caller
 *   confirms first and wipes the rest of the app data itself.
 *
 * Create with [load]. All mutating calls are serialised by a mutex, which also stops parallel
 * guessing.
 */
class ParentalControl private constructor(
    private val store: SecretSlotStore,
    private val clock: Clock,
    private val hasher: PinHasher,
    private val policy: LockoutPolicy,
    private val unlockDurationMillis: Long?,
    private val hashContext: CoroutineContext,
    pinHash: String?,
    attempts: AttemptRecord,
    lockedGroups: Set<LockedGroup>,
) {
    private val mutex = Mutex()

    @Volatile private var pinHash: String? = pinHash
    @Volatile private var attempts: AttemptRecord = attempts
    @Volatile private var groups: Set<LockedGroup> = if (pinHash == null) emptySet() else lockedGroups

    /** Null when locked; otherwise the session end (Long.MAX_VALUE when open-ended). */
    @Volatile private var sessionUntil: Long? = null

    private val _state = MutableStateFlow(computeState(clock.nowMillis()))
    private val _lockedGroups = MutableStateFlow(groups)

    /**
     * Observable state. A timed unlock expiring is reflected on the next call to any method;
     * use [currentState] for an exact read.
     */
    val state: StateFlow<ParentalState> = _state.asStateFlow()

    /** Configured locked groups (empty when no PIN is set). */
    val lockedGroups: StateFlow<Set<LockedGroup>> = _lockedGroups.asStateFlow()

    fun currentState(): ParentalState = computeState(clock.nowMillis()).also { _state.value = it }

    val hasPin: Boolean get() = pinHash != null

    /** True when the group is configured as locked (regardless of the session). */
    fun isGroupLocked(playlistId: Long, groupKey: String): Boolean =
        pinHash != null && LockedGroup(playlistId, groupKey) in groups

    /** True when the group must be excluded from browse, search, home rows and the guide now. */
    fun isGroupHidden(playlistId: Long, groupKey: String): Boolean =
        isGroupLocked(playlistId, groupKey) && !sessionActive(clock.nowMillis())

    /** End of the active lockout (epoch millis), or null. */
    fun lockedOutUntil(): Long? = policy.lockedUntil(attempts, clock.nowMillis())

    /**
     * Checks the PIN and unlocks the session on success. A blank [pin] returns
     * [Authorization.PinRequired] without counting an attempt. With no PIN set: Allowed.
     */
    suspend fun verify(pin: String): Authorization = mutex.withLock {
        val hash = pinHash ?: return@withLock Authorization.Allowed
        val now = clock.nowMillis()
        policy.lockedUntil(attempts, now)?.let { return@withLock Authorization.LockedOut(it) }
        if (pin.isEmpty()) return@withLock Authorization.PinRequired
        checkPin(pin, hash, now)
    }

    /**
     * Gate for a [ProtectedAction]. With no PIN set: Allowed. Otherwise an unlocked session
     * allows actions that do not [ProtectedAction.requiresFreshPin]; anything else needs [pin].
     */
    suspend fun authorize(action: ProtectedAction, pin: String? = null): Authorization = mutex.withLock {
        authorizeLocked(action, pin)
    }

    /** Ends the unlock session. */
    fun lock() {
        sessionUntil = null
        publish()
    }

    /**
     * Sets the first PIN, or changes it when one exists (then [currentPin] is required).
     * Setting the first PIN unlocks the session so the parent can pick groups to lock.
     */
    suspend fun setPin(newPin: String, currentPin: String? = null): ParentalResult {
        if (pinHash != null) return changePin(currentPin.orEmpty(), newPin)
        return mutex.withLock {
            if (pinHash != null) return@withLock ParentalResult.Rejected(Authorization.PinRequired)
            if (!PinFormat.isValid(newPin)) return@withLock ParentalResult.InvalidPinFormat
            val encoded = withContext(hashContext) { hasher.hash(newPin) }
            store.put(SLOT_PIN, encoded)
            store.remove(SLOT_ATTEMPTS)
            pinHash = encoded
            attempts = AttemptRecord.NONE
            startSession(clock.nowMillis())
            ParentalResult.Done
        }
    }

    suspend fun changePin(currentPin: String, newPin: String): ParentalResult = mutex.withLock {
        if (pinHash == null) return@withLock ParentalResult.NoPinSet
        if (!PinFormat.isValid(newPin)) return@withLock ParentalResult.InvalidPinFormat
        val auth = authorizeLocked(ProtectedAction.ChangePin, currentPin)
        if (auth != Authorization.Allowed) return@withLock ParentalResult.Rejected(auth)
        val encoded = withContext(hashContext) { hasher.hash(newPin) }
        store.put(SLOT_PIN, encoded)
        pinHash = encoded
        ParentalResult.Done
    }

    /** Removes the PIN and, with it, every locked group (nothing can stay hidden without a PIN). */
    suspend fun removePin(currentPin: String): ParentalResult = mutex.withLock {
        if (pinHash == null) return@withLock ParentalResult.NoPinSet
        val auth = authorizeLocked(ProtectedAction.RemovePin, currentPin)
        if (auth != Authorization.Allowed) return@withLock ParentalResult.Rejected(auth)
        clearAll()
        ParentalResult.Done
    }

    /**
     * Replaces the locked groups. Needs a PIN to exist ([ParentalResult.NoPinSet] otherwise) and
     * [ProtectedAction.ChangeGroupVisibility] authorization (unlocked session or [pin]).
     */
    suspend fun setLockedGroups(groups: Set<LockedGroup>, pin: String? = null): ParentalResult = mutex.withLock {
        if (pinHash == null) return@withLock ParentalResult.NoPinSet
        val auth = authorizeLocked(ProtectedAction.ChangeGroupVisibility, pin)
        if (auth != Authorization.Allowed) return@withLock ParentalResult.Rejected(auth)
        writeGroups(groups.toSet())
        ParentalResult.Done
    }

    /**
     * Drops locks belonging to a removed playlist. Housekeeping only: the caller has already
     * authorized [ProtectedAction.RemovePlaylist], so no PIN is checked here.
     */
    suspend fun forgetPlaylist(playlistId: Long) = mutex.withLock {
        val remaining = groups.filterTo(mutableSetOf()) { it.playlistId != playlistId }
        if (remaining.size != groups.size) writeGroups(remaining)
    }

    /**
     * Forgotten-PIN path ("Reset app"): wipes PIN, attempts and locked groups without a PIN.
     * The caller must confirm with the user first and wipes the remaining app data itself.
     */
    suspend fun resetAll() = mutex.withLock { clearAll() }

    // --- internals (call with mutex held) ---

    private suspend fun authorizeLocked(action: ProtectedAction, pin: String?): Authorization {
        val hash = pinHash ?: return Authorization.Allowed
        val now = clock.nowMillis()
        if (pin.isNullOrEmpty() && !action.requiresFreshPin && sessionActive(now)) return Authorization.Allowed
        policy.lockedUntil(attempts, now)?.let { return Authorization.LockedOut(it) }
        if (pin.isNullOrEmpty()) return Authorization.PinRequired
        return checkPin(pin, hash, now)
    }

    private suspend fun checkPin(pin: String, hash: String, now: Long): Authorization {
        // Persist the failure first; a success below undoes it.
        val failed = policy.recordFailure(attempts, now)
        store.put(SLOT_ATTEMPTS, failed.encode())
        attempts = failed
        val matches = withContext(hashContext) { hasher.verify(pin, hash) }
        if (matches) {
            store.remove(SLOT_ATTEMPTS)
            attempts = AttemptRecord.NONE
            if (hasher.needsRehash(hash)) {
                val upgraded = withContext(hashContext) { hasher.hash(pin) }
                store.put(SLOT_PIN, upgraded)
                pinHash = upgraded
            }
            startSession(now)
            return Authorization.Allowed
        }
        val lockedUntil = policy.lockedUntil(failed, now)
        if (lockedUntil != null) {
            sessionUntil = null
            publish()
            return Authorization.LockedOut(lockedUntil)
        }
        return Authorization.Denied(policy.threshold - failed.failures)
    }

    private suspend fun writeGroups(newGroups: Set<LockedGroup>) {
        if (newGroups.isEmpty()) store.remove(SLOT_GROUPS) else store.put(SLOT_GROUPS, encodeGroups(newGroups))
        groups = newGroups
        publish()
    }

    private suspend fun clearAll() {
        store.remove(SLOT_PIN)
        store.remove(SLOT_ATTEMPTS)
        store.remove(SLOT_GROUPS)
        pinHash = null
        attempts = AttemptRecord.NONE
        groups = emptySet()
        sessionUntil = null
        publish()
    }

    private fun startSession(now: Long) {
        sessionUntil = unlockDurationMillis?.let { now + it } ?: Long.MAX_VALUE
        publish()
    }

    private fun sessionActive(now: Long): Boolean {
        val until = sessionUntil ?: return false
        return pinHash != null && now < until
    }

    private fun computeState(now: Long): ParentalState = when {
        pinHash == null -> ParentalState.NoPin
        sessionActive(now) -> ParentalState.Unlocked(sessionUntil.takeUnless { it == Long.MAX_VALUE })
        else -> ParentalState.Locked
    }

    private fun publish() {
        _state.value = computeState(clock.nowMillis())
        _lockedGroups.value = if (pinHash == null) emptySet() else groups
    }

    companion object {
        const val SLOT_PIN = "parental.pinHash"
        const val SLOT_ATTEMPTS = "parental.attempts"
        const val SLOT_GROUPS = "parental.lockedGroups"
        private const val GROUPS_HEADER = "v1"

        /**
         * Reads persisted state. A restart always starts [ParentalState.Locked] (or NoPin).
         *
         * @param unlockDurationMillis null keeps an unlock until [lock] or app exit.
         * @param hashContext where PBKDF2 runs (off the main thread by default).
         */
        suspend fun load(
            store: SecretSlotStore,
            clock: Clock = Clock.SYSTEM,
            hasher: PinHasher = PinHasher(),
            policy: LockoutPolicy = LockoutPolicy(),
            unlockDurationMillis: Long? = null,
            hashContext: CoroutineContext = Dispatchers.Default,
        ): ParentalControl {
            require(unlockDurationMillis == null || unlockDurationMillis > 0)
            val pinHash = store.get(SLOT_PIN)
            val attempts = store.get(SLOT_ATTEMPTS)?.let { text ->
                AttemptRecord.decode(text) ?: policy.failClosed(clock.nowMillis()).also {
                    store.put(SLOT_ATTEMPTS, it.encode())
                }
            } ?: AttemptRecord.NONE
            val groups = store.get(SLOT_GROUPS)?.let(::decodeGroups).orEmpty()
            return ParentalControl(
                store, clock, hasher, policy, unlockDurationMillis, hashContext,
                pinHash, attempts, groups,
            )
        }

        // One group per line: `<playlistId>\t<base64url(groupKey UTF-8)>`, so any group name
        // (tabs, newlines, emoji) survives.
        internal fun encodeGroups(groups: Set<LockedGroup>): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return buildString {
                append(GROUPS_HEADER)
                groups.sortedWith(compareBy({ it.playlistId }, { it.groupKey })).forEach {
                    append('\n').append(it.playlistId).append('\t')
                    append(encoder.encodeToString(it.groupKey.toByteArray(Charsets.UTF_8)))
                }
            }
        }

        internal fun decodeGroups(text: String): Set<LockedGroup> {
            val lines = text.split('\n')
            if (lines.firstOrNull() != GROUPS_HEADER) return emptySet()
            val decoder = Base64.getUrlDecoder()
            return lines.drop(1).mapNotNullTo(mutableSetOf()) { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNullTo null
                val id = line.substring(0, tab).toLongOrNull() ?: return@mapNotNullTo null
                val key = try {
                    String(decoder.decode(line.substring(tab + 1)), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    return@mapNotNullTo null
                }
                LockedGroup(id, key)
            }
        }
    }
}

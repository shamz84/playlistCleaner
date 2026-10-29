package app.playlistplayer.feature.settings

/**
 * Persisted failed-attempt state.
 *
 * @property failures consecutive wrong PINs since the last lockout or success.
 * @property level number of lockouts since the last success (drives escalation).
 * @property lockedUntilMillis end of the current lockout, or 0 when none was applied.
 */
data class AttemptRecord(
    val failures: Int = 0,
    val level: Int = 0,
    val lockedUntilMillis: Long = 0,
) {
    fun encode(): String = "$VERSION;$failures;$level;$lockedUntilMillis"

    companion object {
        val NONE = AttemptRecord()
        private const val VERSION = "v1"

        /** Null when [text] is not a valid record (caller decides how to fail closed). */
        fun decode(text: String): AttemptRecord? {
            val parts = text.split(';')
            if (parts.size != 4 || parts[0] != VERSION) return null
            val failures = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val level = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val until = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return AttemptRecord(failures, level, until)
        }
    }
}

/**
 * Escalating lockout: every [threshold] consecutive wrong PINs apply a lockout of
 * [baseLockoutMillis] doubled per earlier lockout since the last success, capped at
 * [maxLockoutMillis]. Defaults: 3 wrong → 30 s, next 3 → 60 s, 120 s, … up to 1 h.
 * The counter restarts after each lockout; a correct PIN resets everything.
 */
data class LockoutPolicy(
    val threshold: Int = 3,
    val baseLockoutMillis: Long = 30_000,
    val maxLockoutMillis: Long = 3_600_000,
) {
    init {
        require(threshold >= 1)
        require(baseLockoutMillis in 1..maxLockoutMillis)
    }

    fun lockoutDurationMillis(level: Int): Long {
        require(level >= 1)
        var duration = baseLockoutMillis
        repeat(level - 1) {
            if (duration >= maxLockoutMillis) return maxLockoutMillis
            duration *= 2
        }
        return duration.coerceAtMost(maxLockoutMillis)
    }

    /** The record after one more wrong PIN at [nowMillis]. */
    fun recordFailure(record: AttemptRecord, nowMillis: Long): AttemptRecord {
        val failures = record.failures + 1
        if (failures < threshold) return record.copy(failures = failures)
        val level = record.level + 1
        return AttemptRecord(failures = 0, level = level, lockedUntilMillis = nowMillis + lockoutDurationMillis(level))
    }

    /**
     * End of the active lockout, or null. Clamped to [maxLockoutMillis] from now so a wall
     * clock moved backwards cannot extend a lockout beyond the cap.
     */
    fun lockedUntil(record: AttemptRecord, nowMillis: Long): Long? =
        if (record.lockedUntilMillis > nowMillis) {
            minOf(record.lockedUntilMillis, nowMillis + maxLockoutMillis)
        } else {
            null
        }

    /** A record that fails closed (full cap from now), used when the stored record is corrupt. */
    fun failClosed(nowMillis: Long): AttemptRecord =
        AttemptRecord(failures = 0, level = maxLevel(), lockedUntilMillis = nowMillis + maxLockoutMillis)

    private fun maxLevel(): Int {
        var level = 1
        while (lockoutDurationMillis(level) < maxLockoutMillis) level++
        return level
    }
}

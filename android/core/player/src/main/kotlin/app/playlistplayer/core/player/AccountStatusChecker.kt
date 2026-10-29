package app.playlistplayer.core.player

/** Result of a `player_api.php` `user_info` lookup used to classify HTTP 401/403/509. */
sealed interface AccountStatus {
    /** The account is active; [maxConnections] <= 0 means unlimited. */
    data class Active(val activeConnections: Int, val maxConnections: Int) : AccountStatus {
        val atConnectionLimit: Boolean get() = maxConnections in 1..activeConnections
    }

    /** Expired, banned or disabled. */
    data object Expired : AccountStatus

    /** The status could not be determined (no Xtream account, network failure, parse error). */
    data object Unknown : AccountStatus
}

/**
 * Queries the account status for a source's playlist. Implemented over the Xtream client (U4);
 * must not throw for ordinary failures (return [AccountStatus.Unknown]) and should be cancellable.
 */
fun interface AccountStatusChecker {
    suspend fun check(source: PlayableSource): AccountStatus

    companion object {
        /** For playlists without Xtream processing: every auth-class error is a stream error. */
        val UNKNOWN: AccountStatusChecker = AccountStatusChecker { AccountStatus.Unknown }
    }
}

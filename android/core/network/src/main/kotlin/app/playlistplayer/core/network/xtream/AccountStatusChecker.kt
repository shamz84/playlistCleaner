package app.playlistplayer.core.network.xtream

import app.playlistplayer.core.model.XtreamAccount
import kotlin.coroutines.cancellation.CancellationException

/** Result of a live `player_api.php` `user_info` lookup for one playlist. */
sealed interface AccountCheck {
    /** Active account. [maxConnections] <= 0 means unlimited / not reported. */
    data class Active(val activeConnections: Int, val maxConnections: Int) : AccountCheck {
        val atConnectionLimit: Boolean get() = maxConnections in 1..activeConnections
    }

    /** Credentials rejected (`auth: 0`, HTTP 401) or status other than Active (Expired, Banned, Disabled). */
    data object Expired : AccountCheck

    /** Could not be determined: no Xtream account stored, network failure, unreadable response. */
    data object Unknown : AccountCheck
}

/**
 * Asks the provider for a playlist's account state. The player (U6) uses it to classify HTTP
 * 401/403/509 as AccountExpired / ConnectionLimit / stream error. Never throws for ordinary
 * failures (returns [AccountCheck.Unknown]); cancellable.
 */
fun interface AccountStatusChecker {
    suspend fun check(playlistId: Long): AccountCheck

    companion object {
        /** For playlists without Xtream processing. */
        val UNKNOWN: AccountStatusChecker = AccountStatusChecker { AccountCheck.Unknown }
    }
}

/**
 * [AccountStatusChecker] over [XtreamClient.accountInfo]. [accountLookup] returns the playlist's
 * stored account (SecureStore), or null when it has none / Xtream is disabled. [onInfo] receives
 * every successful response so callers can persist the fresh status.
 */
class XtreamAccountStatusChecker(
    private val client: XtreamClient,
    private val accountLookup: suspend (playlistId: Long) -> XtreamAccount?,
    private val onInfo: suspend (playlistId: Long, info: AccountInfo) -> Unit = { _, _ -> },
) : AccountStatusChecker {
    override suspend fun check(playlistId: Long): AccountCheck {
        val account = try {
            accountLookup(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return AccountCheck.Unknown
        val info = try {
            client.accountInfo(account)
        } catch (e: XtreamException.Http) {
            return if (e.isAuthFailure) AccountCheck.Expired else AccountCheck.Unknown
        } catch (_: XtreamException) {
            return AccountCheck.Unknown
        }
        try {
            onInfo(playlistId, info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Persisting the status is best effort; the classification still stands.
        }
        return classify(info)
    }

    companion object {
        fun classify(info: AccountInfo): AccountCheck =
            if (!info.isActive) {
                AccountCheck.Expired
            } else {
                AccountCheck.Active(
                    activeConnections = info.userInfo.activeConnections ?: 0,
                    maxConnections = info.userInfo.maxConnections ?: 0,
                )
            }
    }
}

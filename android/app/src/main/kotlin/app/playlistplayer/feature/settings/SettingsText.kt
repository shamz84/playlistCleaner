package app.playlistplayer.feature.settings

import androidx.annotation.StringRes
import app.playlistplayer.R
import app.playlistplayer.core.player.url.LiveFormatPreference
import app.playlistplayer.feature.playlists.InvalidReason
import app.playlistplayer.feature.playlists.PlaylistMessage
import app.playlistplayer.feature.playlists.PlaylistSummary
import app.playlistplayer.feature.playlists.SyncHealth
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * A string resource plus format arguments, resolved by the UI tree (TV or phone). Lets both
 * trees share one wording for Settings without the feature layer touching Compose.
 * Arguments may themselves be [SettingsText] (resolved first).
 */
data class SettingsText(@StringRes val res: Int, val args: List<Any> = emptyList()) {
    constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())
}

/** Shared Settings wording (TV and phone). Dates use the device locale. */
object SettingsTexts {
    @StringRes
    fun theme(theme: AppTheme): Int = when (theme) {
        AppTheme.MIDNIGHT_BLUE -> R.string.settings_app_theme_midnight
        AppTheme.CINEMA_EMBER -> R.string.settings_app_theme_ember
        AppTheme.GRAPHITE -> R.string.settings_app_theme_graphite
    }

    fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    fun percent(rate: Double): Int = (rate * 100).roundToInt().coerceIn(0, 100)

    fun signedHours(hours: Int): String = if (hours > 0) "+$hours" else hours.toString()

    // ---- playlists ----

    fun health(summary: PlaylistSummary): SettingsText = when (summary.health) {
        SyncHealth.Ok -> SettingsText(R.string.settings_health_ok)
        SyncHealth.NeverSynced -> SettingsText(R.string.settings_health_never)
        SyncHealth.Failed -> SettingsText(R.string.settings_health_failed)
        SyncHealth.UsingCachedCopy -> summary.lastSuccessAt
            ?.let { SettingsText(R.string.settings_health_cached, dateTime(it)) }
            ?: SettingsText(R.string.settings_health_cached_undated)
    }

    /** Account lines (status, expiry, connections); empty when Xtream is off or unknown. */
    fun account(summary: PlaylistSummary): List<SettingsText> {
        val account = summary.account ?: return if (summary.accountExpired) listOf(SettingsText(R.string.settings_account_expired)) else emptyList()
        return buildList {
            if (summary.accountExpired) add(SettingsText(R.string.settings_account_expired))
            account.status?.let { add(SettingsText(R.string.settings_account_status, it)) }
            add(account.expiresAt?.let { SettingsText(R.string.settings_account_expires, date(it)) } ?: SettingsText(R.string.settings_account_no_expiry))
            val max = account.maxConnections
            if (max != null) add(SettingsText(R.string.settings_account_connections, account.activeConnections ?: 0, max))
        }
    }

    fun counts(summary: PlaylistSummary): SettingsText = with(summary.counts) {
        SettingsText(R.string.settings_storage_counts, groups, channels, movies, series, programmes)
    }

    fun playlistMessage(message: PlaylistMessage): SettingsText = when (message) {
        is PlaylistMessage.Saved -> SettingsText(R.string.settings_msg_saved)
        is PlaylistMessage.RefreshQueued -> SettingsText(R.string.settings_msg_refresh_queued)
        is PlaylistMessage.Removed -> SettingsText(R.string.settings_msg_removed)
        is PlaylistMessage.NotAuthorized -> SettingsText(R.string.settings_msg_not_authorized)
        is PlaylistMessage.XtreamUnavailable -> SettingsText(R.string.settings_msg_xtream_unavailable)
        is PlaylistMessage.Invalid -> when (message.reason) {
            InvalidReason.BlankName -> SettingsText(R.string.settings_msg_invalid_name)
            InvalidReason.BlankDriveId -> SettingsText(R.string.settings_msg_invalid_drive)
            InvalidReason.InvalidUrl -> SettingsText(R.string.settings_msg_invalid_url)
        }
        is PlaylistMessage.Failed -> SettingsText(R.string.settings_msg_failed)
    }

    // ---- guide ----

    fun testResult(result: OverrideTestResult): SettingsText = when (result) {
        is OverrideTestResult.Reachable -> SettingsText(
            if (result.partial) R.string.settings_guide_test_ok_partial else R.string.settings_guide_test_ok,
            result.channels, result.matched, result.total, percent(result.matchRate),
        )
        is OverrideTestResult.HttpError -> SettingsText(R.string.settings_guide_test_http, result.code)
        OverrideTestResult.InvalidUrl -> SettingsText(R.string.settings_guide_test_invalid)
        OverrideTestResult.NotXmltv -> SettingsText(R.string.settings_guide_test_not_xmltv)
        OverrideTestResult.Unreachable -> SettingsText(R.string.settings_guide_test_unreachable)
    }

    fun guideStatus(status: GuideStatus?): List<SettingsText> {
        if (status == null || status.lastImportAt == null) {
            return listOfNotNull(
                SettingsText(if (status?.overrideSet == true) R.string.settings_guide_override_set else R.string.settings_guide_override_none),
                SettingsText(R.string.settings_guide_no_import),
                status?.lastErrorKind?.let { SettingsText(R.string.settings_guide_import_error, it) },
            )
        }
        return listOfNotNull(
            SettingsText(if (status.overrideSet) R.string.settings_guide_override_set else R.string.settings_guide_override_none),
            SettingsText(R.string.settings_guide_match_rate, status.matchedCount, status.totalCount, percent(status.matchRate)),
            SettingsText(R.string.settings_last_updated, dateTime(status.lastImportAt)),
            status.lastErrorKind?.let { SettingsText(R.string.settings_guide_import_error, it) },
        )
    }

    // ---- playback / content ----

    @StringRes
    fun liveFormat(format: LiveFormatPreference): Int = when (format) {
        LiveFormatPreference.TS -> R.string.settings_playback_format_ts
        LiveFormatPreference.HLS -> R.string.settings_playback_format_hls
    }

    @StringRes
    fun quality(quality: QualityPreference): Int = when (quality) {
        QualityPreference.BEST -> R.string.settings_quality_best
        QualityPreference.UHD -> R.string.settings_quality_uhd
        QualityPreference.FHD -> R.string.settings_quality_fhd
        QualityPreference.HD -> R.string.settings_quality_hd
        QualityPreference.SD -> R.string.settings_quality_sd
    }

    @StringRes
    fun groupKind(kind: ContentGroupKind): Int = when (kind) {
        ContentGroupKind.LIVE -> R.string.settings_group_kind_live
        ContentGroupKind.MOVIES -> R.string.settings_group_kind_movies
        ContentGroupKind.SERIES -> R.string.settings_group_kind_series
    }

    fun parental(state: ParentalUiState): SettingsText = SettingsText(
        when (state.state) {
            ParentalState.NoPin -> R.string.settings_pin_not_set
            ParentalState.Locked -> R.string.settings_pin_locked
            is ParentalState.Unlocked -> R.string.settings_pin_unlocked
            ParentalState.ResetRequired -> R.string.settings_pin_reset_required
        },
    )

    @StringRes
    fun action(action: ProtectedAction): Int = when (action) {
        ProtectedAction.EditPlaylist -> R.string.settings_action_edit_playlist
        ProtectedAction.RemovePlaylist -> R.string.settings_action_remove_playlist
        ProtectedAction.ChangeGroupVisibility -> R.string.settings_action_change_groups
        ProtectedAction.ChangeXtreamProcessing -> R.string.settings_action_change_xtream
        ProtectedAction.ChangePin -> R.string.settings_action_change_pin
        ProtectedAction.RemovePin -> R.string.settings_action_remove_pin
        ProtectedAction.RevealSourceReference -> R.string.settings_action_reveal_source
    }

    /** Error line under the PIN pad; null on the first prompt. */
    fun authorization(auth: Authorization): SettingsText? = when (auth) {
        Authorization.Allowed, Authorization.PinRequired -> null
        is Authorization.Denied -> SettingsText(R.string.settings_pin_wrong, auth.attemptsBeforeLockout)
        is Authorization.LockedOut -> SettingsText(R.string.settings_pin_locked_out, dateTime(auth.retryAtMillis))
        Authorization.ResetRequired -> SettingsText(R.string.settings_pin_reset_required)
    }

    fun message(message: SettingsMessage): SettingsText = when (message) {
        SettingsMessage.PinSet -> SettingsText(R.string.settings_msg_pin_set)
        SettingsMessage.PinChanged -> SettingsText(R.string.settings_msg_pin_changed)
        SettingsMessage.PinRemoved -> SettingsText(R.string.settings_msg_pin_removed)
        SettingsMessage.PinMismatch -> SettingsText(R.string.settings_msg_pin_mismatch)
        SettingsMessage.PinInvalidFormat -> SettingsText(R.string.settings_msg_pin_invalid)
        is SettingsMessage.PinRejected -> authorization(message.authorization) ?: SettingsText(R.string.settings_msg_pin_required)
        SettingsMessage.NoPinSet -> SettingsText(R.string.settings_msg_no_pin)
        SettingsMessage.GroupsSaved -> SettingsText(R.string.settings_msg_groups_saved)
        SettingsMessage.NotAuthorized -> SettingsText(R.string.settings_msg_not_authorized)
        SettingsMessage.ResetRequired -> SettingsText(R.string.settings_pin_reset_required)
        is SettingsMessage.OverrideSaved -> SettingsText(R.string.settings_msg_override_saved)
        is SettingsMessage.OverrideCleared -> SettingsText(R.string.settings_msg_override_cleared)
        is SettingsMessage.CuratedGuideApplied -> SettingsText(R.string.settings_msg_curated_applied, message.count)
        is SettingsMessage.GuideRefreshing -> SettingsText(R.string.settings_msg_guide_refreshing)
        is SettingsMessage.OverrideNotSaved -> SettingsText(R.string.settings_msg_override_not_saved, testResult(message.result))
        SettingsMessage.InvalidLanguage -> SettingsText(R.string.settings_msg_invalid_language)
        SettingsMessage.HlsNotAllowed -> SettingsText(R.string.settings_msg_hls_not_allowed)
        is SettingsMessage.ValueClamped -> SettingsText(R.string.settings_msg_value_clamped, message.applied)
        SettingsMessage.ResetDone -> SettingsText(R.string.settings_msg_reset_done)
        SettingsMessage.Failed -> SettingsText(R.string.settings_msg_failed)
    }

    /** Preset steps for the App section steppers (D-pad friendly). */
    val REFRESH_PRESETS_HOURS: List<Int> = listOf(2, 4, 6, 12)
    val GUIDE_REFRESH_PRESETS_HOURS: List<Int> = listOf(6, 12, 24, 48)
    val STALE_PRESETS_DAYS: List<Int> = listOf(1, 2, 3, 7, 14, 30, 60, 90)

    /** The next preset above/below [current] (direction +1/-1), or [current] at the end. */
    fun step(presets: List<Int>, current: Int, direction: Int): Int = if (direction > 0) {
        presets.firstOrNull { it > current } ?: presets.last()
    } else {
        presets.lastOrNull { it < current } ?: presets.first()
    }
}

package app.playlistplayer.feature.onboarding

import androidx.annotation.StringRes
import app.playlistplayer.R

/** User-facing text for onboarding states, shared by the TV and mobile trees. */
object OnboardingText {

    @StringRes
    fun errorMessage(error: OnboardingError): Int = when (error) {
        OnboardingError.NotFoundOrNotShared -> R.string.onboarding_error_not_found_or_not_shared
        OnboardingError.NotShared -> R.string.onboarding_error_not_shared
        OnboardingError.QuotaExceeded -> R.string.onboarding_error_quota_exceeded
        OnboardingError.Network -> R.string.onboarding_error_network
        OnboardingError.InvalidInput -> R.string.onboarding_error_invalid_input
        OnboardingError.AccountExpired -> R.string.onboarding_error_account_expired
        OnboardingError.Other -> R.string.onboarding_error_other
    }

    /** Hint for the Drive field. Hints taking a count use the entered ID length as `%1$d`. */
    @StringRes
    fun driveHint(validation: DriveIdValidation): Int = when (validation) {
        DriveIdValidation.Empty -> R.string.onboarding_drive_hint_empty
        DriveIdValidation.UnrecognisedLink -> R.string.onboarding_drive_hint_unrecognised_link
        DriveIdValidation.InvalidCharacters -> R.string.onboarding_drive_hint_invalid_characters
        DriveIdValidation.TooShort -> R.string.onboarding_drive_hint_too_short
        DriveIdValidation.TooLong -> R.string.onboarding_drive_hint_too_long
        DriveIdValidation.Valid -> R.string.onboarding_drive_hint_valid
    }

    fun driveHintTakesLength(validation: DriveIdValidation): Boolean =
        validation == DriveIdValidation.TooShort || validation == DriveIdValidation.TooLong

    @StringRes
    fun m3uHint(validation: M3uUrlValidation): Int = when (validation) {
        M3uUrlValidation.Empty -> R.string.onboarding_m3u_hint_empty
        M3uUrlValidation.NotHttp -> R.string.onboarding_m3u_hint_not_http
        M3uUrlValidation.Malformed -> R.string.onboarding_m3u_hint_malformed
        M3uUrlValidation.Valid -> R.string.onboarding_m3u_hint_valid
    }

    @StringRes
    fun stageName(stage: OnboardingStage): Int = when (stage) {
        OnboardingStage.Playlist -> R.string.onboarding_stage_playlist
        OnboardingStage.Channels -> R.string.onboarding_stage_channels
        OnboardingStage.Catalog -> R.string.onboarding_stage_catalog
        OnboardingStage.Guide -> R.string.onboarding_stage_guide
    }

    @StringRes
    fun stageStatus(status: StageStatus): Int = when (status) {
        StageStatus.Pending -> R.string.onboarding_status_pending
        StageStatus.Running -> R.string.onboarding_status_running
        StageStatus.Done -> R.string.onboarding_status_done
        StageStatus.Skipped -> R.string.onboarding_status_skipped
        StageStatus.Failed -> R.string.onboarding_status_failed
    }

    /** Explanation under the Xtream toggle for the current state. */
    @StringRes
    fun xtreamExplanation(state: OnboardingUiState): Int = when {
        state.xtreamAvailability == XtreamAvailability.NotXtreamShaped -> R.string.onboarding_xtream_unavailable
        !state.xtreamRequested -> R.string.onboarding_xtream_off
        state.sourceType == SourceType.DriveId -> R.string.onboarding_xtream_drive_note
        else -> R.string.onboarding_xtream_on
    }
}

package app.playlistplayer.feature.settings

import app.playlistplayer.feature.playlists.PlaylistsUiState

/** A selectable language for the preference list. [code] is the provider code the comparator uses. */
data class LanguageOption(val code: String, val label: String)

/**
 * Languages offered by Settings → Content. Codes are the lower-case provider codes produced by
 * `:core:consolidation` (`en`, `hi`, `in-ta` for `IN-TA` prefixes, …). Unknown codes stay usable:
 * [label] falls back to the upper-case code.
 */
object LanguageCatalog {
    val options: List<LanguageOption> = listOf(
        LanguageOption("en", "English"),
        LanguageOption("hi", "Hindi"),
        LanguageOption("in-hi", "Hindi (IN)"),
        LanguageOption("in-ta", "Tamil (IN)"),
        LanguageOption("in-te", "Telugu (IN)"),
        LanguageOption("in-ml", "Malayalam (IN)"),
        LanguageOption("in-kn", "Kannada (IN)"),
        LanguageOption("in-pa", "Punjabi (IN)"),
        LanguageOption("in-mr", "Marathi (IN)"),
        LanguageOption("in-bn", "Bengali (IN)"),
        LanguageOption("in-gu", "Gujarati (IN)"),
        LanguageOption("fr", "French"),
        LanguageOption("de", "German"),
        LanguageOption("es", "Spanish"),
        LanguageOption("it", "Italian"),
        LanguageOption("pt", "Portuguese"),
        LanguageOption("nl", "Dutch"),
        LanguageOption("ar", "Arabic"),
        LanguageOption("tr", "Turkish"),
        LanguageOption("pl", "Polish"),
        LanguageOption("ru", "Russian"),
        LanguageOption("sv", "Swedish"),
        LanguageOption("da", "Danish"),
        LanguageOption("fi", "Finnish"),
        LanguageOption("el", "Greek"),
        LanguageOption("he", "Hebrew"),
    )

    private val byCode = options.associateBy { it.code }

    fun label(code: String): String = byCode[code]?.label ?: code.uppercase()
}

/** Confirmation dialogs owned by [SettingsViewModel]. */
sealed interface SettingsDialog {
    data class ConfirmRemovePlaylist(val playlistId: Long, val name: String) : SettingsDialog

    /** "Reset app" wipes every playlist, preference and the parental PIN. */
    data object ConfirmResetApp : SettingsDialog
}

/** One-shot feedback (snackbar/toast). Never carries a secret. */
sealed interface SettingsMessage {
    data object PinSet : SettingsMessage
    data object PinChanged : SettingsMessage
    data object PinRemoved : SettingsMessage
    data object PinMismatch : SettingsMessage
    data object PinInvalidFormat : SettingsMessage

    /** Wrong current PIN, lockout or damaged storage. */
    data class PinRejected(val authorization: Authorization) : SettingsMessage

    /** Locking groups needs a PIN first. */
    data object NoPinSet : SettingsMessage
    data object GroupsSaved : SettingsMessage
    data object NotAuthorized : SettingsMessage

    /** Parental storage is unreadable; only "Reset app" helps. */
    data object ResetRequired : SettingsMessage

    data class OverrideSaved(val playlistId: Long) : SettingsMessage
    data class OverrideCleared(val playlistId: Long) : SettingsMessage

    data class CuratedGuideApplied(val count: Int) : SettingsMessage

    data class GuideRefreshing(val playlistId: Long) : SettingsMessage

    /** The test before saving failed; nothing was saved. */
    data class OverrideNotSaved(val playlistId: Long, val result: OverrideTestResult) : SettingsMessage

    data object InvalidLanguage : SettingsMessage
    data object HlsNotAllowed : SettingsMessage

    /** A number was outside its range and [applied] was stored instead. */
    data class ValueClamped(val applied: Int) : SettingsMessage

    data object ResetDone : SettingsMessage
    data object Failed : SettingsMessage
}

sealed interface OverrideTestState {
    data object Idle : OverrideTestState
    data object Running : OverrideTestState
    data class Done(val result: OverrideTestResult) : OverrideTestState
}

/** Settings → Guide for one playlist. */
data class GuideUiState(
    val playlistId: Long,
    /** Stored override URL for pre-filling the field (loaded by `openGuide`). Never log it. */
    val overrideUrl: String? = null,
    val overrideLoaded: Boolean = false,
    val test: OverrideTestState = OverrideTestState.Idle,
    val saving: Boolean = false,
    /** Import status from `EpgStore.state` (match rate after an import). */
    val status: GuideStatus? = null,
)

data class GroupVisibilityRow(val group: ContentGroup, val locked: Boolean)

/** Settings → Content → Group visibility for one playlist. */
data class GroupVisibilityState(
    val playlistId: Long,
    val loading: Boolean = true,
    val rows: List<GroupVisibilityRow> = emptyList(),
)

data class ParentalUiState(
    val state: ParentalState = ParentalState.NoPin,
    val hasPin: Boolean = false,
    val lockedOutUntil: Long? = null,
    val lockedGroupCount: Int = 0,
) {
    val resetRequired: Boolean get() = state == ParentalState.ResetRequired
}

data class SettingsUiState(
    val playlists: PlaylistsUiState = PlaylistsUiState(),
    val preferences: AppPreferences = AppPreferences(),
    val preferencesLoaded: Boolean = false,
    /** HLS may be chosen: an Xtream-enabled playlist's account allows `m3u8`. */
    val hlsAllowed: Boolean = false,
    val parental: ParentalUiState = ParentalUiState(),
    val guide: Map<Long, GuideUiState> = emptyMap(),
    val groupVisibility: GroupVisibilityState? = null,
    /** Adult groups proposed for locking; shown for confirmation, never applied silently. */
    val suggestions: List<GroupSuggestion> = emptyList(),
    val dialog: SettingsDialog? = null,
    val pinPrompt: PinPromptRequest? = null,
    val message: SettingsMessage? = null,
    /** A reset is running. */
    val resetting: Boolean = false,
)

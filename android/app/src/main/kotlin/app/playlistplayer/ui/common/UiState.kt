package app.playlistplayer.ui.common

/**
 * Screen-level load state shared by the TV and mobile trees.
 *
 * - [Loading]: first load in flight; show a loading indicator.
 * - [Empty]: loaded, nothing to show; [reason] says why (user-facing text).
 * - [Content]: loaded with [value].
 * - [Error]: failed; [message] is user-facing and already redacted, [retry] re-runs the load
 *   (null when retrying cannot help).
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data class Empty(val reason: String) : UiState<Nothing>

    data class Content<out T>(val value: T) : UiState<T>

    data class Error(val message: String, val retry: (() -> Unit)? = null) : UiState<Nothing>
}

/** The content value, or null in any other state. */
fun <T> UiState<T>.valueOrNull(): T? = (this as? UiState.Content<T>)?.value

/** Maps the content value, keeping every other state as is. */
inline fun <T, R> UiState<T>.map(transform: (T) -> R): UiState<R> = when (this) {
    is UiState.Content -> UiState.Content(transform(value))
    is UiState.Empty -> this
    is UiState.Error -> this
    UiState.Loading -> UiState.Loading
}

/** [UiState.Content] for a non-empty list, [UiState.Empty] with [emptyReason] otherwise. */
fun <T> List<T>.toUiState(emptyReason: String): UiState<List<T>> =
    if (isEmpty()) UiState.Empty(emptyReason) else UiState.Content(this)

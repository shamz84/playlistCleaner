package app.playlistplayer.core.player

/** Classified playback failure. Contains no URL or message text, so it is always safe to log. */
data class PlaybackError(
    val kind: Kind,
    /** HTTP status for HTTP-class kinds. */
    val httpStatus: Int? = null,
) {
    enum class Kind {
        /** Connection/timeout/IO failures and 5xx/429 responses: retryable. */
        NETWORK,

        /** 401/403/509: classify through the account status before deciding. */
        HTTP_AUTH,

        /** 404/405/410: the stream does not exist or is not served. Fail over. */
        HTTP_NOT_FOUND,

        /** Other HTTP statuses (4xx not listed above): treated as a stream error. Fail over. */
        HTTP_OTHER,

        /** Decoder, parser or renderer failure. */
        MEDIA,

        /** HLS fell behind the live window: re-prepare at the live edge, not a budget failure. */
        BEHIND_LIVE_WINDOW,

        /** Watchdog: no first frame / no progress in time. */
        STALL,

        /** The server ended a live stream. */
        STREAM_ENDED,

        UNKNOWN,
    }

    companion object {
        fun fromHttpStatus(status: Int): PlaybackError = PlaybackError(
            kind = when (status) {
                401, 403, 509 -> Kind.HTTP_AUTH
                404, 405, 410 -> Kind.HTTP_NOT_FOUND
                408, 429 -> Kind.NETWORK
                in 500..599 -> Kind.NETWORK
                else -> Kind.HTTP_OTHER
            },
            httpStatus = status,
        )

        val STALL = PlaybackError(Kind.STALL)
        val STREAM_ENDED = PlaybackError(Kind.STREAM_ENDED)
    }
}

/** States of [PlaybackStateMachine]; see the plan's "Live playback, reconnect and failover" diagram. */
sealed interface PlaybackState {

    /** The source this state refers to, if any. */
    val source: PlayableSource? get() = null

    data object Idle : PlaybackState

    /**
     * A version is being prepared and has not rendered a frame yet.
     * [reconnecting] is true when this is a re-prepare after a drop, error or stall.
     */
    data class Preparing(
        override val source: PlayableSource,
        val attempt: Int,
        val reconnecting: Boolean,
    ) : PlaybackState

    /** Frames are rendering. [notice] is the transient "Switched to" label after a failover. */
    data class Playing(
        override val source: PlayableSource,
        val buffering: Boolean = false,
        val notice: String? = null,
    ) : PlaybackState

    /** The watchdog saw no progress; a re-prepare follows immediately. */
    data class Stalled(override val source: PlayableSource) : PlaybackState

    /**
     * Waiting before re-preparing [source].
     * [waitingForNetwork]: paused until connectivity returns. [checkingAccount]: classifying an
     * HTTP 401/403/509 through the account status.
     */
    data class Retrying(
        override val source: PlayableSource,
        val reason: PlaybackError,
        val delayMs: Long,
        val waitingForNetwork: Boolean = false,
        val checkingAccount: Boolean = false,
    ) : PlaybackState

    /** Released [from]; [to] is prepared next. [count] is the 1-based failover number. */
    data class Failover(
        val from: PlayableSource,
        val to: PlayableSource,
        val count: Int,
        val reason: PlaybackError,
    ) : PlaybackState {
        override val source: PlayableSource get() = to
    }

    /** The provider reports all connections in use. Terminal; no failover. */
    data class ConnectionLimit(
        override val source: PlayableSource,
        val activeConnections: Int?,
        val maxConnections: Int?,
    ) : PlaybackState

    /** The account is expired or disabled. Terminal. */
    data class AccountExpired(override val source: PlayableSource) : PlaybackState

    /** The selected version and every tried other version failed. Terminal. */
    data class Failed(
        override val source: PlayableSource,
        val reason: PlaybackError,
        val triedVersions: Int,
    ) : PlaybackState

    /** A VOD item played to its end. */
    data class Ended(override val source: PlayableSource) : PlaybackState

    val isTerminal: Boolean
        get() = this is ConnectionLimit || this is AccountExpired || this is Failed
}

package app.playlistplayer.feature.live

import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.core.player.catchup.CatchupCandidate
import app.playlistplayer.core.player.catchup.CatchupDecision
import app.playlistplayer.core.player.catchup.CatchupEligibility
import app.playlistplayer.core.player.catchup.CatchupSeekAction
import app.playlistplayer.core.player.catchup.CatchupSession
import app.playlistplayer.core.player.catchup.CatchupUrlStrategy
import app.playlistplayer.core.player.catchup.ProgrammeWindow
import app.playlistplayer.feature.vod.AccountProvider
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A request to play a programme from the archive (R7) or restart the airing one (R8).
 *
 * @property otherVersions the channel's other versions in preference order; when [channel] has
 *   no archive, the first one whose archive covers the programme is used.
 */
data class CatchupRequest(
    val channel: LiveChannel,
    val otherVersions: List<LiveChannel>,
    val programmeStartMillis: Long,
    val programmeStopMillis: Long,
    val title: String?,
    val programmeId: Long? = null,
) {
    val window: ProgrammeWindow
        get() = ProgrammeWindow(Instant.ofEpochMilli(programmeStartMillis), Instant.ofEpochMilli(programmeStopMillis))
}

sealed interface CatchupStart {
    data class Started(val playback: CatchupPlayback) : CatchupStart

    /** Outside every candidate's archive window, in the future, or no candidate has archive. */
    data object NotEligible : CatchupStart

    data object NoAccount : CatchupStart
}

/** What the player must do for a catch-up seek. */
sealed interface CatchupSeekCommand {
    /** Programme-relative position after the seek. */
    val timelinePositionMs: Long

    /** Seek the current item to [playerPositionMs]. */
    data class NativeSeek(val playerPositionMs: Long, override val timelinePositionMs: Long) : CatchupSeekCommand

    /** Load [source] (a rebuilt URL) and start [startPositionMs] into it. */
    data class Reload(
        val source: PlayableSource,
        val startPositionMs: Long,
        override val timelinePositionMs: Long,
    ) : CatchupSeekCommand
}

/**
 * Builds catch-up playback (U8): picks the version with archive ([CatchupEligibility]), converts
 * the programme start into the server timezone from the account status (falling back to UTC when
 * unknown) plus the user's offset, uses the URL style from the settings, and decides how seeks
 * work: native byte-range seeking when [seekable] (the provider spike found timeshift responses
 * seekable), otherwise a URL rebuild through [CatchupSession]. [rebuildRestartSeeks] enables
 * the URL-rebuild strategy for ongoing programmes independently of completed archive playback.
 */
class CatchupPlaybackController(
    private val accounts: AccountProvider,
    private val accountStatus: LiveAccountStatusSource,
    private val playbackPreferences: PlaybackPreferencesProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val seekable: Boolean = true,
    private val rebuildRestartSeeks: Boolean = false,
) {
    /** The decision for [request] at [nowMillis] (pure; no I/O). */
    fun decide(request: CatchupRequest, nowMillis: Long = clock()): CatchupDecision =
        decide(request.channel, request.otherVersions, request.window, nowMillis)

    suspend fun start(request: CatchupRequest): CatchupStart {
        val now = clock()
        val decision = decide(request, now)
        val (candidate, usesOther, restart) = when (decision) {
            CatchupDecision.NotEligible -> return CatchupStart.NotEligible
            is CatchupDecision.PlayFromStart -> Triple(decision.candidate, decision.usesOtherVersion, false)
            is CatchupDecision.RestartLive -> Triple(decision.candidate, decision.usesOtherVersion, true)
        }
        val channel = (listOf(request.channel) + request.otherVersions)
            .first { it.streamId?.toString() == candidate.streamId }
        val account = accounts.streamAccount(channel.playlistId) ?: return CatchupStart.NoAccount
        val prefs = playbackPreferences.current()
        val zone = serverZone(accountStatus.status(channel.playlistId)?.serverTimezone)
        val session = CatchupSession(
            account = account,
            streamId = candidate.streamId,
            programme = request.window,
            serverZone = zone,
            userOffsetHours = prefs.catchupOffsetHours,
            strategy = CatchupUrlStrategy.forStyle(prefs.catchupUrlStyle),
            seekable = seekable && !(restart && rebuildRestartSeeks),
        )
        return CatchupStart.Started(CatchupPlayback(request, channel, usesOther, restart, session, clock))
    }

    companion object {
        fun decide(
            channel: LiveChannel,
            otherVersions: List<LiveChannel>,
            window: ProgrammeWindow,
            nowMillis: Long,
        ): CatchupDecision = CatchupEligibility.evaluate(
            now = Instant.ofEpochMilli(nowMillis),
            programme = window,
            entry = candidateOf(channel),
            otherVersions = otherVersions.filter { it.playlistId == channel.playlistId }.map(::candidateOf),
        )

        /** Entries without a stream id cannot build a timeshift URL, so they never qualify. */
        private fun candidateOf(channel: LiveChannel) = CatchupCandidate(
            streamId = channel.streamId?.toString().orEmpty(),
            archiveDays = if (channel.streamId != null && channel.isPlayable) channel.archiveDays else 0,
        )

        internal fun serverZone(id: String?): ZoneId = id?.takeIf { it.isNotBlank() }?.let {
            try {
                ZoneId.of(it.trim())
            } catch (_: DateTimeException) {
                null
            }
        } ?: ZoneOffset.UTC
    }
}

/**
 * One catch-up playback. The overlay sees the programme's own timeline (`0..durationMs`) whether
 * seeks are native or rebuild the URL. For a restarted airing programme, seeking is limited to
 * what has aired ([availableMs]). Confine to the main thread.
 */
class CatchupPlayback internal constructor(
    val request: CatchupRequest,
    /** The version actually played (an other version when the selected one lacks archive). */
    val channel: LiveChannel,
    val usesOtherVersion: Boolean,
    val isRestart: Boolean,
    private val session: CatchupSession,
    private val clock: () -> Long,
) {
    val durationMs: Long get() = session.durationMs
    val seekable: Boolean get() = session.seekable
    val streamId: String get() = session.streamId

    /** The source for the current segment. Its URL carries credentials: never log or store it. */
    fun currentSource(): PlayableSource = PlayableSource(
        id = "catchup:${channel.playlistId}:${session.streamId}:${request.programmeStartMillis}:${session.segmentStartMs}",
        url = session.currentUrl,
        label = channel.name,
        isLive = false,
        accountKey = channel.playlistId.toString(),
    )

    /** Timeline length that can be played now: the whole programme, or what has aired so far. */
    fun availableMs(nowMillis: Long = clock()): Long =
        if (isRestart) (nowMillis - request.programmeStartMillis).coerceIn(0, durationMs) else durationMs

    /** Programme-relative position for the player's position in the current item. */
    fun timelinePositionMs(playerPositionMs: Long): Long = session.timelinePositionMs(playerPositionMs)

    /** Seek to [timelineMs] on the programme timeline (clamped to [availableMs]). */
    fun seekTo(timelineMs: Long): CatchupSeekCommand {
        val target = timelineMs.coerceIn(0, availableMs())
        return when (val action = session.seekTo(target)) {
            is CatchupSeekAction.NativeSeek -> CatchupSeekCommand.NativeSeek(action.positionMs, action.positionMs)
            is CatchupSeekAction.Rebuild -> CatchupSeekCommand.Reload(currentSource(), action.residualMs, action.positionMs)
        }
    }

    override fun toString(): String =
        "CatchupPlayback(channel=${channel.ordinal}, usesOtherVersion=$usesOtherVersion, isRestart=$isRestart, session=$session)"
}

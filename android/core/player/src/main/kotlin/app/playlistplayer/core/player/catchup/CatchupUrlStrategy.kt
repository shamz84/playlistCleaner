package app.playlistplayer.core.player.catchup

import app.playlistplayer.core.player.url.StreamAccount
import app.playlistplayer.core.player.url.UrlEncoding
import java.time.LocalDateTime

/** Which timeshift URL form to build. The provider spike found both equivalent; path is the default. */
enum class CatchupUrlStyle {
    PATH,
    PHP,
    ;

    companion object {
        val DEFAULT = PATH
    }
}

/**
 * Builds a timeshift URL. [startWallTime] is already in server wall time (see [CatchupTime.startWallTime]).
 * The result contains credentials: never log it unredacted.
 */
interface CatchupUrlStrategy {
    val style: CatchupUrlStyle

    fun build(account: StreamAccount, streamId: String, startWallTime: LocalDateTime, durationMinutes: Int): String

    companion object {
        fun forStyle(style: CatchupUrlStyle): CatchupUrlStrategy = when (style) {
            CatchupUrlStyle.PATH -> PathTimeshiftStrategy
            CatchupUrlStyle.PHP -> PhpTimeshiftStrategy
        }
    }
}

/** `/timeshift/{u}/{p}/{durationMin}/{YYYY-MM-DD:HH-MM}/{id}.ts` */
object PathTimeshiftStrategy : CatchupUrlStrategy {
    override val style = CatchupUrlStyle.PATH

    override fun build(account: StreamAccount, streamId: String, startWallTime: LocalDateTime, durationMinutes: Int): String {
        validate(streamId, durationMinutes)
        return buildString {
            append(account.origin)
            append("/timeshift/")
            append(UrlEncoding.encodeComponent(account.username)).append('/')
            append(UrlEncoding.encodeComponent(account.password)).append('/')
            append(durationMinutes).append('/')
            // Only digits, '-' and ':' (a valid pchar); kept literal as the server expects.
            append(CatchupTime.formatStart(startWallTime)).append('/')
            append(UrlEncoding.encodeComponent(streamId.trim())).append(".ts")
        }
    }
}

/** `/streaming/timeshift.php?username=&password=&stream=&start=YYYY-MM-DD:HH-MM&duration=` */
object PhpTimeshiftStrategy : CatchupUrlStrategy {
    override val style = CatchupUrlStyle.PHP

    override fun build(account: StreamAccount, streamId: String, startWallTime: LocalDateTime, durationMinutes: Int): String {
        validate(streamId, durationMinutes)
        return buildString {
            append(account.origin)
            append("/streaming/timeshift.php")
            append("?username=").append(UrlEncoding.encodeComponent(account.username))
            append("&password=").append(UrlEncoding.encodeComponent(account.password))
            append("&stream=").append(UrlEncoding.encodeComponent(streamId.trim()))
            // ':' is valid in a query; kept literal to match the form verified in the spike.
            append("&start=").append(CatchupTime.formatStart(startWallTime))
            append("&duration=").append(durationMinutes)
        }
    }
}

private fun validate(streamId: String, durationMinutes: Int) {
    require(streamId.isNotBlank()) { "Stream id is blank" }
    require(durationMinutes > 0) { "Duration must be positive" }
}

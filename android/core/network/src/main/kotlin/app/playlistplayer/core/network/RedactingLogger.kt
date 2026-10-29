package app.playlistplayer.core.network

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Destination for already-redacted log lines (android.util.Log in the app, a list in tests). */
fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/**
 * The only logger app code should use. Every message, and every Throwable (message chain), passes
 * through [Redactor] before it reaches the [sink]; throwables are replaced by redacted copies that
 * keep their stack frames.
 */
class RedactingLogger(
    private val sink: LogSink,
    private val tag: String = "PlaylistPlayer",
) {
    fun withTag(tag: String): RedactingLogger = RedactingLogger(sink, tag)

    fun d(message: String) = log(LogLevel.DEBUG, message, null)
    fun i(message: String) = log(LogLevel.INFO, message, null)
    fun w(message: String, throwable: Throwable? = null) = log(LogLevel.WARN, message, throwable)
    fun e(message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, message, throwable)

    fun log(level: LogLevel, message: String, throwable: Throwable?) {
        sink.write(level, tag, Redactor.redact(message), throwable?.let(Redactor::redacted))
    }

    companion object {
        /** Discards everything; the default where no sink is wired. */
        val NONE: RedactingLogger = RedactingLogger({ _, _, _, _ -> })
    }
}

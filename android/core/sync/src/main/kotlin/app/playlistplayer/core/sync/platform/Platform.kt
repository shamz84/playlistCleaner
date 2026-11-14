package app.playlistplayer.core.sync.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.playlistplayer.core.network.LogLevel
import app.playlistplayer.core.network.LogSink
import java.security.MessageDigest

/** [LogSink] over android.util.Log. Receives only lines already redacted by RedactingLogger. */
object AndroidLogSink : LogSink {
    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
    }
}

object AppSignature {
    /**
     * Upper-case hex SHA-1 (no colons) of the app's current signing certificate, as Google expects
     * in `X-Android-Cert` for Android-restricted API keys. Empty when it cannot be read.
     */
    fun certSha1(context: Context): String = try {
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        }
        if (signer == null) {
            ""
        } else {
            MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }
}

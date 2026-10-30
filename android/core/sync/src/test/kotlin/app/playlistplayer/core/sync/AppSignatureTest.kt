package app.playlistplayer.core.sync

import android.content.Context
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.sync.platform.AppSignature
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * The signing-certificate SHA-1 sent as `X-Android-Cert`: read from SigningInfo on API 28+ and
 * from the legacy signatures field on API 25-27 (Fire OS 6). Both must give the same value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 36])
class AppSignatureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun certSha1_isUpperHexSha1OfTheSigningCertificate() {
        val certificate = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01)
        val signature = Signature(certificate)
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        @Suppress("DEPRECATION")
        info.signatures = arrayOf(signature)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signature)) }
        }

        val expected = MessageDigest.getInstance("SHA-1").digest(certificate).joinToString("") { "%02X".format(it) }
        assertThat(AppSignature.certSha1(context)).isEqualTo(expected)
    }

    @Test
    fun certSha1_isEmptyWhenNoCertificateIsReported() {
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        @Suppress("DEPRECATION")
        info.signatures = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo = null

        assertThat(AppSignature.certSha1(context)).isEmpty()
    }
}

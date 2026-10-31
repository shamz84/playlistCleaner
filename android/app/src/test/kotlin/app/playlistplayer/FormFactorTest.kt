package app.playlistplayer

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class FormFactorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- Pure decision function ---

    @Test
    fun resolve_televisionUiMode_isTv() {
        assertThat(FormFactor.resolve(Configuration.UI_MODE_TYPE_TELEVISION, hasLeanbackFeature = false))
            .isEqualTo(FormFactor.Tv)
    }

    @Test
    fun resolve_leanbackFeature_isTv() {
        assertThat(FormFactor.resolve(Configuration.UI_MODE_TYPE_NORMAL, hasLeanbackFeature = true))
            .isEqualTo(FormFactor.Tv)
    }

    @Test
    fun resolve_fireTvFeature_isTv() {
        assertThat(
            FormFactor.resolve(
                Configuration.UI_MODE_TYPE_NORMAL,
                hasLeanbackFeature = false,
                hasFireTvFeature = true,
            ),
        ).isEqualTo(FormFactor.Tv)
    }

    @Test
    fun resolve_normalUiModeWithoutTvFeatures_isMobile() {
        assertThat(FormFactor.resolve(Configuration.UI_MODE_TYPE_NORMAL, hasLeanbackFeature = false))
            .isEqualTo(FormFactor.Mobile)
        assertThat(FormFactor.resolve(Configuration.UI_MODE_TYPE_UNDEFINED, hasLeanbackFeature = false))
            .isEqualTo(FormFactor.Mobile)
    }

    // --- Device detection under Robolectric configurations ---

    @Test
    @Config(qualifiers = "television")
    fun detect_televisionConfiguration_isTv() {
        assertThat(FormFactor.detect(context)).isEqualTo(FormFactor.Tv)
    }

    @Test
    fun detect_leanbackFeatureOnNormalUiMode_isTv() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertThat(FormFactor.detect(context)).isEqualTo(FormFactor.Tv)
    }

    @Test
    @Config(qualifiers = "port-finger")
    fun detect_phoneConfiguration_isMobile() {
        assertThat(FormFactor.detect(context)).isEqualTo(FormFactor.Mobile)
    }

    @Test
    @Config(qualifiers = "port-finger-keysexposed-qwerty-navexposed-dpad")
    fun detect_phoneWithKeyboardAndDpad_isStillMobile() {
        val configuration = context.resources.configuration
        assertThat(configuration.keyboard).isEqualTo(Configuration.KEYBOARD_QWERTY)
        assertThat(configuration.navigation).isEqualTo(Configuration.NAVIGATION_DPAD)

        assertThat(FormFactor.detect(context)).isEqualTo(FormFactor.Mobile)
    }

    @Test
    @Config(qualifiers = "notouch-keysexposed-qwerty-navexposed-dpad")
    fun detect_noTouchWithDpadButNormalUiMode_isStillMobile() {
        // Input type alone (no touch, D-pad navigation) must never select the TV tree.
        assertThat(FormFactor.detect(context)).isEqualTo(FormFactor.Mobile)
    }
}

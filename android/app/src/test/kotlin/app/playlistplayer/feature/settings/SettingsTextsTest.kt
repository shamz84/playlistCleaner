package app.playlistplayer.feature.settings

import app.playlistplayer.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsTextsTest {
    @Test
    fun steppersMoveBetweenPresetsAndStopAtTheEnds() {
        val presets = SettingsTexts.REFRESH_PRESETS_HOURS
        assertThat(SettingsTexts.step(presets, 6, +1)).isEqualTo(12)
        assertThat(presets).containsExactly(2, 4, 6, 12).inOrder()
        assertThat(SettingsTexts.step(presets, 6, -1)).isEqualTo(4)
        assertThat(SettingsTexts.step(presets, 5, +1)).isEqualTo(6) // off-preset value snaps to the next one
        assertThat(SettingsTexts.step(presets, 168, +1)).isEqualTo(12)
        assertThat(SettingsTexts.step(presets, 1, -1)).isEqualTo(2)
        assertThat(SettingsTexts.step(presets, 12, +1)).isEqualTo(12)
        assertThat(SettingsTexts.step(presets, 2, -1)).isEqualTo(2)
        assertThat(presets.all { it in SyncPreferences.REFRESH_RANGE }).isTrue()
        assertThat(SettingsTexts.STALE_PRESETS_DAYS.all { it in SyncPreferences.STALE_RANGE }).isTrue()
    }

    @Test
    fun overrideResultsMapToTheirMessages() {
        assertThat(SettingsTexts.testResult(OverrideTestResult.HttpError(404))).isEqualTo(SettingsText(R.string.settings_guide_test_http, 404))
        assertThat(SettingsTexts.testResult(OverrideTestResult.Reachable(10, 3, 4, partial = false)))
            .isEqualTo(SettingsText(R.string.settings_guide_test_ok, 10, 3, 4, 75))
        assertThat(SettingsTexts.message(SettingsMessage.OverrideNotSaved(1, OverrideTestResult.NotXmltv)))
            .isEqualTo(SettingsText(R.string.settings_msg_override_not_saved, SettingsText(R.string.settings_guide_test_not_xmltv)))
        assertThat(SettingsTexts.authorization(Authorization.Denied(2))).isEqualTo(SettingsText(R.string.settings_pin_wrong, 2))
        assertThat(SettingsTexts.authorization(Authorization.PinRequired)).isNull()
        assertThat(SettingsTexts.signedHours(2)).isEqualTo("+2")
        assertThat(SettingsTexts.signedHours(-3)).isEqualTo("-3")
    }
}

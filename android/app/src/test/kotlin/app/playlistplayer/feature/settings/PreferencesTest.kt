package app.playlistplayer.feature.settings

import app.playlistplayer.core.consolidation.DeviceCapabilities
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.Version
import app.playlistplayer.core.consolidation.VersionComparator
import app.playlistplayer.core.player.catchup.CatchupUrlStyle
import app.playlistplayer.core.player.url.LiveFormatPreference
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PreferencesTest {

    @Test
    fun `defaults match the plan`() {
        val prefs = AppPreferences()
        assertThat(prefs.content.languages).containsExactly("en")
        assertThat(prefs.content.quality).isEqualTo(QualityPreference.BEST)
        assertThat(prefs.playback.liveFormat).isEqualTo(LiveFormatPreference.TS)
        assertThat(prefs.playback.matchFrameRate).isFalse()
        assertThat(prefs.playback.tunneling).isFalse()
        assertThat(prefs.playback.showVideoInfo).isFalse()
        assertThat(prefs.playback.showVodDiagnostics).isFalse()
        assertThat(prefs.playback.catchupOffsetHours).isEqualTo(0)
        assertThat(prefs.playback.catchupUrlStyle).isEqualTo(CatchupUrlStyle.PATH)
        assertThat(prefs.sync.refreshIntervalHours).isEqualTo(6)
        assertThat(prefs.sync.staleWarningDays).isEqualTo(7)
        assertThat(prefs.theme).isEqualTo(AppTheme.MIDNIGHT_BLUE)
    }

    @Test
    fun `codec round trips non-default values`() {
        val prefs = AppPreferences(
            content = ContentPreferences(listOf("in-tl", "en", "fr"), QualityPreference.FHD),
            playback = PlaybackPreferences(
                liveFormat = LiveFormatPreference.HLS,
                matchFrameRate = true,
                tunneling = true,
                showVideoInfo = true,
                showVodDiagnostics = true,
                catchupOffsetHours = -12,
                catchupUrlStyle = CatchupUrlStyle.PHP,
            ),
            sync = SyncPreferences(refreshIntervalHours = 24, staleWarningDays = 30),
            playlistOrder = listOf(3L, 1L, 2L),
            theme = AppTheme.CINEMA_EMBER,
        )
        assertThat(PreferencesCodec.decode(PreferencesCodec.encode(prefs))).isEqualTo(prefs)
        assertThat(PreferencesCodec.decode(PreferencesCodec.encode(AppPreferences()))).isEqualTo(AppPreferences())
    }

    @Test
    fun `empty map decodes to defaults`() {
        assertThat(PreferencesCodec.decode(emptyMap())).isEqualTo(AppPreferences())
        assertThat(PreferencesCodec.decode(mapOf(PreferencesCodec.KEY_THEME to "invalid")).theme).isEqualTo(AppTheme.MIDNIGHT_BLUE)
    }

    @Test
    fun `theme cycle includes graphite and codec preserves all choices`() {
        assertThat(AppTheme.entries.map { it.next() }).containsExactly(
            AppTheme.CINEMA_EMBER, AppTheme.GRAPHITE, AppTheme.MIDNIGHT_BLUE,
        ).inOrder()
        AppTheme.entries.forEach { theme ->
            assertThat(PreferencesCodec.decode(PreferencesCodec.encode(AppPreferences(theme = theme))).theme).isEqualTo(theme)
        }
    }

    @Test
    fun `bad values fall back and out of range numbers are coerced`() {
        val decoded = PreferencesCodec.decode(
            mapOf(
                PreferencesCodec.KEY_LANGUAGES to " EN , ,bad tag!,hi,en",
                PreferencesCodec.KEY_QUALITY to "ULTRA",
                PreferencesCodec.KEY_LIVE_FORMAT to "MP4",
                PreferencesCodec.KEY_MATCH_FRAME_RATE to "yes",
                PreferencesCodec.KEY_TUNNELING to "TRUE",
                PreferencesCodec.KEY_CATCHUP_OFFSET to "20",
                PreferencesCodec.KEY_CATCHUP_STYLE to "nope",
                PreferencesCodec.KEY_REFRESH_HOURS to "0",
                PreferencesCodec.KEY_STALE_DAYS to "x",
                "unknown.key" to "ignored",
            ),
        )
        assertThat(decoded.content.languages).containsExactly("en", "hi").inOrder()
        assertThat(decoded.content.quality).isEqualTo(QualityPreference.BEST)
        assertThat(decoded.playback.liveFormat).isEqualTo(LiveFormatPreference.TS)
        assertThat(decoded.playback.matchFrameRate).isFalse()
        assertThat(decoded.playback.tunneling).isTrue()
        assertThat(decoded.playback.catchupOffsetHours).isEqualTo(12)
        assertThat(decoded.playback.catchupUrlStyle).isEqualTo(CatchupUrlStyle.PATH)
        assertThat(decoded.sync.refreshIntervalHours).isEqualTo(1)
        assertThat(decoded.sync.staleWarningDays).isEqualTo(7)

        val negative = PreferencesCodec.decode(mapOf(PreferencesCodec.KEY_CATCHUP_OFFSET to "-13"))
        assertThat(negative.playback.catchupOffsetHours).isEqualTo(-12)
        val noLanguages = PreferencesCodec.decode(mapOf(PreferencesCodec.KEY_LANGUAGES to ",,"))
        assertThat(noLanguages.content.languages).containsExactly("en")
    }

    @Test
    fun `catch-up offset range is enforced`() {
        assertThat(PlaybackPreferences(catchupOffsetHours = 12).catchupOffsetHours).isEqualTo(12)
        assertThat(PlaybackPreferences(catchupOffsetHours = -12).catchupOffsetHours).isEqualTo(-12)
        assertThrows(IllegalArgumentException::class.java) { PlaybackPreferences(catchupOffsetHours = 13) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackPreferences(catchupOffsetHours = -13) }
    }

    @Test
    fun `sync ranges are enforced`() {
        assertThrows(IllegalArgumentException::class.java) { SyncPreferences(refreshIntervalHours = 0) }
        assertThrows(IllegalArgumentException::class.java) { SyncPreferences(staleWarningDays = 0) }
    }

    @Test
    fun `language list reorders adds and removes`() {
        val prefs = ContentPreferences().addLanguage("HI").addLanguage("fr").addLanguage("en")
        assertThat(prefs.languages).containsExactly("en", "hi", "fr").inOrder()
        assertThat(prefs.moveLanguage(1, 0).languages).containsExactly("hi", "en", "fr").inOrder()
        assertThat(prefs.moveLanguage(0, 2).languages).containsExactly("hi", "fr", "en").inOrder()
        assertThat(prefs.removeLanguage("hi").languages).containsExactly("en", "fr").inOrder()
        assertThat(ContentPreferences().removeLanguage("en").languages).containsExactly("en")
        assertThrows(IllegalArgumentException::class.java) { prefs.moveLanguage(0, 3) }
        assertThrows(IllegalArgumentException::class.java) { ContentPreferences(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ContentPreferences(listOf("en", "en")) }
    }

    @Test
    fun `reordering languages changes the default version`() {
        val english = Version(id = "en", language = "en", quality = QualityTier.FHD)
        val hindi = Version(id = "hi", language = "hi", quality = QualityTier.FHD)
        val base = ContentPreferences(listOf("en", "hi"))
        val best = listOf(hindi, english).sortedWith(VersionComparator(base.versionPreferences())).first()
        assertThat(best.id).isEqualTo("en")

        val reordered = base.moveLanguage(1, 0)
        val bestAfter = listOf(english, hindi).sortedWith(VersionComparator(reordered.versionPreferences())).first()
        assertThat(bestAfter.id).isEqualTo("hi")
    }

    @Test
    fun `quality preference caps the device height`() {
        val device = DeviceCapabilities(maxVideoHeight = 2160)
        assertThat(ContentPreferences().versionPreferences(device).device.maxVideoHeight).isEqualTo(2160)
        val hd = ContentPreferences(quality = QualityPreference.HD).versionPreferences(device)
        assertThat(hd.device.maxVideoHeight).isEqualTo(720)
        assertThat(hd.languages).containsExactly("en")
    }
}

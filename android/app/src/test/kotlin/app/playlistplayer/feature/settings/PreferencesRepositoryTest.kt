package app.playlistplayer.feature.settings

import app.playlistplayer.core.player.catchup.CatchupUrlStyle
import app.playlistplayer.core.player.url.LiveFormatPreference
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PreferencesRepositoryTest {

    private val slots = InMemorySecretSlotStore()
    private val storage = SecretSlotPreferencesStorage(slots)

    @Test
    fun defaultsWhenNothingIsStored() = runTest {
        val repo = PreferencesRepository(storage)
        assertThat(repo.preferences.first()).isEqualTo(AppPreferences())
        assertThat(repo.current().content.languages).containsExactly("en")
    }

    @Test
    fun everySettingPersistsAcrossANewRepositoryInstance() = runTest {
        val repo = PreferencesRepository(storage)
        repo.addLanguage("HI")
        repo.moveLanguage(1, 0)
        repo.setQuality(QualityPreference.FHD)
        repo.setLiveFormat(LiveFormatPreference.HLS)
        repo.setMatchFrameRate(true)
        repo.setTunneling(true)
        repo.setCatchupOffsetHours(-2)
        repo.setCatchupUrlStyle(CatchupUrlStyle.PHP)
        repo.setRefreshIntervalHours(12)
        repo.setStaleWarningDays(3)

        val reopened = PreferencesRepository(SecretSlotPreferencesStorage(slots)).current()
        assertThat(reopened.content.languages).containsExactly("hi", "en").inOrder()
        assertThat(reopened.content.quality).isEqualTo(QualityPreference.FHD)
        assertThat(reopened.playback).isEqualTo(
            PlaybackPreferences(LiveFormatPreference.HLS, matchFrameRate = true, tunneling = true, catchupOffsetHours = -2, catchupUrlStyle = CatchupUrlStyle.PHP),
        )
        assertThat(reopened.sync).isEqualTo(SyncPreferences(refreshIntervalHours = 12, staleWarningDays = 3))
    }

    @Test
    fun numbersAreClampedIntoTheirRanges() = runTest {
        val repo = PreferencesRepository(storage)
        assertThat(repo.setRefreshIntervalHours(0)).isEqualTo(1)
        assertThat(repo.setRefreshIntervalHours(10_000)).isEqualTo(168)
        assertThat(repo.setStaleWarningDays(-5)).isEqualTo(1)
        assertThat(repo.setStaleWarningDays(365)).isEqualTo(90)
        assertThat(repo.setCatchupOffsetHours(99)).isEqualTo(12)
        assertThat(repo.setCatchupOffsetHours(-99)).isEqualTo(-12)
        val prefs = PreferencesRepository(storage).current()
        assertThat(prefs.sync).isEqualTo(SyncPreferences(refreshIntervalHours = 168, staleWarningDays = 90))
        assertThat(prefs.playback.catchupOffsetHours).isEqualTo(-12)
    }

    @Test
    fun storedOutOfRangeValuesAreCoercedOnRead() = runTest {
        val raw = InMemoryPreferencesStorage(
            mapOf(PreferencesCodec.KEY_REFRESH_HOURS to "0", PreferencesCodec.KEY_STALE_DAYS to "999", PreferencesCodec.KEY_CATCHUP_OFFSET to "x"),
        )
        val prefs = PreferencesRepository(raw).current()
        assertThat(prefs.sync).isEqualTo(SyncPreferences(refreshIntervalHours = 1, staleWarningDays = 90))
        assertThat(prefs.playback.catchupOffsetHours).isEqualTo(0)
    }

    @Test
    fun languageEditsValidateAndKeepAtLeastOne() = runTest {
        val repo = PreferencesRepository(storage)
        assertThat(repo.addLanguage("not a tag!")).isFalse()
        assertThat(repo.addLanguage(" IN-TA ")).isTrue()
        assertThat(repo.current().content.languages).containsExactly("en", "in-ta").inOrder()
        assertThat(repo.moveLanguage(0, 5)).isFalse()
        assertThat(repo.setLanguages(emptyList())).isFalse()
        assertThat(repo.setLanguages(listOf("FR", "fr", "en"))).isTrue()
        assertThat(repo.current().content.languages).containsExactly("fr", "en").inOrder()
        repo.removeLanguage("fr")
        repo.removeLanguage("en") // the last language stays
        assertThat(repo.current().content.languages).containsExactly("en")
    }

    @Test
    fun unreadableOrCorruptRecordFallsBackToDefaults() = runTest {
        PreferencesRepository(storage).setQuality(QualityPreference.SD)
        slots.unreadable += SecretSlotPreferencesStorage.SLOT
        assertThat(PreferencesRepository(storage).current()).isEqualTo(AppPreferences())

        slots.unreadable.clear()
        slots.slots[SecretSlotPreferencesStorage.SLOT] = "garbage"
        assertThat(PreferencesRepository(storage).current()).isEqualTo(AppPreferences())
    }

    @Test
    fun resetRestoresDefaultsAndRemovesTheRecord() = runTest {
        val repo = PreferencesRepository(storage)
        repo.setTunneling(true)
        repo.reset()
        assertThat(repo.current()).isEqualTo(AppPreferences())
        assertThat(slots.slots).doesNotContainKey(SecretSlotPreferencesStorage.SLOT)
    }
}

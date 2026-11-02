package app.playlistplayer.feature.vod

import app.playlistplayer.feature.settings.InMemoryPreferencesStorage
import app.playlistplayer.feature.settings.PreferencesRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackPreferenceTest {

    private val englishStereo = TrackOption("0:0", TrackType.AUDIO, "English · Stereo", "en", selected = true)
    private val french = TrackOption("1:0", TrackType.AUDIO, "French · 5.1", "fr")
    private val unknownAudio = TrackOption("2:0", TrackType.AUDIO, "Audio 3", null)
    private val englishForced = TrackOption("3:0", TrackType.TEXT, "English · Forced", "en", forced = true)
    private val englishSubs = TrackOption("3:1", TrackType.TEXT, "English", "en")
    private val frenchSubs = TrackOption("4:0", TrackType.TEXT, "French", "fr")
    private val unknownSubs = TrackOption("5:0", TrackType.TEXT, "Subtitles 4", null)
    private val tracks = listOf(englishStereo, french, unknownAudio, englishForced, englishSubs, frenchSubs, unknownSubs)

    private val audio = tracks.filter { it.type == TrackType.AUDIO }
    private val subtitles = tracks.filter { it.type == TrackType.TEXT }

    // ---- resolver ----

    @Test
    fun preferredAudioLanguageIsChosenWhenPresent() {
        assertThat(TrackPreferenceResolver.audio(audio, TrackPreference(audioLanguage = "fr"))).isEqualTo("1:0")
        assertThat(TrackPreferenceResolver.audio(audio, TrackPreference(audioLanguage = "fra"))).isEqualTo("1:0")
    }

    @Test
    fun absentAudioLanguageFallsBackToTheDeviceLanguageThenEnglishThenThePlayersTrack() {
        assertThat(TrackPreferenceResolver.audio(audio, TrackPreference(audioLanguage = "de", deviceLanguage = "fr"))).isEqualTo("1:0")
        assertThat(TrackPreferenceResolver.audio(audio, TrackPreference(audioLanguage = "de", deviceLanguage = "nl"))).isEqualTo("0:0")
        val noEnglish = listOf(french.copy(selected = false), unknownAudio.copy(selected = true))
        assertThat(TrackPreferenceResolver.audio(noEnglish, TrackPreference(audioLanguage = "de"))).isEqualTo("2:0")
        assertThat(TrackPreferenceResolver.audio(emptyList(), TrackPreference())).isNull()
    }

    @Test
    fun defaultPreferenceIsDeviceOrEnglishAudioAndSubtitlesOff() {
        val pref = TrackPreference(deviceLanguage = "nl")
        assertThat(pref.audioLanguages).containsExactly("nl", "en").inOrder()
        assertThat(pref.subtitlesOff).isTrue()
        assertThat(TrackPreferenceResolver.subtitle(subtitles, pref)).isNull()
    }

    @Test
    fun preferredSubtitleLanguagePrefersAFullTrackOverAForcedOne() {
        assertThat(TrackPreferenceResolver.subtitle(subtitles, TrackPreference(subtitleLanguage = "en"))).isEqualTo("3:1")
        assertThat(TrackPreferenceResolver.subtitle(subtitles, TrackPreference(subtitleLanguage = "fr"))).isEqualTo("4:0")
        // The player's own pick in that language is kept.
        val playerPicked = subtitles.map { it.copy(selected = it.id == "3:0") }
        assertThat(TrackPreferenceResolver.subtitle(playerPicked, TrackPreference(subtitleLanguage = "en"))).isEqualTo("3:0")
    }

    @Test
    fun absentSubtitleLanguageTurnsSubtitlesOff() {
        assertThat(TrackPreferenceResolver.subtitle(subtitles, TrackPreference(subtitleLanguage = "de"))).isNull()
        assertThat(TrackPreferenceResolver.subtitle(emptyList(), TrackPreference(subtitleLanguage = "en"))).isNull()
    }

    // ---- overlay ----

    @Test
    fun openingWithAPreferenceSelectsThePreferredTracksWhenTheyArrive() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null, trackPreference = TrackPreference(audioLanguage = "fr", subtitleLanguage = "en")))
        // The player picked English audio and no subtitles: the preference is applied.
        assertThat(overlay.handle(OverlayEvent.TracksChanged(tracks))).containsExactly(OverlayCommand.ApplyTracks("1:0", "3:1"))
        val model = overlay.state.tracks
        assertThat(model.audio.single { it.selected }.id).isEqualTo("1:0")
        assertThat(model.subtitles.single { it.selected }.id).isEqualTo("3:1")

        // Once the player reports that selection, nothing more is applied.
        val applied = tracks.map { it.copy(selected = it.id == "1:0" || it.id == "3:1") }
        assertThat(overlay.handle(OverlayEvent.TracksChanged(applied))).isEmpty()
    }

    @Test
    fun noCommandWhenThePlayerAlreadyChoseThePreferredTracks() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null, trackPreference = TrackPreference()))
        assertThat(overlay.handle(OverlayEvent.TracksChanged(tracks))).isEmpty()
        assertThat(overlay.state.tracks.selectedAudioId).isEqualTo("0:0")
        assertThat(overlay.state.tracks.selectedSubtitleId).isNull()
    }

    @Test
    fun manualChoiceAppliesAtOnceAndBecomesThePreference() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null, trackPreference = TrackPreference()))
        overlay.handle(OverlayEvent.TracksChanged(tracks))

        assertThat(overlay.handle(OverlayEvent.SelectAudio("1:0"))).containsExactly(
            OverlayCommand.ApplyTracks("1:0", null),
            OverlayCommand.SaveTrackPreference(TrackPreference(audioLanguage = "fr")),
        ).inOrder()
        assertThat(overlay.handle(OverlayEvent.SelectSubtitle("4:0"))).containsExactly(
            OverlayCommand.ApplyTracks("1:0", "4:0"),
            OverlayCommand.SaveTrackPreference(TrackPreference(audioLanguage = "fr", subtitleLanguage = "fr")),
        ).inOrder()
        // Off is remembered too.
        assertThat(overlay.handle(OverlayEvent.SelectSubtitle(null))).containsExactly(
            OverlayCommand.ApplyTracks("1:0", null),
            OverlayCommand.SaveTrackPreference(TrackPreference(audioLanguage = "fr")),
        ).inOrder()
    }

    @Test
    fun unknownLanguageChoiceAppliesButKeepsThePreference() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null, trackPreference = TrackPreference(audioLanguage = "fr")))
        overlay.handle(OverlayEvent.TracksChanged(tracks))
        assertThat(overlay.handle(OverlayEvent.SelectAudio("2:0"))).containsExactly(OverlayCommand.ApplyTracks("2:0", null))
        assertThat(overlay.state.tracks.preference).isEqualTo(TrackPreference(audioLanguage = "fr"))

        // After a manual pick the preference no longer overrides the user on track refreshes.
        assertThat(overlay.handle(OverlayEvent.TracksChanged(tracks))).isEmpty()
        assertThat(overlay.state.tracks.selectedAudioId).isEqualTo("2:0")
    }

    @Test
    fun nextItemStartsFreshFromThePreference() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null, trackPreference = TrackPreference()))
        overlay.handle(OverlayEvent.TracksChanged(tracks))
        overlay.handle(OverlayEvent.SelectAudio("1:0"))
        val carried = overlay.state.tracks.preference!!

        overlay.handle(OverlayEvent.Open(null, trackPreference = carried))
        assertThat(overlay.state.tracks.manual).isFalse()
        assertThat(overlay.state.tracks.audio).isEmpty()
        // The next episode's English-first track list follows the remembered French choice.
        assertThat(overlay.handle(OverlayEvent.TracksChanged(tracks))).containsExactly(OverlayCommand.ApplyTracks("1:0", null))
    }

    @Test
    fun withoutAPreferenceThePlayersSelectionIsKept() {
        val overlay = VodPlayerOverlayState()
        overlay.handle(OverlayEvent.Open(null))
        assertThat(overlay.handle(OverlayEvent.TracksChanged(tracks))).isEmpty()
        assertThat(overlay.handle(OverlayEvent.SelectAudio("1:0"))).containsExactly(OverlayCommand.ApplyTracks("1:0", null))
    }

    // ---- store ----

    @Test
    fun preferenceStorePersistsThroughTheAppPreferences() = runTest {
        val storage = InMemoryPreferencesStorage()
        val store = PreferencesTrackPreferenceStore(PreferencesRepository(storage), deviceLanguage = { "nl" })
        assertThat(store.current()).isEqualTo(TrackPreference(subtitleLanguage = "en", deviceLanguage = "nl"))

        store.save(TrackPreference(audioLanguage = "fr", subtitleLanguage = "en"))
        val reopened = PreferencesTrackPreferenceStore(PreferencesRepository(storage), deviceLanguage = { "nl" })
        assertThat(reopened.current()).isEqualTo(TrackPreference(audioLanguage = "fr", subtitleLanguage = "en", deviceLanguage = "nl"))

        reopened.save(TrackPreference(audioLanguage = "fr", subtitleLanguage = null))
        assertThat(PreferencesTrackPreferenceStore(PreferencesRepository(storage)).current().subtitleLanguage).isNull()
    }

    @Test
    fun unreadableStoreFallsBackToDefaults() = runTest {
        val broken = object : TrackPreferenceStore {
            override suspend fun current(): TrackPreference = error("unreadable")
            override suspend fun save(preference: TrackPreference) = error("unwritable")
        }
        assertThat(broken.currentOrDefault()).isEqualTo(TrackPreference())
        broken.saveQuietly(TrackPreference(audioLanguage = "fr")) // does not throw
    }
}

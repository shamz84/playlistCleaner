package app.playlistplayer.feature.vod

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VodPlayerOverlayStateTest {

    private val overlay = VodPlayerOverlayState()
    private val duration = 2_700_000L // 45 min

    private fun progress(position: Long, now: Long) = overlay.handle(OverlayEvent.Progress(position, duration, isPlaying = true, nowMs = now))

    @Test
    fun `resumable start shows the prompt and waits`() {
        assertThat(overlay.handle(OverlayEvent.Open(resumeOfferMs = 600_000))).isEmpty()
        assertThat(overlay.state.resumePrompt).isEqualTo(ResumePrompt(600_000))
        assertThat(overlay.state.started).isFalse()
        // Seek and play/pause do nothing while the prompt is up.
        assertThat(overlay.handle(OverlayEvent.PlayPause)).isEmpty()
        assertThat(overlay.handle(OverlayEvent.SeekKey(SeekDirection.FORWARD, 0))).isEmpty()

        assertThat(overlay.handle(OverlayEvent.ChooseResume)).containsExactly(OverlayCommand.Start(600_000))
        assertThat(overlay.state.resumePrompt).isNull()
        assertThat(overlay.state.started).isTrue()
    }

    @Test
    fun `start over starts at zero`() {
        overlay.handle(OverlayEvent.Open(resumeOfferMs = 600_000))
        assertThat(overlay.handle(OverlayEvent.ChooseStartOver)).containsExactly(OverlayCommand.Start(0))
        assertThat(overlay.handle(OverlayEvent.ChooseStartOver)).isEmpty()
    }

    @Test
    fun `no resume offer starts immediately`() {
        assertThat(overlay.handle(OverlayEvent.Open(resumeOfferMs = null, startPositionMs = 5_000))).containsExactly(OverlayCommand.Start(5_000))
        assertThat(overlay.state.resumePrompt).isNull()
    }

    @Test
    fun `play pause toggles`() {
        overlay.handle(OverlayEvent.Open(null))
        assertThat(overlay.handle(OverlayEvent.PlayPause)).containsExactly(OverlayCommand.Pause)
        assertThat(overlay.state.controlsVisible).isTrue()
        assertThat(overlay.handle(OverlayEvent.PlayPause)).containsExactly(OverlayCommand.Resume)
    }

    @Test
    fun `single presses seek -10 s and +30 s on key up`() {
        overlay.handle(OverlayEvent.Open(null))
        progress(600_000, 0)
        assertThat(overlay.handle(OverlayEvent.SeekKey(SeekDirection.FORWARD, 0))).isEmpty()
        assertThat(overlay.state.seek!!.targetMs).isEqualTo(630_000)
        assertThat(overlay.handle(OverlayEvent.SeekKeyUp)).containsExactly(OverlayCommand.SeekTo(630_000))
        assertThat(overlay.state.seek).isNull()

        overlay.handle(OverlayEvent.SeekKey(SeekDirection.BACK, 0))
        assertThat(overlay.handle(OverlayEvent.SeekKeyUp)).containsExactly(OverlayCommand.SeekTo(620_000))
    }

    @Test
    fun `holding the key accelerates and commits once`() {
        assertThat(SeekAccelerator.stepMs(SeekDirection.FORWARD, 0)).isEqualTo(30_000)
        assertThat(SeekAccelerator.stepMs(SeekDirection.FORWARD, 3)).isEqualTo(60_000)
        assertThat(SeekAccelerator.stepMs(SeekDirection.FORWARD, 8)).isEqualTo(120_000)
        assertThat(SeekAccelerator.stepMs(SeekDirection.BACK, 15)).isEqualTo(-80_000)

        overlay.handle(OverlayEvent.Open(null))
        progress(0, 0)
        val commands = (0..9).flatMap { overlay.handle(OverlayEvent.SeekKey(SeekDirection.FORWARD, it)) }
        assertThat(commands).isEmpty()
        // 3×30 + 5×60 + 2×120 = 630 s
        assertThat(overlay.state.seek!!.targetMs).isEqualTo(630_000)
        assertThat(overlay.state.seek!!.stepMs).isEqualTo(120_000)
        // Progress while previewing does not move the target anchor.
        progress(1_000, 1)
        assertThat(overlay.handle(OverlayEvent.SeekKeyUp)).containsExactly(OverlayCommand.SeekTo(630_000))
    }

    @Test
    fun `seek clamps to the duration and zero`() {
        overlay.handle(OverlayEvent.Open(null))
        progress(duration - 10_000, 0)
        overlay.handle(OverlayEvent.CancelNext) // ignore the credits card here
        overlay.handle(OverlayEvent.SeekKey(SeekDirection.FORWARD, 0))
        assertThat(overlay.handle(OverlayEvent.SeekKeyUp)).containsExactly(OverlayCommand.SeekTo(duration))
        progress(5_000, 1)
        overlay.handle(OverlayEvent.SeekKey(SeekDirection.BACK, 0))
        assertThat(overlay.handle(OverlayEvent.SeekKeyUp)).containsExactly(OverlayCommand.SeekTo(0))
    }

    @Test
    fun `next-episode card counts down 10 s at the credits then plays next`() {
        overlay.handle(OverlayEvent.Open(null, nextLabel = "S1 E5"))
        assertThat(progress(2_500_000, 0)).isEmpty() // before 95% and the last 30 s
        assertThat(overlay.state.nextEpisode).isNull()
        assertThat(progress(duration - 30_000, 1_000)).isEmpty()
        assertThat(overlay.state.nextEpisode).isEqualTo(NextEpisodeCard("S1 E5", 10))
        overlay.handle(OverlayEvent.Tick(4_500))
        assertThat(overlay.state.nextEpisode!!.secondsRemaining).isEqualTo(7)
        overlay.handle(OverlayEvent.Tick(10_999))
        assertThat(overlay.state.nextEpisode!!.secondsRemaining).isEqualTo(1)
        assertThat(overlay.handle(OverlayEvent.Tick(11_000))).containsExactly(OverlayCommand.PlayNext)
        assertThat(overlay.state.nextEpisode).isNull()
        // Only once.
        assertThat(overlay.handle(OverlayEvent.Tick(12_000))).isEmpty()
        assertThat(overlay.handle(OverlayEvent.Ended)).isEmpty()
    }

    @Test
    fun `credits also start at 95 percent of a long item`() {
        assertThat(VodPlayerOverlayState.inCredits(9_500_000, 10_000_000)).isTrue()
        assertThat(VodPlayerOverlayState.inCredits(9_000_000, 10_000_000)).isFalse()
        assertThat(VodPlayerOverlayState.inCredits(0, 0)).isFalse()
    }

    @Test
    fun `cancel hides the card and the end does not advance`() {
        overlay.handle(OverlayEvent.Open(null, nextLabel = "S1 E5"))
        progress(duration - 20_000, 0)
        assertThat(overlay.state.nextEpisode).isNotNull()
        overlay.handle(OverlayEvent.CancelNext)
        assertThat(overlay.state.nextEpisode).isNull()
        assertThat(progress(duration - 15_000, 20_000)).isEmpty()
        assertThat(overlay.state.nextEpisode).isNull()
        assertThat(overlay.handle(OverlayEvent.Ended)).isEmpty()
        assertThat(overlay.state.controlsVisible).isTrue()
    }

    @Test
    fun `play now skips the countdown, and the end of an episode advances`() {
        overlay.handle(OverlayEvent.Open(null, nextLabel = "S1 E5"))
        progress(duration - 20_000, 0)
        assertThat(overlay.handle(OverlayEvent.PlayNextNow)).containsExactly(OverlayCommand.PlayNext)

        val other = VodPlayerOverlayState()
        other.handle(OverlayEvent.Open(null, nextLabel = "S1 E6"))
        assertThat(other.handle(OverlayEvent.Ended)).containsExactly(OverlayCommand.PlayNext)
    }

    @Test
    fun `no next episode means no card`() {
        overlay.handle(OverlayEvent.Open(null, nextLabel = null))
        progress(duration - 1_000, 0)
        assertThat(overlay.state.nextEpisode).isNull()
        assertThat(overlay.handle(OverlayEvent.Ended)).isEmpty()
    }

    @Test
    fun `seeking back out of the credits hides the card`() {
        overlay.handle(OverlayEvent.Open(null, nextLabel = "S1 E5"))
        progress(duration - 20_000, 0)
        assertThat(overlay.state.nextEpisode).isNotNull()
        (0..15).forEach { overlay.handle(OverlayEvent.SeekKey(SeekDirection.BACK, it)) }
        overlay.handle(OverlayEvent.SeekKeyUp)
        assertThat(overlay.state.nextEpisode).isNull()
        assertThat(overlay.handle(OverlayEvent.Tick(20_000))).isEmpty()
    }

    @Test
    fun `track selection model picks, keeps and outputs ids`() {
        val tracks = listOf(
            TrackOption("0:0", TrackType.AUDIO, "English", "en", selected = true),
            TrackOption("0:1", TrackType.AUDIO, "French", "fr"),
            TrackOption("1:0", TrackType.TEXT, "English", "en"),
        )
        overlay.handle(OverlayEvent.Open(null))
        overlay.handle(OverlayEvent.TracksChanged(tracks))
        val model = overlay.state.tracks
        assertThat(model.selectedAudioId).isEqualTo("0:0")
        assertThat(model.selectedSubtitleId).isNull()
        assertThat(model.hasChoices).isTrue()

        assertThat(overlay.handle(OverlayEvent.SelectAudio("0:1"))).containsExactly(OverlayCommand.ApplyTracks("0:1", null))
        assertThat(overlay.handle(OverlayEvent.SelectSubtitle("1:0"))).containsExactly(OverlayCommand.ApplyTracks("0:1", "1:0"))
        assertThat(overlay.state.tracks.audio.single { it.selected }.id).isEqualTo("0:1")
        assertThat(overlay.handle(OverlayEvent.SelectSubtitle("9:9"))).isEmpty() // unknown id ignored
        assertThat(overlay.handle(OverlayEvent.SelectSubtitle(null))).containsExactly(OverlayCommand.ApplyTracks("0:1", null))

        // A refreshed track list keeps the user's choice when it still exists.
        overlay.handle(OverlayEvent.TracksChanged(tracks))
        assertThat(overlay.state.tracks.selectedAudioId).isEqualTo("0:1")
        overlay.handle(OverlayEvent.TracksChanged(tracks.filter { it.id != "0:1" }))
        assertThat(overlay.state.tracks.selectedAudioId).isEqualTo("0:0")
    }

    @Test
    fun `track panel toggles and hides with controls`() {
        overlay.handle(OverlayEvent.Open(null))
        overlay.handle(OverlayEvent.ToggleTrackPanel)
        assertThat(overlay.state.trackPanelOpen).isTrue()
        overlay.handle(OverlayEvent.HideControls)
        assertThat(overlay.state.trackPanelOpen).isFalse()
    }

    @Test
    fun `absolute seek commits at once and clamps to the duration`() {
        assertThat(overlay.handle(OverlayEvent.SeekTo(10_000))).isEmpty() // not started yet
        overlay.handle(OverlayEvent.Open(null))
        progress(60_000, now = 0)
        assertThat(overlay.handle(OverlayEvent.SeekTo(1_200_000))).containsExactly(OverlayCommand.SeekTo(1_200_000))
        assertThat(overlay.state.positionMs).isEqualTo(1_200_000)
        assertThat(overlay.state.seek).isNull()
        assertThat(overlay.handle(OverlayEvent.SeekTo(duration + 60_000))).containsExactly(OverlayCommand.SeekTo(duration))
    }
}

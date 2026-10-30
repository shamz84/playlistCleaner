package app.playlistplayer.feature.onboarding

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OnboardingViewModelTest {

    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123-_9"
    private val xtreamUrl = "http://provider.test/get.php?username=u&password=p&type=m3u_plus"
    private val plainUrl = "https://lists.test/channels.m3u"

    private class FakeOnboarder : PlaylistOnboarder {
        val progressFlow = MutableSharedFlow<OnboardingProgress>(extraBufferCapacity = 32)
        override val progress = progressFlow
        val calls = mutableListOf<Triple<String, SourceInput, Boolean>>()
        var result = CompletableDeferred<OnboardingResult>()
        var throwOnAdd: Exception? = null
        var cancelled = false

        override suspend fun addPlaylist(name: String, source: SourceInput, xtreamEnabled: Boolean): OnboardingResult {
            calls += Triple(name, source, xtreamEnabled)
            throwOnAdd?.let { throw it }
            try {
                return result.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private val onboarder = FakeOnboarder()

    private fun TestScope.newViewModel() = OnboardingViewModel(
        onboarder = onboarder,
        isXtreamShaped = { it.contains("get.php?username=") || it.contains("/live/") },
        externalScope = backgroundScope,
    )

    private fun TestScope.emit(stage: OnboardingStage, status: StageStatus) {
        check(onboarder.progressFlow.tryEmit(OnboardingProgress(stage, status)))
        runCurrent()
    }

    private fun TestScope.submittedDriveViewModel(): OnboardingViewModel = newViewModel().apply {
        onDriveTextChanged(id)
        submit()
        runCurrent()
    }

    // --- Defaults ---

    @Test
    fun `defaults are My playlist, Drive source, Xtream on, nothing to submit`() = runTest {
        val s = newViewModel().state.value
        assertThat(s.name).isEqualTo("My playlist")
        assertThat(s.sourceType).isEqualTo(SourceType.DriveId)
        assertThat(s.xtreamEnabled).isTrue()
        assertThat(s.xtreamToggleEnabled).isTrue()
        assertThat(s.canSubmit).isFalse()
        assertThat(s.canContinue).isFalse()
    }

    // --- Drive input assist ---

    @Test
    fun `pasted share link is reduced to the id and its resource key, grouped for display`() = runTest {
        val vm = newViewModel()
        vm.onDriveTextChanged("https://drive.google.com/file/d/$id/view?usp=sharing&resourcekey=0-rk")
        val s = vm.state.value
        assertThat(s.driveId).isEqualTo(id)
        assertThat(s.driveIdFromLink).isTrue()
        assertThat(s.hasResourceKey).isTrue()
        assertThat(s.driveValidation).isEqualTo(DriveIdValidation.Valid)
        assertThat(s.driveIdForDisplay).isEqualTo(DriveIdRules.groupForDisplay(id))
        assertThat(s.canSubmit).isTrue()

        vm.submit()
        runCurrent()
        assertThat(onboarder.calls.single().second).isEqualTo(SourceInput.DriveId(id, "0-rk"))
    }

    @Test
    fun `typing validates live`() = runTest {
        val vm = newViewModel()
        vm.onDriveTextChanged("1AbC")
        assertThat(vm.state.value.driveValidation).isEqualTo(DriveIdValidation.TooShort)
        assertThat(vm.state.value.canSubmit).isFalse()
        vm.onDriveTextChanged("1AbC dEf")
        assertThat(vm.state.value.driveValidation).isEqualTo(DriveIdValidation.InvalidCharacters)
        vm.onDriveTextChanged(id)
        assertThat(vm.state.value.driveValidation).isEqualTo(DriveIdValidation.Valid)
        vm.onDriveTextChanged("https://example.com/x")
        assertThat(vm.state.value.driveValidation).isEqualTo(DriveIdValidation.UnrecognisedLink)
    }

    @Test
    fun `submit is a no-op while the source is invalid`() = runTest {
        val vm = newViewModel()
        vm.onDriveTextChanged("short")
        vm.submit()
        runCurrent()
        assertThat(onboarder.calls).isEmpty()
        assertThat(vm.state.value.phase).isEqualTo(OnboardingPhase.Form)
    }

    @Test
    fun `blank name submits the default name, trimmed names are kept`() = runTest {
        val vm = newViewModel()
        vm.onNameChanged("   ")
        vm.onDriveTextChanged(id)
        vm.submit()
        runCurrent()
        assertThat(onboarder.calls.single().first).isEqualTo("My playlist")

        val vm2 = newViewModel()
        vm2.onNameChanged("  Family  ")
        vm2.onDriveTextChanged(id)
        vm2.submit()
        runCurrent()
        assertThat(onboarder.calls.last().first).isEqualTo("Family")
    }

    // --- M3U URL and the Xtream toggle ---

    @Test
    fun `m3u url that is not Xtream-shaped disables the toggle and submits without Xtream`() = runTest {
        val vm = newViewModel()
        vm.onSourceTypeChanged(SourceType.M3uUrl)
        vm.onM3uTextChanged(plainUrl)
        val s = vm.state.value
        assertThat(s.m3uValidation).isEqualTo(M3uUrlValidation.Valid)
        assertThat(s.xtreamAvailability).isEqualTo(XtreamAvailability.NotXtreamShaped)
        assertThat(s.xtreamToggleEnabled).isFalse()
        assertThat(s.xtreamEnabled).isFalse()

        vm.onXtreamToggled(true) // ignored while disabled
        assertThat(vm.state.value.xtreamEnabled).isFalse()

        vm.submit()
        runCurrent()
        assertThat(onboarder.calls.single()).isEqualTo(Triple("My playlist", SourceInput.M3uUrl(plainUrl), false))
        assertThat(vm.state.value.stages[OnboardingStage.Catalog]).isEqualTo(StageStatus.Skipped)
        assertThat(vm.state.value.stages[OnboardingStage.Guide]).isEqualTo(StageStatus.Skipped)
    }

    @Test
    fun `Xtream-shaped m3u url keeps the toggle available and on`() = runTest {
        val vm = newViewModel()
        vm.onSourceTypeChanged(SourceType.M3uUrl)
        vm.onM3uTextChanged(xtreamUrl)
        assertThat(vm.state.value.xtreamAvailability).isEqualTo(XtreamAvailability.Available)
        assertThat(vm.state.value.xtreamToggleEnabled).isTrue()
        assertThat(vm.state.value.xtreamEnabled).isTrue()
    }

    @Test
    fun `user choice is restored when the url becomes Xtream-shaped or the source switches to Drive`() = runTest {
        val vm = newViewModel()
        vm.onSourceTypeChanged(SourceType.M3uUrl)
        vm.onM3uTextChanged(plainUrl)
        assertThat(vm.state.value.xtreamEnabled).isFalse()
        vm.onM3uTextChanged(xtreamUrl)
        assertThat(vm.state.value.xtreamEnabled).isTrue()
        vm.onM3uTextChanged(plainUrl)
        vm.onSourceTypeChanged(SourceType.DriveId)
        assertThat(vm.state.value.xtreamAvailability).isEqualTo(XtreamAvailability.UnknownUntilDownload)
        assertThat(vm.state.value.xtreamEnabled).isTrue()
    }

    @Test
    fun `Drive source keeps the toggle on and lets the user turn it off`() = runTest {
        val vm = newViewModel()
        vm.onDriveTextChanged(id)
        assertThat(vm.state.value.xtreamToggleEnabled).isTrue()
        vm.onXtreamToggled(false)
        vm.submit()
        runCurrent()
        assertThat(onboarder.calls.single().third).isFalse()
    }

    @Test
    fun `invalid m3u urls cannot be submitted`() = runTest {
        val vm = newViewModel()
        vm.onSourceTypeChanged(SourceType.M3uUrl)
        vm.onM3uTextChanged("ftp://lists.test/x.m3u")
        assertThat(vm.state.value.m3uValidation).isEqualTo(M3uUrlValidation.NotHttp)
        assertThat(vm.state.value.canSubmit).isFalse()
    }

    // --- Progress and canContinue ---

    @Test
    fun `channels are usable only after the Channels stage`() = runTest {
        val vm = submittedDriveViewModel()
        assertThat(vm.state.value.phase).isEqualTo(OnboardingPhase.Progress)
        assertThat(vm.state.value.canContinue).isFalse()

        emit(OnboardingStage.Playlist, StageStatus.Running)
        emit(OnboardingStage.Playlist, StageStatus.Done)
        emit(OnboardingStage.Channels, StageStatus.Running)
        assertThat(vm.state.value.canContinue).isFalse()
        assertThat(vm.continueToApp()).isFalse()

        emit(OnboardingStage.Channels, StageStatus.Done)
        assertThat(vm.state.value.canContinue).isTrue()
        assertThat(vm.state.value.stages[OnboardingStage.Catalog]).isEqualTo(StageStatus.Pending)

        emit(OnboardingStage.Catalog, StageStatus.Running)
        assertThat(vm.state.value.stages[OnboardingStage.Catalog]).isEqualTo(StageStatus.Running)

        assertThat(vm.continueToApp()).isTrue()
        assertThat(vm.state.value.phase).isEqualTo(OnboardingPhase.Finished)
    }

    @Test
    fun `a later stage implies earlier stages finished`() = runTest {
        val vm = submittedDriveViewModel()
        emit(OnboardingStage.Catalog, StageStatus.Running)
        assertThat(vm.state.value.stages[OnboardingStage.Playlist]).isEqualTo(StageStatus.Done)
        assertThat(vm.state.value.stages[OnboardingStage.Channels]).isEqualTo(StageStatus.Done)
        assertThat(vm.state.value.canContinue).isTrue()
    }

    @Test
    fun `success result makes channels usable and records the playlist id`() = runTest {
        val vm = submittedDriveViewModel()
        onboarder.result.complete(OnboardingResult.Success(playlistId = 7, xtreamEnabled = true))
        runCurrent()
        assertThat(vm.state.value.canContinue).isTrue()
        assertThat(vm.state.value.playlistId).isEqualTo(7)
        assertThat(vm.state.value.xtreamTurnedOff).isFalse()
    }

    @Test
    fun `success without Xtream turns it off and skips catalog and guide`() = runTest {
        val vm = submittedDriveViewModel()
        onboarder.result.complete(OnboardingResult.Success(playlistId = 3, xtreamEnabled = false))
        runCurrent()
        val s = vm.state.value
        assertThat(s.xtreamTurnedOff).isTrue()
        assertThat(s.stages[OnboardingStage.Catalog]).isEqualTo(StageStatus.Skipped)
        assertThat(s.stages[OnboardingStage.Guide]).isEqualTo(StageStatus.Skipped)
        assertThat(s.allStagesTerminal).isTrue()
        assertThat(s.canContinue).isTrue()
    }

    // --- Errors ---

    @Test
    fun `each failure before channels returns to the form with its error and keeps the input`() = runTest {
        for (error in OnboardingError.entries) {
            onboarder.result = CompletableDeferred()
            val vm = submittedDriveViewModel()
            emit(OnboardingStage.Playlist, StageStatus.Running)
            onboarder.result.complete(OnboardingResult.Failure(error))
            runCurrent()
            val s = vm.state.value
            assertThat(s.phase).isEqualTo(OnboardingPhase.Form)
            assertThat(s.error).isEqualTo(error)
            assertThat(s.driveText).isEqualTo(id)
            assertThat(s.canSubmit).isTrue()
            assertThat(s.stages.values.toSet()).isEqualTo(setOf(StageStatus.Pending))

            // Editing clears the error.
            vm.onDriveTextChanged(id)
            assertThat(vm.state.value.error).isNull()
        }
    }

    @Test
    fun `failure after channels keeps the user in progress with a warning`() = runTest {
        val vm = submittedDriveViewModel()
        emit(OnboardingStage.Channels, StageStatus.Done)
        emit(OnboardingStage.Catalog, StageStatus.Running)
        onboarder.result.complete(OnboardingResult.Failure(OnboardingError.AccountExpired))
        runCurrent()
        val s = vm.state.value
        assertThat(s.phase).isEqualTo(OnboardingPhase.Progress)
        assertThat(s.error).isEqualTo(OnboardingError.AccountExpired)
        assertThat(s.canContinue).isTrue()
        assertThat(s.stages[OnboardingStage.Catalog]).isEqualTo(StageStatus.Failed)
        assertThat(s.stages[OnboardingStage.Guide]).isEqualTo(StageStatus.Failed)
    }

    @Test
    fun `an unexpected exception from the onboarder maps to Other`() = runTest {
        onboarder.throwOnAdd = IllegalStateException("boom")
        val vm = submittedDriveViewModel()
        assertThat(vm.state.value.phase).isEqualTo(OnboardingPhase.Form)
        assertThat(vm.state.value.error).isEqualTo(OnboardingError.Other)
    }

    // --- Back ---

    @Test
    fun `back before channels cancels the add and returns to the form`() = runTest {
        val vm = submittedDriveViewModel()
        emit(OnboardingStage.Playlist, StageStatus.Running)
        assertThat(vm.onBack()).isTrue()
        runCurrent()
        assertThat(onboarder.cancelled).isTrue()
        assertThat(vm.state.value.phase).isEqualTo(OnboardingPhase.Form)
        // Late progress from the cancelled add is ignored.
        emit(OnboardingStage.Channels, StageStatus.Done)
        assertThat(vm.state.value.stages[OnboardingStage.Channels]).isEqualTo(StageStatus.Pending)
    }

    @Test
    fun `back once channels are usable continues into the app`() = runTest {
        val vm = submittedDriveViewModel()
        emit(OnboardingStage.Channels, StageStatus.Done)
        assertThat(vm.onBack()).isTrue()
        assertThat(vm.state.value.phase).isEqualTo(OnboardingPhase.Finished)
    }

    @Test
    fun `back on the form is left to the host`() = runTest {
        assertThat(newViewModel().onBack()).isFalse()
    }

    @Test
    fun `form edits are ignored during progress`() = runTest {
        val vm = submittedDriveViewModel()
        vm.onNameChanged("Other")
        vm.onSourceTypeChanged(SourceType.M3uUrl)
        vm.onXtreamToggled(false)
        assertThat(vm.state.value.name).isEqualTo("My playlist")
        assertThat(vm.state.value.sourceType).isEqualTo(SourceType.DriveId)
        assertThat(vm.state.value.xtreamRequested).isTrue()
    }
}

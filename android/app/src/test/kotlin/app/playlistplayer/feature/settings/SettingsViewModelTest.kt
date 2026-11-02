package app.playlistplayer.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.Version
import app.playlistplayer.core.consolidation.VersionComparator
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.ChannelEpgMapEntity
import app.playlistplayer.core.database.entity.EpgMapMethod
import app.playlistplayer.core.database.entity.EpgMapOrigin
import app.playlistplayer.core.database.entity.LiveStreamInfoEntity
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.ProgrammeSource
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.player.url.LiveFormatPreference
import app.playlistplayer.core.sync.epg.AndroidXmlPullSource
import app.playlistplayer.core.sync.secure.AesGcmValueCipher
import app.playlistplayer.core.sync.secure.SecureStore
import app.playlistplayer.feature.onboarding.OnboardingResult
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.playlists.PlaylistMessage
import app.playlistplayer.feature.playlists.PlaylistTestEnv
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.coroutines.EmptyCoroutineContext

/** Records override saves; the URL itself is only kept in memory. */
class FakeGuideStore : GuideSettingsStore {
    val saved = ArrayList<Pair<Long, String?>>()
    private val urls = HashMap<Long, String>()
    val statuses = HashMap<Long, MutableStateFlow<GuideStatus?>>()

    override suspend fun overrideUrl(playlistId: Long): String? = urls[playlistId]

    override suspend fun setOverride(playlistId: Long, url: String?): Boolean {
        saved += playlistId to url
        if (url.isNullOrBlank()) urls.remove(playlistId) else urls[playlistId] = url
        return true
    }

    override fun observeStatus(playlistId: Long): Flow<GuideStatus?> = statuses.getOrPut(playlistId) { MutableStateFlow(null) }
}

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: PlaylistTestEnv
    private val clock = FakeClock()
    private val broker = PinPromptBroker()
    private val guide = FakeGuideStore()
    private val resets = ArrayList<String>()
    private val extraScopes = ArrayList<CoroutineScope>()
    private lateinit var slots: SecretSlotStore
    private lateinit var parental: ParentalControl
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() = runBlocking {
        env = PlaylistTestEnv(temp.root)
        slots = SecureStoreSecretSlots(env.secureStore)
        parental = loadParental(slots)
        prefs = PreferencesRepository(SecretSlotPreferencesStorage(slots))
    }

    @After
    fun tearDown() {
        extraScopes.forEach { it.cancel() }
        env.close()
    }

    private suspend fun loadParental(store: SecretSlotStore) =
        ParentalControl.load(store, clock, PinHasher(iterations = 1_000), hashContext = EmptyCoroutineContext)

    private fun vm(
        parental: ParentalControl = this.parental,
        groups: ContentGroupSource = StoreContentGroupSource(env.store, env.catalog),
        formats: AllowedFormatsSource = AllowedFormatsSource { id -> env.catalog.accountStatus(id)?.allowedFormatList.orEmpty() },
    ) = SettingsViewModel(
        SettingsDependencies(
            playlists = env.repository(),
            preferences = prefs,
            parental = parental,
            pinPrompts = broker,
            guide = guide,
            overrideTester = XmltvOverrideTester(XmltvDownloader(env.http), AndroidXmlPullSource.Factory, XmltvOverrideTester.channelsFrom(env.catalog)),
            groups = groups,
            allowedFormats = formats,
            resetApp = { resets += "wipe" },
        ),
        externalScope = env.scope,
    )

    private suspend fun SettingsViewModel.await(predicate: (SettingsUiState) -> Boolean): SettingsUiState =
        withTimeout(10_000) { state.first(predicate) }

    private suspend fun drivePlaylist(name: String, fileId: String): Long =
        (env.onboarder().addPlaylist(name, SourceInput.DriveId(fileId), true) as OnboardingResult.Success).playlistId

    private suspend fun SettingsViewModel.awaitPlaylists(count: Int) = await { !it.playlists.loading && it.playlists.playlists.size == count }

    private suspend fun SettingsViewModel.awaitPrompt(match: (Authorization) -> Boolean): PinPromptRequest =
        await { s -> s.pinPrompt?.let { match(it.reason) } == true }.pinPrompt!!

    private suspend fun pinSetAndLocked() {
        assertThat(parental.setPin("1234")).isEqualTo(ParentalResult.Done)
        parental.lock()
    }

    // ---- Content: languages ----

    @Test
    fun reorderingLanguagesToHindiEnglishChangesTheDefaultVersion() = runBlocking<Unit> {
        val vm = vm()
        val versions = listOf(
            Version("en-fhd", language = "en", quality = QualityTier.FHD),
            Version("hi-hd", language = "hi", quality = QualityTier.HD),
        )
        fun defaultVersion(p: AppPreferences) = versions.sortedWith(VersionComparator(p.content.versionPreferences())).first().id

        assertThat(defaultVersion(vm.await { it.preferencesLoaded }.preferences)).isEqualTo("en-fhd")

        vm.addLanguage("hi")
        vm.await { it.preferences.content.languages == listOf("en", "hi") }
        vm.moveLanguageUp(1)
        val reordered = vm.await { it.preferences.content.languages == listOf("hi", "en") }.preferences

        assertThat(defaultVersion(reordered)).isEqualTo("hi-hd")
        // Persisted: a new repository instance sees the order.
        assertThat(PreferencesRepository(SecretSlotPreferencesStorage(slots)).current().content.languages).containsExactly("hi", "en").inOrder()
    }

    @Test
    fun settingsPersistAcrossANewRepositoryInstance() = runBlocking<Unit> {
        val vm = vm()
        vm.await { it.preferencesLoaded }
        vm.setQuality(QualityPreference.HD)
        vm.setMatchFrameRate(true)
        vm.setTunneling(true)
        vm.setCatchupOffsetHours(1)
        vm.setRefreshInterval(24)
        vm.setStaleWarningDays(10)
        vm.await { it.preferences.sync.staleWarningDays == 10 }

        val reopened = PreferencesRepository(SecretSlotPreferencesStorage(SecureStoreSecretSlots(env.secureStore))).current()
        assertThat(reopened.content.quality).isEqualTo(QualityPreference.HD)
        assertThat(reopened.playback.matchFrameRate).isTrue()
        assertThat(reopened.playback.tunneling).isTrue()
        assertThat(reopened.playback.catchupOffsetHours).isEqualTo(1)
        assertThat(reopened.sync).isEqualTo(SyncPreferences(refreshIntervalHours = 24, staleWarningDays = 10))
        // The refresh interval is applied to the periodic work.
        assertThat(env.scheduler.calls).contains("periodic:24")
    }

    @Test
    fun audioAndSubtitleLanguagesAreSavedAndCanBeCleared() = runBlocking<Unit> {
        val vm = vm()
        vm.await { it.preferencesLoaded }
        vm.setAudioLanguage("fr")
        vm.setSubtitleLanguage("en")
        vm.await { it.preferences.playback.subtitleLanguage == "en" }
        val reopened = PreferencesRepository(SecretSlotPreferencesStorage(SecureStoreSecretSlots(env.secureStore))).current()
        assertThat(reopened.playback.audioLanguage).isEqualTo("fr")
        assertThat(reopened.playback.subtitleLanguage).isEqualTo("en")

        vm.setSubtitleLanguage(null)
        vm.await { it.preferences.playback.subtitleLanguage == null }
        vm.setAudioLanguage("not a tag!")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.InvalidLanguage)
        assertThat(prefs.current().playback.audioLanguage).isEqualTo("fr")
    }

    @Test
    fun outOfRangeValuesAreClampedAndReported() = runBlocking<Unit> {
        val vm = vm()
        vm.await { it.preferencesLoaded }
        vm.setRefreshInterval(0)
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.ValueClamped(1))
        assertThat(env.scheduler.calls).contains("periodic:1")
        vm.dismissMessage()
        vm.setCatchupOffsetHours(40)
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.ValueClamped(12))
        assertThat(prefs.current().playback.catchupOffsetHours).isEqualTo(12)
    }

    // ---- Playback ----

    @Test
    fun hlsIsOfferedOnlyWhenAnAccountAllowsIt() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        val vm = vm()
        vm.awaitPlaylists(1)
        vm.setLiveFormat(LiveFormatPreference.HLS)
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.HlsNotAllowed)
        assertThat(prefs.current().playback.liveFormat).isEqualTo(LiveFormatPreference.TS)

        env.catalog.putAccountStatus(AccountStatusEntity(id, "Active", true, null, 1, 0, "m3u8,ts", null, false, env.now))
        vm.dismissMessage()
        // A playlist change re-evaluates the formats.
        env.store.renamePlaylist(id, "Main 2")
        vm.await { it.hlsAllowed }
        vm.setLiveFormat(LiveFormatPreference.HLS)
        vm.await { it.preferences.playback.liveFormat == LiveFormatPreference.HLS }
    }

    // ---- Guide ----

    @Test
    fun testOnAValidOverrideReportsSuccessAndMatchRateWithoutSaving() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        val vm = vm()
        vm.awaitPlaylists(1)
        env.server.enqueue(MockResponse.Builder().code(200).body(guideFor(listOf("ch0.uk", "CH1.UK", "ch2.uk"))).build())

        vm.testOverride(id, env.url("/guide.xml"))

        val done = vm.await { it.guide[id]?.test is OverrideTestState.Done }.guide.getValue(id).test as OverrideTestState.Done
        val result = done.result as OverrideTestResult.Reachable
        assertThat(result.channels).isEqualTo(3)
        assertThat(result.matched).isEqualTo(3)
        assertThat(result.total).isEqualTo(9)
        assertThat(guide.saved).isEmpty()

        // Saving tests again, then stores the URL and queues the import.
        env.server.enqueue(MockResponse.Builder().code(200).body(guideFor(listOf("ch0.uk"))).build())
        vm.saveOverride(id, " " + env.url("/guide.xml") + " ")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.OverrideSaved(id))
        assertThat(guide.saved).containsExactly(id to env.url("/guide.xml"))
    }

    @Test
    fun overrideReturning404ReportsAnErrorAndIsNotSaved() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        val vm = vm()
        vm.awaitPlaylists(1)
        env.server.enqueue(MockResponse.Builder().code(404).body("missing").build())

        vm.saveOverride(id, env.url("/missing.xml"))

        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.OverrideNotSaved(id, OverrideTestResult.HttpError(404)))
        assertThat(guide.saved).isEmpty()
        val ui = vm.state.value.guide.getValue(id)
        assertThat(ui.test).isEqualTo(OverrideTestState.Done(OverrideTestResult.HttpError(404)))
        assertThat(ui.saving).isFalse()
    }

    @Test
    fun guideSectionShowsTheImportMatchRate() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        val vm = vm()
        vm.awaitPlaylists(1)
        guide.statuses.getOrPut(id) { MutableStateFlow(null) }.value =
            GuideStatus(true, "override", env.now, null, 6, 9, 6.0 / 9, 6, 100)
        val status = vm.await { it.guide[id]?.status != null }.guide.getValue(id).status!!
        assertThat(status.matchRate).isWithin(1e-9).of(6.0 / 9)
    }

    // ---- Parental PIN and protected actions ----

    @Test
    fun wrongPinThreeTimesLocksOutAndLockedGroupsStayHidden() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        assertThat(parental.setPin("1234")).isEqualTo(ParentalResult.Done)
        parental.setLockedGroups(setOf(LockedGroup(id, ContentGroupKeys.live("⚽ Sports"))))
        parental.lock()
        val vm = vm()
        vm.awaitPlaylists(1)

        vm.requestRemovePlaylist(id)
        assertThat(vm.state.value.dialog).isEqualTo(SettingsDialog.ConfirmRemovePlaylist(id, "Main"))
        vm.confirmDialog()
        val first = vm.awaitPrompt { it == Authorization.PinRequired }
        assertThat(first.action).isEqualTo(ProtectedAction.RemovePlaylist)

        vm.submitPin("0000")
        vm.awaitPrompt { it == Authorization.Denied(2) }
        vm.submitPin("0000")
        vm.awaitPrompt { it == Authorization.Denied(1) }
        vm.submitPin("0000")
        val locked = vm.awaitPrompt { it is Authorization.LockedOut }
        assertThat((locked.reason as Authorization.LockedOut).retryAtMillis).isEqualTo(clock.now + 30_000)

        // During the lockout even the right PIN is refused.
        vm.submitPin("1234")
        vm.await { s -> s.pinPrompt?.let { it.id != locked.id && it.reason is Authorization.LockedOut } == true }
        assertThat(parental.isGroupHidden(id, ContentGroupKeys.live("⚽ Sports"))).isTrue()
        assertThat(vm.state.value.parental.lockedOutUntil).isNotNull()

        vm.cancelPin()
        assertThat(vm.await { it.playlists.message != null }.playlists.message).isEqualTo(PlaylistMessage.NotAuthorized(id))
        assertThat(env.store.playlist(id)).isNotNull()

        // After the lockout the right PIN works again.
        clock.advance(30_001)
        assertThat(parental.verify("1234")).isEqualTo(Authorization.Allowed)
    }

    @Test
    fun removingAPlaylistRequiresThePinAndDeletesOnlyItsData() = runBlocking<Unit> {
        val keep = drivePlaylist("Keep", "FILE_KEEP")
        val gone = drivePlaylist("Gone", "FILE_GONE")
        seedXtreamData(gone)
        pinSetAndLocked()
        val vm = vm()
        vm.awaitPlaylists(2)

        vm.requestRemovePlaylist(gone)
        vm.confirmDialog()
        vm.awaitPrompt { it == Authorization.PinRequired }
        assertThat(env.store.playlist(gone)).isNotNull()

        vm.submitPin("1234")
        assertThat(vm.await { it.playlists.message != null }.playlists.message).isEqualTo(PlaylistMessage.Removed(gone))
        vm.awaitPlaylists(1)

        assertThat(env.store.playlist(gone)).isNull()
        assertThat(env.catalog.state(gone)).isNull()
        assertThat(env.epg.programmeCount(gone)).isEqualTo(0)
        assertThat(env.secureStore.sourceReference(gone)).isNull()
        assertThat(env.removedHook).containsExactly(gone)
        assertThat(env.store.channels(keep)).hasSize(9)
        assertThat(env.store.playlist(keep)!!.name).isEqualTo("Keep")
    }

    @Test
    fun removalWithoutPinOnlyNeedsTheConfirmation() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        val vm = vm()
        vm.awaitPlaylists(1)
        vm.requestRemovePlaylist(id)
        vm.dismissDialog()
        assertThat(env.store.playlist(id)).isNotNull()

        vm.requestRemovePlaylist(id)
        vm.confirmDialog()
        assertThat(vm.await { it.playlists.message != null }.playlists.message).isEqualTo(PlaylistMessage.Removed(id))
        assertThat(env.store.playlist(id)).isNull()
    }

    @Test
    fun turningXtreamOffRemovesMoviesSeriesGuideAndCatchupButKeepsLive() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        seedXtreamData(id)
        assertThat(env.catalog.channelWithLiveInfo(id, 0)!!.effectiveArchiveDays).isEqualTo(3)
        pinSetAndLocked()
        val vm = vm()
        vm.awaitPlaylists(1)

        vm.setXtreamEnabled(id, false)
        vm.awaitPrompt { it == Authorization.PinRequired }
        vm.submitPin("1234")
        assertThat(vm.await { it.playlists.message != null }.playlists.message).isEqualTo(PlaylistMessage.Saved(id))

        assertThat(env.store.playlist(id)!!.xtreamEnabled).isFalse()
        assertThat(env.catalog.vodCategories(id)).isEmpty()
        assertThat(env.catalog.seriesCategories(id)).isEmpty()
        assertThat(env.catalog.state(id)).isNull()
        assertThat(env.epg.programmeCount(id)).isEqualTo(0)
        assertThat(env.catalog.channelWithLiveInfo(id, 0)!!.effectiveArchiveDays).isEqualTo(0) // no catch-up
        assertThat(env.store.channels(id)).hasSize(9) // live stays
        val summary = vm.await { s -> s.playlists.playlists.singleOrNull()?.xtreamEnabled == false }.playlists.playlists.single()
        assertThat(summary.counts.movies).isEqualTo(0)
        assertThat(summary.counts.series).isEqualTo(0)
        assertThat(summary.account).isNull()
    }

    @Test
    fun pinSetChangeAndRemoveFromSettings() = runBlocking<Unit> {
        val vm = vm()
        vm.await { it.preferencesLoaded }
        vm.setPin("1234", "1243")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.PinMismatch)
        vm.dismissMessage()
        vm.setPin("12", "12")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.PinInvalidFormat)
        vm.dismissMessage()
        vm.setPin("1234", "1234")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.PinSet)
        assertThat(vm.await { it.parental.hasPin }.parental.state).isEqualTo(ParentalState.Unlocked(null))
        vm.dismissMessage()

        vm.changePin("9999", "5678", "5678")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.PinRejected(Authorization.Denied(2)))
        vm.dismissMessage()
        vm.changePin("1234", "5678", "5678")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.PinChanged)
        vm.dismissMessage()
        vm.removePin("5678")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.PinRemoved)
        assertThat(vm.await { !it.parental.hasPin }.parental.state).isEqualTo(ParentalState.NoPin)
    }

    @Test
    fun adultSuggestionsNeedConfirmationAndAPin() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        val groups = ContentGroupSource { playlistId ->
            listOf(
                ContentGroup(playlistId, ContentGroupKind.LIVE, ContentGroupKeys.live("🇬🇧 UK"), "🇬🇧 UK"),
                ContentGroup(playlistId, ContentGroupKind.LIVE, ContentGroupKeys.live("XXX Adults"), "XXX Adults"),
                ContentGroup(playlistId, ContentGroupKind.MOVIES, ContentGroupKeys.movies("66"), "For Adults"),
                ContentGroup(playlistId, ContentGroupKind.LIVE, ContentGroupKeys.live("Adult Swim"), "Adult Swim"),
            )
        }
        val vm = vm(groups = groups)
        val suggested = vm.await { it.suggestions.isNotEmpty() }.suggestions
        assertThat(suggested.map { it.displayName }).containsExactly("XXX Adults", "For Adults")
        // Nothing is hidden until the user confirms.
        assertThat(parental.isGroupLocked(id, ContentGroupKeys.live("XXX Adults"))).isFalse()

        vm.confirmSuggestions()
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.NoPinSet)
        vm.dismissMessage()

        vm.setPin("1234", "1234")
        vm.await { it.message == SettingsMessage.PinSet }
        vm.dismissMessage()
        vm.confirmSuggestions(setOf(LockedGroup(id, ContentGroupKeys.live("XXX Adults"))))
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.GroupsSaved)
        assertThat(parental.isGroupLocked(id, ContentGroupKeys.live("XXX Adults"))).isTrue()
        assertThat(vm.await { it.suggestions.size == 1 }.suggestions.single().displayName).isEqualTo("For Adults")

        vm.dismissSuggestions()
        vm.await { it.suggestions.isEmpty() }
    }

    @Test
    fun groupVisibilityTogglesAskForThePinWhenLocked() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        pinSetAndLocked()
        val vm = vm()
        vm.awaitPlaylists(1)
        vm.openGroupVisibility(id)
        val rows = vm.await { it.groupVisibility?.loading == false }.groupVisibility!!.rows
        assertThat(rows.map { it.group.name }).containsExactly("🇬🇧 UK", "⚽ Sports", "🎬 Movies").inOrder()
        val sports = rows[1].group

        vm.setGroupLocked(sports, true)
        vm.awaitPrompt { it == Authorization.PinRequired }
        vm.submitPin("1234")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.GroupsSaved)
        assertThat(vm.await { s -> s.groupVisibility!!.rows[1].locked }.groupVisibility!!.rows.map { it.locked })
            .containsExactly(false, true, false).inOrder()

        // The PIN unlocked the session: hidden only after locking again.
        assertThat(parental.isGroupHidden(id, sports.key)).isFalse()
        vm.lockNow()
        assertThat(parental.isGroupHidden(id, sports.key)).isTrue()
    }

    // ---- Fail closed and Reset app ----

    @Test
    fun unreadablePinSlotLocksEverythingAndOnlyResetAppUnlocks() = runBlocking<Unit> {
        val id = drivePlaylist("Main", "FILE_A")
        // A parental store whose Keystore key is later replaced (e.g. invalidated by the OS).
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { extraScopes += it }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temp.root, "parental.preferences_pb") })
        val keyA = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keyB = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val before = loadParental(SecureStoreSecretSlots(SecureStore(dataStore, AesGcmValueCipher { keyA })))
        before.setPin("1234")
        before.setLockedGroups(setOf(LockedGroup(id, ContentGroupKeys.live("⚽ Sports"))))

        val damaged = loadParental(SecureStoreSecretSlots(SecureStore(dataStore, AesGcmValueCipher { keyB })))
        assertThat(damaged.currentState()).isEqualTo(ParentalState.ResetRequired)
        val filter = ParentalContentFilter(damaged)
        assertThat(filter.isLiveGroupHidden(id, "🇬🇧 UK")).isTrue() // never locked, still hidden
        assertThat(filter.isLiveGroupHidden(id, "⚽ Sports")).isTrue()

        val vm = vm(parental = damaged)
        assertThat(vm.await { it.parental.resetRequired }.parental.hasPin).isTrue()
        vm.awaitPlaylists(1)

        // Protected actions are refused without a prompt; the right PIN does not help.
        vm.requestRemovePlaylist(id)
        vm.confirmDialog()
        assertThat(vm.await { it.playlists.message != null }.playlists.message).isEqualTo(PlaylistMessage.NotAuthorized(id))
        assertThat(vm.state.value.pinPrompt).isNull()
        assertThat(damaged.verify("1234")).isEqualTo(Authorization.ResetRequired)
        vm.setPin("5678", "5678")
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.ResetRequired)
        vm.dismissMessage()

        vm.requestResetApp()
        assertThat(vm.state.value.dialog).isEqualTo(SettingsDialog.ConfirmResetApp)
        vm.confirmDialog()
        assertThat(vm.await { it.message != null }.message).isEqualTo(SettingsMessage.ResetDone)
        assertThat(resets).containsExactly("wipe")
        assertThat(vm.await { !it.parental.resetRequired }.parental.state).isEqualTo(ParentalState.NoPin)
        assertThat(filter.isLiveGroupHidden(id, "🇬🇧 UK")).isFalse()
    }

    @Test
    fun resetAppRestoresDefaultPreferences() = runBlocking<Unit> {
        val vm = vm()
        vm.await { it.preferencesLoaded }
        vm.setTunneling(true)
        vm.await { it.preferences.playback.tunneling }
        vm.requestResetApp()
        vm.confirmDialog()
        vm.await { it.message == SettingsMessage.ResetDone }
        assertThat(vm.await { !it.preferences.playback.tunneling }.preferences).isEqualTo(AppPreferences())
        assertThat(PreferencesRepository(SecretSlotPreferencesStorage(slots)).current()).isEqualTo(AppPreferences())
    }

    // ---- fixtures ----

    /** XMLTV with the given channel ids and one programme. Synthetic only. */
    private fun guideFor(ids: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
        ids.forEach { append("<channel id=\"$it\"><display-name>$it</display-name></channel>") }
        append("<programme start=\"20261030120000 +0000\" stop=\"20261030130000 +0000\" channel=\"${ids.first()}\"><title>Show</title></programme>")
        append("</tv>")
    }

    /** Movies, series, live archive info (catch-up), an account status and provider programmes. */
    private suspend fun seedXtreamData(id: Long) {
        val generation = env.catalog.beginGeneration(id)
        val writer = env.catalog.writer(id, generation)
        writer.insertLiveInfo(listOf(LiveStreamInfoEntity(id, generation, 10_000, tvArchive = true, archiveDays = 3, epgChannelId = "provider.ch", categoryId = "1")))
        writer.insertVodCategories(listOf(app.playlistplayer.core.database.entity.VodCategoryEntity(id, generation, "1", "Films", null, 0)))
        writer.insertVodStreams(
            listOf(
                VodStreamEntity(
                    playlistId = id, generation = generation, streamId = 1, name = "Film", icon = null, rating = null, added = null,
                    categoryId = "1", containerExtension = "mkv", tmdb = null, isAdult = false, providerOrder = 0,
                ),
            ),
        )
        writer.insertSeriesCategories(listOf(app.playlistplayer.core.database.entity.SeriesCategoryEntity(id, generation, "2", "Shows", null, 0)))
        writer.insertSeries(listOf(SeriesEntity(id, generation, 5, "Show", null, null, null, null, null, "2", null, null, 0)))
        env.catalog.activateGeneration(id, generation) { it.copy(vodCount = 1, seriesCount = 1, lastSuccessAt = env.now) }
        env.catalog.putAccountStatus(AccountStatusEntity(id, "Active", true, 2_000_000_000_000L, 1, 0, "ts", "Europe/Amsterdam", false, env.now))

        val importId = env.epg.beginImport(id)
        val programme = ProgrammeEntity(
            playlistId = id, channelKey = "provider.ch", startMillis = 10_000, stopMillis = 20_000, title = "Provider show", subTitle = null,
            description = null, categories = null, episodeNum = null, source = ProgrammeSource.XMLTV.name,
        )
        env.epg.stage(importId, listOf(programme))
        env.epg.commitImport(
            id, importId, importStart = 0, pruneBefore = 0,
            mappings = listOf(ChannelEpgMapEntity(id, "s:10000", "provider.ch", EpgMapMethod.EXACT_ID.name, EpgMapOrigin.PROVIDER.name)),
        ) { state, _, _, _ -> state.copy(lastImportAt = env.now, matchedCount = 1, totalCount = 9, providerMatchedCount = 1) }
        assertThat(env.epg.programmeCount(id)).isEqualTo(1)
    }
}

package app.playlistplayer.wiring

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.live.LastChannel
import app.playlistplayer.feature.playlists.PlaylistActionResult
import app.playlistplayer.feature.settings.ContentGroupKeys
import app.playlistplayer.feature.settings.LockedGroup
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.feature.vod.FavouriteKind
import app.playlistplayer.feature.vod.FavouriteRef
import app.playlistplayer.feature.vod.PlaybackProgress
import app.playlistplayer.feature.vod.ProgressKey
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.mobile.MobileRoot
import app.playlistplayer.ui.tv.TvRoot
import app.playlistplayer.ui.tv.TvRootTags
import app.playlistplayer.ui.tv.shell.ShellTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests over the real [app.playlistplayer.AppContainer] wiring: in-memory Room,
 * software-key SecureStore, MockWebServer as Drive, fake hosts and credentials only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h720dp-land")
class AppWiringTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private lateinit var env: WiringTestEnv
    private val container get() = env.container

    @Before
    fun setUp() {
        env = WiringTestEnv(temp.newFolder())
        runBlocking { container.initialize() }
    }

    @After
    fun tearDown() {
        container.playback.release()
        env.close()
    }

    private fun liveGroupNames(): List<String> = runBlocking {
        container.liveBrowseSource.sections().first().flatMap { section -> section.groups.map { it.name } }
    }

    private fun liveHitGroups(query: String): List<String> = runBlocking {
        container.searchRepository.search(query).live.flatMap { it.hits }.map { it.groupName }
    }

    /** Waits for [condition]; on timeout prints the semantics trees (all roots, dialogs included). */
    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: ComposeTimeoutException) {
            println(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE))
            throw e
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) =
        await(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForTag(tag: String) = await { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    // tv-material surfaces react to D-pad keys, not touch: invoke their click semantics action.
    private fun clickTag(tag: String) {
        compose.onAllNodes(hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
            .onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun clickText(text: String, substring: Boolean = true) {
        val nodes = compose.onAllNodes(hasClickAction() and hasAnyDescendant(hasText(text, substring = substring)), useUnmergedTree = true)
        nodes[nodes.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
    }

    // Also runs on API 25 (Fire OS 6, the minSdk) to prove start-up and TV wiring there.
    @Test
    @Config(sdk = [25, 36])
    fun `onboarding a Drive playlist lands on Home with its channels listed`() {
        val dependencies = container.uiDependencies()
        compose.setContent { TvRoot(dependencies.tv) }
        compose.waitForIdle()
        // First run: no shell yet.
        assertThat(compose.onAllNodesWithText("Exit app?").fetchSemanticsNodes()).isEmpty()
        assertThat(runBlocking { container.hasPlaylists().first() }).isFalse()

        val id = runBlocking { env.onboard(name = "Home playlist") }

        // The shell replaces onboarding and Home lists the playlist's live groups.
        waitForTag(ShellTags.RAIL)
        waitForText("Home playlist")
        waitForText("🇬🇧 UK")
        val sections = runBlocking { container.liveBrowseSource.sections().first() }
        assertThat(sections.map { it.name }).containsExactly("Home playlist")
        assertThat(sections.single().groups.map { it.name }).containsExactly("🇬🇧 UK", "⚽ Sports", "🎬 Movies").inOrder()
        val channels = runBlocking { container.liveBrowseSource.channels(GroupKey(id, 0)).first() }
        assertThat(channels.map { it.name }).containsExactly("Channel 0", "Channel 1", "Channel 2").inOrder()
        assertThat(channels.map { it.number }).containsExactly(1, 2, 3).inOrder()
        // The Drive API was asked for metadata, then the media.
        assertThat(env.server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a locked group disappears from Live, Search, Guide, other versions and VOD`() {
        val id = runBlocking { env.onboard() }
        runBlocking { container.searchIndexer.ensureAllFresh() }
        assertThat(liveGroupNames()).contains("⚽ Sports")
        assertThat(liveHitGroups("Channel")).contains("⚽ Sports")

        val parental = container.parentalControl
        runBlocking {
            parental.setPin("1357")
            parental.setLockedGroups(setOf(LockedGroup(id, ContentGroupKeys.live("⚽ Sports")), LockedGroup(id, ContentGroupKeys.movies("12"))))
            parental.lock()
        }

        // Live browse (also the Guide's and Home's group source).
        assertThat(liveGroupNames()).containsExactly("🇬🇧 UK", "🎬 Movies").inOrder()
        assertThat(runBlocking { container.liveBrowseSource.channels(GroupKey(id, 1)).first() }).isEmpty()
        // Search.
        assertThat(liveHitGroups("Channel")).doesNotContain("⚽ Sports")
        assertThat(liveHitGroups("Channel")).isNotEmpty()
        // Other versions never come from a hidden group.
        assertThat(runBlocking { container.liveChannelSource.playlistChannels(id) }.map { it.groupOrdinal }.distinct()).containsExactly(0, 2)
        // VOD categories.
        assertThat(container.contentVisibility.isVodCategoryHidden(id, VodKind.MOVIE, "12")).isTrue()
        assertThat(container.contentVisibility.isVodCategoryHidden(id, VodKind.MOVIE, "1")).isFalse()

        // Guide (TV): the group picker offers only visible groups.
        val dependencies = container.uiDependencies()
        compose.setContent { TvRoot(dependencies.tv) }
        waitForTag(ShellTags.railItem(Destination.Guide))
        clickTag(ShellTags.railItem(Destination.Guide))
        waitForTag(TvRootTags.GUIDE_GROUP)
        clickText("Change group")
        waitForText("Choose a group")
        compose.onAllNodesWithText("⚽ Sports", substring = true).assertCountEquals(0)
        assertThat(compose.onAllNodesWithText("🎬 Movies", substring = true).fetchSemanticsNodes()).isNotEmpty()

        // Unlocking shows it again.
        runBlocking { parental.verify("1357") }
        assertThat(liveGroupNames()).contains("⚽ Sports")
    }

    @Test
    fun `removing a playlist asks for the PIN and clears its favourites, progress and search rows`() {
        val id = runBlocking { env.onboard() }
        runBlocking {
            container.favouritesStore.setFavourite(FavouriteRef(FavouriteKind.CHANNEL, id, "s:10000"), true)
            container.playbackProgressStore.put(
                PlaybackProgress(ProgressKey("$id|movie|x"), id, VodKind.MOVIE, "X", "movie:1", 60_000, 600_000, updatedAt = 1),
            )
            container.titlePickStore.setPick("$id|movie|x", "1")
            container.lastChannelStore.setLastChannel(id, LastChannel(ordinal = 0, groupOrdinal = 0))
            container.searchIndexer.ensureAllFresh()
        }
        assertThat(runBlocking { container.userDataStore.rowCount(id) }).isGreaterThan(0)
        assertThat(liveHitGroups("Channel")).isNotEmpty()

        val parental = container.parentalControl
        runBlocking {
            parental.setPin("2468")
            parental.setLockedGroups(setOf(LockedGroup(id, ContentGroupKeys.live("⚽ Sports"))))
            parental.lock()
        }

        val result = runBlocking {
            val removal = async(Dispatchers.Default) { container.playlistRepository.remove(id) }
            // The global PIN prompt (rendered by the roots) is the broker's request.
            val request = withTimeout(10_000) { container.pinPromptBroker.request.filterNotNull().first() }
            container.pinPromptBroker.submit(request.id, "2468")
            withTimeout(10_000) { removal.await() }
        }

        assertThat(result).isEqualTo(PlaylistActionResult.Done)
        assertThat(runBlocking { container.userDataStore.rowCount(id) }).isEqualTo(0)
        assertThat(runBlocking { container.titlePickStore.pick("$id|movie|x") }).isNull()
        assertThat(liveHitGroups("Channel")).isEmpty()
        assertThat(liveGroupNames()).isEmpty()
        // ParentalControl.forgetPlaylist ran through the removal hook.
        assertThat(parental.lockedGroups.value.filter { it.playlistId == id }).isEmpty()
        assertThat(env.scheduler.calls).contains("cancelAll:$id")
    }

    @Test
    fun `TV Movies to detail to play builds the fake-host movie source`() {
        val id = runBlocking {
            env.onboard(xtream = true).also { env.syncCatalog(it) }
        }
        val dependencies = container.uiDependencies()
        compose.setContent { TvRoot(dependencies.tv) }
        waitForTag(ShellTags.railItem(Destination.Movies))
        clickTag(ShellTags.railItem(Destination.Movies))

        waitForText("Test Movie")
        clickText("Test Movie")
        waitForText("Play")
        clickText("Play", substring = false)

        val controller = container.playback.controller()
        await { controller.state.value.source != null }
        val source = controller.state.value.source!!
        assertThat(source.id).isEqualTo("movie:${WiringTestEnv.MOVIE_STREAM_ID}")
        assertThat(source.url).startsWith("http://${WiringTestEnv.FAKE_HOST}/movie/user/pass/${WiringTestEnv.MOVIE_STREAM_ID}.")
        assertThat(source.accountKey).isEqualTo(id.toString())
    }

    // Also runs on API 25 (Android 7.1, the minSdk) to prove phone wiring there.
    @Test
    @Config(qualifiers = "w411dp-h891dp-port", sdk = [25, 36])
    fun `phone root composes with full dependencies`() {
        runBlocking { env.onboard(name = "Phone playlist") }
        val dependencies = container.uiDependencies()
        assertThat(dependencies.mobile.vod).isNotNull()
        assertThat(dependencies.mobile.playback).isNotNull()
        assertThat(dependencies.mobile.guide).isNotNull()
        assertThat(dependencies.mobile.shared.screens).isNotNull()
        compose.setContent { MobileRoot(dependencies.mobile) }
        waitForText("Phone playlist")
        waitForText("🇬🇧 UK")
    }

    @Test
    fun `reset app wipes playlists and secrets and returns to first run`() {
        val id = runBlocking { env.onboard() }
        runBlocking { container.parentalControl.setPin("9753") }
        runBlocking { container.resetEverything() }
        assertThat(runBlocking { container.hasPlaylists().first() }).isFalse()
        assertThat(runBlocking { container.secureStore.account(id) }).isNull()
        assertThat(container.parentalControl.hasPin).isFalse()
        assertThat(env.cancelledAllWork).isEqualTo(1)
    }
}

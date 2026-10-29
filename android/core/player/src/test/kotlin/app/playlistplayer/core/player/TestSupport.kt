package app.playlistplayer.core.player

import app.playlistplayer.core.consolidation.QualityTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope

internal fun live(id: String, tier: QualityTier = QualityTier.FHD, label: String = "Channel $id") =
    PlayableSource(id = id, url = "http://example.invalid/live/u/p/$id.ts", label = label, isLive = true, versionTier = tier)

internal fun vod(id: String) =
    PlayableSource(id = id, url = "http://example.invalid/movie/u/p/$id.mkv", label = "Movie $id", isLive = false)

/** Records driver calls in order: "prepare:<id>@<pos|edge>" and "release". */
internal class FakeDriver : PlaybackDriver {
    val calls = mutableListOf<String>()
    val prepared = mutableListOf<Pair<PlayableSource, Long?>>()

    override fun prepare(source: PlayableSource, startPositionMs: Long?) {
        calls += "prepare:${source.id}@${startPositionMs ?: "edge"}"
        prepared += source to startPositionMs
    }

    override fun release() {
        calls += "release"
    }

    fun preparedIds(): List<String> = prepared.map { it.first.id }
    fun prepareCount(id: String): Int = prepared.count { it.first.id == id }
}

internal class FakeConnectivity(initial: Boolean = true) : ConnectivityMonitor {
    val flow = MutableStateFlow(initial)
    override val isConnected: StateFlow<Boolean> get() = flow
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class MachineFixture(
    scope: TestScope,
    val policy: PlaybackPolicy = PlaybackPolicy(),
    checker: AccountStatusChecker = AccountStatusChecker.UNKNOWN,
    val connectivity: FakeConnectivity = FakeConnectivity(),
) {
    val driver = FakeDriver()
    val history = mutableListOf<PlaybackState>()
    val machine = PlaybackStateMachine(
        scope = scope.backgroundScope,
        driver = driver,
        accountStatusChecker = checker,
        connectivity = connectivity,
        policy = policy,
        clock = { scope.testScheduler.currentTime },
        onTransition = { history += it },
    )
    val state: PlaybackState get() = machine.state.value
}

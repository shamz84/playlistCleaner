package app.playlistplayer.core.player

import app.playlistplayer.core.consolidation.QualityTier
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateMachineTest {

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val networkError = PlaybackError(PlaybackError.Kind.NETWORK)

    // ---- Happy path -------------------------------------------------------------------

    @Test
    fun selectedFhdEntryPlays_evenWhenA4kOtherVersionExists() = runTest {
        val f = MachineFixture(this)
        val fhd = live("fhd", QualityTier.FHD)
        val uhd = live("uhd", QualityTier.UHD)

        f.machine.play(listOf(fhd, uhd))
        f.machine.onFirstFrame()

        assertThat(f.driver.preparedIds()).containsExactly("fhd")
        assertThat(f.state).isEqualTo(PlaybackState.Playing(fhd))
    }

    @Test
    fun liveIsPreparedAtTheLiveEdge() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a")), startPositionMs = 5_000)
        assertThat(f.driver.calls.last()).isEqualTo("prepare:a@edge")
    }

    // ---- Retry budget and failover ----------------------------------------------------

    @Test
    fun retriesExhausted_releasesAndPreparesTopOtherVersion_thenNext_thenFailed() = runTest {
        val f = MachineFixture(this)
        val a = live("a")
        val b = live("b")
        val c = live("c")
        f.machine.play(listOf(a, b, c))

        fun exhaust(source: PlayableSource) {
            repeat(f.policy.preFirstFrameMaxAttempts) { attempt ->
                assertThat(f.state).isInstanceOf(PlaybackState.Preparing::class.java)
                assertThat(f.machine.currentSource).isEqualTo(source)
                f.machine.onError(networkError)
                if (attempt < f.policy.preFirstFrameMaxAttempts - 1) advance(f.policy.preFirstFrameRetryDelayMs)
            }
        }

        exhaust(a)
        assertThat(f.driver.calls.takeLast(2)).containsExactly("release", "prepare:b@edge").inOrder()
        exhaust(b)
        assertThat(f.driver.calls.takeLast(2)).containsExactly("release", "prepare:c@edge").inOrder()
        exhaust(c)

        val failed = f.state as PlaybackState.Failed
        assertThat(failed.source).isEqualTo(c)
        assertThat(failed.triedVersions).isEqualTo(3)
        assertThat(f.driver.prepareCount("a")).isEqualTo(3)
        assertThat(f.driver.prepareCount("b")).isEqualTo(3)
        assertThat(f.driver.prepareCount("c")).isEqualTo(3)
        assertThat(f.history.filterIsInstance<PlaybackState.Failover>().map { it.to.id }).containsExactly("b", "c").inOrder()
    }

    @Test
    fun preFirstFrameBudget_watchdogTimeoutsCount_andFailoverHappensWithinBound() = runTest {
        val f = MachineFixture(this)
        val a = live("a")
        val b = live("b")
        f.machine.play(listOf(a, b))

        // The stream never renders and never errors: only the watchdog notices.
        val bound = f.policy.preFirstFrameMaxElapsedMs + f.policy.startTimeoutMs + f.policy.preFirstFrameRetryDelayMs
        var failoverAt = -1L
        while (currentTime <= bound + 1_000 && failoverAt < 0) {
            advance(100)
            if (f.driver.prepareCount("b") > 0) failoverAt = currentTime
        }

        assertThat(failoverAt).isAtLeast(0L)
        assertThat(failoverAt).isAtMost(bound)
        assertThat(f.driver.prepareCount("a")).isEqualTo(2) // watchdog re-prepare counted against the budget
        assertThat(f.history.filterIsInstance<PlaybackState.Retrying>().map { it.reason.kind })
            .contains(PlaybackError.Kind.STALL)
    }

    @Test
    fun http405_isAStreamError_failsOverImmediately_releasingFirst() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))

        f.machine.onError(PlaybackError.fromHttpStatus(405))

        assertThat(f.driver.preparedIds()).containsExactly("a", "b").inOrder()
        assertThat(f.driver.calls.takeLast(2)).containsExactly("release", "prepare:b@edge").inOrder()
        assertThat(f.machine.currentSource?.id).isEqualTo("b")
    }

    @Test
    fun http404_failsOver() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onFirstFrame()
        f.machine.onError(PlaybackError.fromHttpStatus(404))
        assertThat(f.driver.preparedIds()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun atMostFiveFailovers_thenFailed() = runTest {
        val f = MachineFixture(this)
        val sources = (0..6).map { live("s$it") }
        f.machine.play(sources)

        repeat(10) {
            if (f.state is PlaybackState.Preparing) f.machine.onError(PlaybackError.fromHttpStatus(404))
        }

        assertThat(f.driver.preparedIds()).containsExactly("s0", "s1", "s2", "s3", "s4", "s5").inOrder()
        val failed = f.state as PlaybackState.Failed
        assertThat(failed.triedVersions).isEqualTo(6)
        assertThat(f.history.filterIsInstance<PlaybackState.Failover>()).hasSize(5)
    }

    @Test
    fun noOtherVersions_failsDirectly() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a")))
        f.machine.onError(PlaybackError.fromHttpStatus(405))
        assertThat(f.state).isInstanceOf(PlaybackState.Failed::class.java)
        assertThat(f.driver.calls.last()).isEqualTo("release")
    }

    @Test
    fun preferredVersionIsTriedFirstAgainOnNextZap() = runTest {
        val f = MachineFixture(this)
        val sources = listOf(live("a"), live("b"))
        f.machine.play(sources)
        f.machine.onError(PlaybackError.fromHttpStatus(404))
        f.machine.onFirstFrame()
        assertThat(f.machine.currentSource?.id).isEqualTo("b")

        f.machine.play(sources)

        assertThat(f.driver.preparedIds().last()).isEqualTo("a")
    }

    @Test
    fun failover_showsSwitchedNoticeOnFirstFrame_thenClearsIt() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b", label = "BBC One HD")))
        f.machine.onError(PlaybackError.fromHttpStatus(405))
        f.machine.onFirstFrame()

        assertThat((f.state as PlaybackState.Playing).notice).isEqualTo("BBC One HD")
        assertThat(PlayerUiState.from(f.state).notice).isEqualTo("Switched to BBC One HD")

        advance(f.policy.failoverNoticeMs)
        assertThat((f.state as PlaybackState.Playing).notice).isNull()
    }

    // ---- After the first frame ---------------------------------------------------------

    @Test
    fun postFirstFrame_networkErrorsKeepRetryingWhileConnected_neverFailOver() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onFirstFrame()

        repeat(25) {
            f.machine.onError(networkError)
            assertThat(f.state).isInstanceOf(PlaybackState.Retrying::class.java)
            advance(f.policy.reconnectBackoffMaxMs)
            assertThat(f.state).isInstanceOf(PlaybackState.Preparing::class.java)
        }

        assertThat(f.driver.prepareCount("a")).isEqualTo(26)
        assertThat(f.driver.prepareCount("b")).isEqualTo(0)
        assertThat(f.driver.prepared.all { it.second == null }).isTrue()
    }

    @Test
    fun postFirstFrame_backoffIsCappedAndGrows() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a")))
        f.machine.onFirstFrame()

        val delays = (1..7).map {
            f.machine.onError(networkError)
            val retrying = f.state as PlaybackState.Retrying
            advance(retrying.delayMs)
            retrying.delayMs
        }

        assertThat(delays).containsExactly(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L).inOrder()
    }

    @Test
    fun postFirstFrame_retriesPauseWhileOffline_andResumeOnReconnect() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onFirstFrame()

        f.connectivity.flow.value = false
        f.machine.onError(networkError)
        assertThat((f.state as PlaybackState.Retrying).waitingForNetwork).isTrue()
        assertThat(PlayerUiState.from(f.state).indicator).isEqualTo(PlayerUiState.Indicator.WAITING_FOR_NETWORK)

        advance(120_000)
        assertThat(f.driver.prepareCount("a")).isEqualTo(1)

        f.connectivity.flow.value = true
        runCurrent()
        assertThat(f.driver.prepareCount("a")).isEqualTo(2)
        assertThat(f.driver.prepareCount("b")).isEqualTo(0)
    }

    @Test
    fun preFirstFrame_offlineTimeDoesNotConsumeBudget() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.connectivity.flow.value = false
        f.machine.onError(networkError)
        advance(60_000)
        f.connectivity.flow.value = true
        runCurrent()

        assertThat(f.driver.prepareCount("a")).isEqualTo(2)
        assertThat(f.driver.prepareCount("b")).isEqualTo(0)
        assertThat(f.state).isInstanceOf(PlaybackState.Preparing::class.java)
    }

    @Test
    fun liveStateEnded_isADrop_retriesAtLiveEdge() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onFirstFrame()

        f.machine.onEnded()
        assertThat((f.state as PlaybackState.Retrying).reason.kind).isEqualTo(PlaybackError.Kind.STREAM_ENDED)
        advance(f.policy.reconnectBackoffInitialMs)

        assertThat(f.driver.calls.last()).isEqualTo("prepare:a@edge")
        assertThat(f.driver.prepareCount("b")).isEqualTo(0)
    }

    @Test
    fun vodEnded_isEnded_noRetry() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(vod("m")))
        f.machine.onFirstFrame()
        f.machine.onEnded()
        advance(30_000)
        assertThat(f.state).isEqualTo(PlaybackState.Ended(vod("m")))
        assertThat(f.driver.prepareCount("m")).isEqualTo(1)
    }

    @Test
    fun vodRetry_resumesAtLastPosition() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(vod("m")), startPositionMs = 10_000)
        assertThat(f.driver.calls.last()).isEqualTo("prepare:m@10000")
        f.machine.onFirstFrame()
        f.machine.onProgress(42_000)
        f.machine.onError(networkError)
        advance(f.policy.reconnectBackoffInitialMs)
        assertThat(f.driver.calls.last()).isEqualTo("prepare:m@42000")
    }

    @Test
    fun stall_triggersExactlyOneReprepareAtLiveEdge_notOverlapping() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a")))
        f.machine.onFirstFrame()

        // Healthy progress keeps the watchdog quiet.
        repeat(20) {
            advance(500)
            f.machine.onProgress(currentTime)
        }
        assertThat(f.driver.prepareCount("a")).isEqualTo(1)

        // Progress stops: one stall, one re-prepare.
        advance(f.policy.stallThresholdMs)
        assertThat(f.history.filterIsInstance<PlaybackState.Stalled>()).hasSize(1)
        advance(f.policy.reconnectBackoffInitialMs)
        assertThat(f.driver.prepareCount("a")).isEqualTo(2)
        assertThat(f.driver.prepared.last().second).isNull()

        // Still no progress, but within the start timeout of the re-prepare: nothing more.
        advance(f.policy.startTimeoutMs - 1)
        assertThat(f.driver.prepareCount("a")).isEqualTo(2)
        assertThat(f.history.filterIsInstance<PlaybackState.Stalled>()).hasSize(1)
    }

    @Test
    fun pausedVod_doesNotTriggerStall() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(vod("m")))
        f.machine.onFirstFrame()
        f.machine.onPausedChanged(true)
        advance(60_000)
        assertThat(f.state).isInstanceOf(PlaybackState.Playing::class.java)
        assertThat(f.driver.prepareCount("m")).isEqualTo(1)
    }

    @Test
    fun behindLiveWindow_reprepares_withoutConsumingBudget() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        repeat(5) {
            f.machine.onError(PlaybackError(PlaybackError.Kind.BEHIND_LIVE_WINDOW))
            runCurrent()
        }
        assertThat(f.driver.prepareCount("a")).isEqualTo(6)
        assertThat(f.driver.prepareCount("b")).isEqualTo(0)
    }

    @Test
    fun mediaErrorBeforeFirstFrame_failsOver() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onError(PlaybackError(PlaybackError.Kind.MEDIA))
        assertThat(f.driver.preparedIds()).containsExactly("a", "b").inOrder()
    }

    // ---- Account classification (401/403/509) ------------------------------------------

    @Test
    fun http403_withActiveBelowMax_isStreamError_failsOver() = runTest {
        val f = MachineFixture(this, checker = { AccountStatus.Active(activeConnections = 0, maxConnections = 1) })
        f.machine.play(listOf(live("a"), live("b")))

        f.machine.onError(PlaybackError.fromHttpStatus(403))
        assertThat((f.state as PlaybackState.Retrying).checkingAccount).isTrue()
        runCurrent()

        assertThat(f.driver.preparedIds()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun http403_atConnectionLimit_isConnectionLimit_noFailover() = runTest {
        val f = MachineFixture(this, checker = { AccountStatus.Active(activeConnections = 1, maxConnections = 1) })
        f.machine.play(listOf(live("a"), live("b")))

        f.machine.onError(PlaybackError.fromHttpStatus(403))
        runCurrent()
        advance(30_000)

        assertThat(f.state).isEqualTo(PlaybackState.ConnectionLimit(live("a"), activeConnections = 1, maxConnections = 1))
        assertThat(f.driver.preparedIds()).containsExactly("a")
        val ui = PlayerUiState.from(f.state)
        assertThat(ui.error?.type).isEqualTo(PlayerUiState.ErrorType.CONNECTION_LIMIT)
        assertThat(ui.error?.activeConnections).isEqualTo(1)
        assertThat(ui.error?.maxConnections).isEqualTo(1)
        assertThat(ui.error?.canRetry).isTrue()
    }

    @Test
    fun http509_expiredAccount_isAccountExpired() = runTest {
        val f = MachineFixture(this, checker = { AccountStatus.Expired })
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onError(PlaybackError.fromHttpStatus(509))
        runCurrent()
        assertThat(f.state).isEqualTo(PlaybackState.AccountExpired(live("a")))
        assertThat(f.driver.preparedIds()).containsExactly("a")
    }

    @Test
    fun http401_unknownAccountStatus_failsOver() = runTest {
        val f = MachineFixture(this, checker = { throw IllegalStateException("api down") })
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onError(PlaybackError.fromHttpStatus(401))
        runCurrent()
        assertThat(f.driver.preparedIds()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun connectionLimitRightAfterAppInitiatedRelease_retriesBounded_thenConnectionLimit() = runTest {
        val f = MachineFixture(this, checker = { AccountStatus.Active(activeConnections = 1, maxConnections = 1) })
        f.machine.play(listOf(live("old")))
        f.machine.onFirstFrame()
        advance(10_000)

        // Zap: the app releases "old" and opens "x"; the provider still counts the old socket.
        f.machine.play(listOf(live("x"), live("y")))
        repeat(f.policy.connectionLimitMaxRetries + 1) {
            assertThat(f.state).isInstanceOf(PlaybackState.Preparing::class.java)
            f.machine.onError(PlaybackError.fromHttpStatus(403))
            runCurrent()
            if (f.state is PlaybackState.Retrying) advance(f.policy.connectionLimitRetryDelayMs)
        }

        assertThat(f.state).isInstanceOf(PlaybackState.ConnectionLimit::class.java)
        assertThat(f.driver.prepareCount("x")).isEqualTo(1 + f.policy.connectionLimitMaxRetries)
        assertThat(f.driver.prepareCount("y")).isEqualTo(0)
    }

    @Test
    fun connectionLimitRightAfterRelease_recoversWhenOldConnectionClears() = runTest {
        var active = 1
        val f = MachineFixture(this, checker = { AccountStatus.Active(activeConnections = active, maxConnections = 1) })
        f.machine.play(listOf(live("old")))
        f.machine.onFirstFrame()
        f.machine.play(listOf(live("x")))

        f.machine.onError(PlaybackError.fromHttpStatus(403))
        runCurrent()
        active = 0
        advance(f.policy.connectionLimitRetryDelayMs)
        f.machine.onFirstFrame()

        assertThat(f.state).isEqualTo(PlaybackState.Playing(live("x")))
    }

    // ---- Zapping -------------------------------------------------------------------------

    @Test
    fun zapDuringPendingRetry_cancelsTheRetry() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a")))
        f.machine.onFirstFrame()
        f.machine.onError(networkError)
        assertThat(f.state).isInstanceOf(PlaybackState.Retrying::class.java)

        f.machine.play(listOf(live("b")))
        f.machine.onFirstFrame()
        repeat(30) {
            advance(1_000)
            f.machine.onProgress(currentTime)
        }

        assertThat(f.driver.preparedIds()).containsExactly("a", "b").inOrder()
        assertThat(f.state).isEqualTo(PlaybackState.Playing(live("b")))
    }

    @Test
    fun zap_cancelsTheOldChannelsWatchdog() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a")))
        f.machine.onFirstFrame()
        advance(f.policy.stallThresholdMs - 1_000)

        f.machine.play(listOf(live("b")))
        f.machine.onFirstFrame()
        repeat(20) {
            advance(1_000)
            f.machine.onProgress(currentTime)
        }

        assertThat(f.history.filterIsInstance<PlaybackState.Stalled>()).isEmpty()
        assertThat(f.driver.preparedIds()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun zapDuringAccountCheck_ignoresTheStaleResult() = runTest {
        val f = MachineFixture(this, checker = {
            kotlinx.coroutines.delay(2_000)
            AccountStatus.Expired
        })
        f.machine.play(listOf(live("a")))
        f.machine.onError(PlaybackError.fromHttpStatus(403))
        runCurrent()
        f.machine.play(listOf(live("b")))
        f.machine.onFirstFrame()
        advance(5_000)
        assertThat(f.state).isEqualTo(PlaybackState.Playing(live("b")))
    }

    @Test
    fun userRetry_fromFailed_restartsFromPreferredWithFreshBudget() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.onError(PlaybackError.fromHttpStatus(404))
        f.machine.onError(PlaybackError.fromHttpStatus(404))
        assertThat(f.state).isInstanceOf(PlaybackState.Failed::class.java)

        f.machine.retry()

        assertThat(f.driver.preparedIds()).containsExactly("a", "b", "a").inOrder()
        assertThat(f.state).isInstanceOf(PlaybackState.Preparing::class.java)
    }

    @Test
    fun stop_releasesAndIgnoresLateEvents() = runTest {
        val f = MachineFixture(this)
        f.machine.play(listOf(live("a"), live("b")))
        f.machine.stop()
        f.machine.onError(PlaybackError.fromHttpStatus(404))
        f.machine.onFirstFrame()
        assertThat(f.state).isEqualTo(PlaybackState.Idle)
        assertThat(f.driver.calls.last()).isEqualTo("release")
        assertThat(f.driver.preparedIds()).containsExactly("a")
    }
}

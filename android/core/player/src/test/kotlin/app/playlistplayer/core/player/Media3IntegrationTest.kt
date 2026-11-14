package app.playlistplayer.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/** Media3-backed pieces that need Android classes (Uri, SystemClock), run under Robolectric. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Media3IntegrationTest {

    private fun httpError(status: Int): PlaybackException {
        val cause = HttpDataSource.InvalidResponseCodeException(
            status, "status", null, emptyMap(), DataSpec(Uri.parse("http://example.invalid/live/u/p/1.ts")), ByteArray(0),
        )
        return PlaybackException("load failed", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    @Test
    fun classifier_readsHttpStatusFromCauseChain() {
        assertThat(PlaybackErrorClassifier.classify(httpError(403))).isEqualTo(PlaybackError(PlaybackError.Kind.HTTP_AUTH, 403))
        assertThat(PlaybackErrorClassifier.classify(httpError(405)).kind).isEqualTo(PlaybackError.Kind.HTTP_NOT_FOUND)
        assertThat(PlaybackErrorClassifier.classify(httpError(502)).kind).isEqualTo(PlaybackError.Kind.NETWORK)
    }

    @Test
    fun classifier_fallsBackToErrorCode() {
        val error = PlaybackException("io", IOException("reset"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertThat(PlaybackErrorClassifier.classify(error).kind).isEqualTo(PlaybackError.Kind.NETWORK)
    }

    @Test
    fun loadErrorPolicy_classifiesInvalidResponseCode() {
        val exception = httpError(509).cause as IOException
        assertThat(LiveErrorPolicy.classify(exception)).isEqualTo(LoadFailure.Http(509))
    }

    @Test
    fun profileLoadControl_liveStartsAtLowThreshold_vodNeedsMore() {
        val control = ProfileLoadControl(LoadControlTuning().resolve(DeviceMemory(false, 256)))
        control.onPrepared(PlayerId.UNSET)
        val timeline = FakeTimeline()
        val periodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))

        fun params(bufferedMs: Long) = LoadControl.Parameters(
            PlayerId.UNSET,
            timeline,
            periodId,
            /* playbackPositionUs = */ 0,
            /* bufferedDurationUs = */ bufferedMs * 1_000,
            /* playbackSpeed = */ 1f,
            /* playWhenReady = */ true,
            /* rebuffering = */ false,
            /* targetLiveOffsetUs = */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs = */ C.TIME_UNSET,
        )

        control.mode = PlaybackMode.LIVE
        assertThat(control.shouldStartPlayback(params(1_300))).isTrue()
        assertThat(control.shouldStartPlayback(params(800))).isFalse()

        control.mode = PlaybackMode.VOD
        assertThat(control.shouldStartPlayback(params(1_300))).isFalse()
        assertThat(control.shouldStartPlayback(params(2_600))).isTrue()

        assertThat(control.shouldContinuePreloading(PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId(Any()), 0))
            .isFalse()
        control.onReleased(PlayerId.UNSET)
    }

    @Test
    fun profileLoadControl_stopsLoadingAtTheActualAllocatorByteLimit() {
        val profile = LoadControlProfile.VOD.copy(targetBufferBytes = 2 * LoadControlProfile.MB)
        val control = ProfileLoadControl(ResolvedLoadControlProfiles(LoadControlProfile.LIVE, profile))
        control.mode = PlaybackMode.VOD
        control.onPrepared(PlayerId.UNSET)
        val timeline = FakeTimeline()
        val parameters = LoadControl.Parameters(
            PlayerId.UNSET, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0, 1_000_000, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET,
        )
        val allocator = control.getAllocator(PlayerId.UNSET)
        val allocations = mutableListOf<androidx.media3.exoplayer.upstream.Allocation>()
        assertThat(control.shouldContinueLoading(parameters)).isTrue()
        while (control.allocatedBufferBytes < profile.targetBufferBytes) allocations += allocator.allocate()
        assertThat(control.shouldContinueLoading(parameters)).isFalse()
        assertThat(control.shouldStartPlayback(parameters)).isTrue()
        allocations.forEach(allocator::release)
        assertThat(control.shouldContinueLoading(parameters)).isTrue()
        control.onReleased(PlayerId.UNSET)
    }
}

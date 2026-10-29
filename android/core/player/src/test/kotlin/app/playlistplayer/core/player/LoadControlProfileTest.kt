package app.playlistplayer.core.player

import app.playlistplayer.core.player.LoadControlProfile.Companion.MB
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LoadControlProfileTest {

    private val tuning = LoadControlTuning()

    @Test
    fun liveProfile_startsFast_andIsBounded() {
        val live = LoadControlProfile.LIVE
        assertThat(live.bufferForPlaybackMs).isIn(1_000..1_500)
        assertThat(live.bufferForPlaybackAfterRebufferMs).isEqualTo(2_000)
        assertThat(live.maxBufferMs).isAtMost(20_000)
        assertThat(live.backBufferMs).isEqualTo(0)
        assertThat(LoadControlProfile.VOD.maxBufferMs).isGreaterThan(live.maxBufferMs)
    }

    @Test
    fun highMemoryDevice_keepsProfileBytesWhenUnderHeapCap() {
        val resolved = tuning.resolve(DeviceMemory(isLowRamDevice = false, memoryClassMb = 256))
        assertThat(resolved.live.targetBufferBytes).isEqualTo(48 * MB) // 25% of 256 MB = 64 MB > 48 MB
        assertThat(resolved.vod.targetBufferBytes).isEqualTo(64 * MB) // capped from 96 MB
        assertThat(resolved.live.maxBufferMs).isEqualTo(LoadControlProfile.LIVE.maxBufferMs)
        assertThat(resolved.vod.backBufferMs).isEqualTo(LoadControlProfile.VOD.backBufferMs)
    }

    @Test
    fun smallerMemoryClass_scalesByteCapDown() {
        val resolved = tuning.resolve(DeviceMemory(isLowRamDevice = false, memoryClassMb = 128))
        assertThat(resolved.live.targetBufferBytes).isEqualTo(32 * MB)
        assertThat(resolved.vod.targetBufferBytes).isEqualTo(32 * MB)
    }

    @Test
    fun lowRamDevice_smallerBytesShorterMaxBuffer_noBackBuffer() {
        val normal = tuning.resolve(DeviceMemory(isLowRamDevice = false, memoryClassMb = 128))
        val low = tuning.resolve(DeviceMemory(isLowRamDevice = true, memoryClassMb = 128))

        assertThat(low.live.targetBufferBytes).isLessThan(normal.live.targetBufferBytes)
        assertThat(low.live.targetBufferBytes).isEqualTo((128L * MB * 0.15).toInt())
        assertThat(low.live.maxBufferMs).isEqualTo(9_000)
        assertThat(low.vod.maxBufferMs).isEqualTo(30_000)
        assertThat(low.vod.backBufferMs).isEqualTo(0)
        // Start thresholds are untouched: zap time does not get worse on low-RAM devices.
        assertThat(low.live.bufferForPlaybackMs).isEqualTo(LoadControlProfile.LIVE.bufferForPlaybackMs)
        assertThat(low.live.maxBufferMs).isAtLeast(low.live.minBufferMs)
    }

    @Test
    fun tinyHeap_neverGoesBelowFloor() {
        val low = tuning.resolve(DeviceMemory(isLowRamDevice = true, memoryClassMb = 32))
        assertThat(low.live.targetBufferBytes).isEqualTo(tuning.minTargetBufferBytes)
    }

    @Test
    fun invalidProfilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LoadControlProfile.LIVE.copy(bufferForPlaybackMs = LoadControlProfile.LIVE.minBufferMs + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoadControlProfile.LIVE.copy(maxBufferMs = 1_000)
        }
    }
}

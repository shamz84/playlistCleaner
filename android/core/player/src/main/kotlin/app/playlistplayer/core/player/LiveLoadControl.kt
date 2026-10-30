@file:OptIn(UnstableApi::class)

package app.playlistplayer.core.player

import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Buffer numbers for one playback kind, fed to [DefaultLoadControl]. All durations milliseconds.
 *
 * @property minBufferMs loading resumes when the buffer falls below this.
 * @property maxBufferMs loading pauses when the buffer reaches this (bounded for live so a server
 *   burst cannot push playback far behind the live edge).
 * @property bufferForPlaybackMs buffer needed to start playback (zap time knob).
 * @property bufferForPlaybackAfterRebufferMs buffer needed to resume after a rebuffer.
 * @property targetBufferBytes byte cap before device scaling; the allocator is Java heap, so
 *   [LoadControlTuning] caps it by memory class.
 * @property prioritizeTimeOverSizeThresholds false lets the byte cap win, which keeps low-RAM
 *   devices safe with 4K streams.
 * @property backBufferMs media kept behind the playhead (VOD seek-back); 0 for live.
 */
data class LoadControlProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
    val backBufferMs: Int,
) {
    init {
        require(bufferForPlaybackMs in 0..minBufferMs) { "bufferForPlaybackMs must be in 0..minBufferMs" }
        require(bufferForPlaybackAfterRebufferMs in 0..minBufferMs) { "bufferForPlaybackAfterRebufferMs must be in 0..minBufferMs" }
        require(maxBufferMs >= minBufferMs) { "maxBufferMs must be >= minBufferMs" }
        require(targetBufferBytes > 0 && backBufferMs >= 0)
    }

    fun toDefaultLoadControl(allocator: DefaultAllocator): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(prioritizeTimeOverSizeThresholds)
            .setBackBuffer(backBufferMs, backBufferMs > 0)
            .build()

    companion object {
        /**
         * Live: start at ~1.2 s buffered, resume after a rebuffer at 2 s, keep at most 15 s.
         * 48 MB covers ~15 s of a 25 Mbit/s 4K HEVC stream before heap capping.
         */
        val LIVE = LoadControlProfile(
            minBufferMs = 6_000,
            maxBufferMs = 15_000,
            bufferForPlaybackMs = 1_200,
            bufferForPlaybackAfterRebufferMs = 2_000,
            targetBufferBytes = 48 * MB,
            prioritizeTimeOverSizeThresholds = false,
            backBufferMs = 0,
        )

        /** VOD: larger forward buffer and a back buffer for short seek-backs. */
        val VOD = LoadControlProfile(
            minBufferMs = 20_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
            targetBufferBytes = 96 * MB,
            prioritizeTimeOverSizeThresholds = false,
            backBufferMs = 15_000,
        )

        const val MB: Int = 1024 * 1024
    }
}

/** Memory facts that drive buffer scaling. */
data class DeviceMemory(val isLowRamDevice: Boolean, val memoryClassMb: Int) {
    companion object {
        fun from(context: Context): DeviceMemory {
            val am = context.getSystemService(ActivityManager::class.java)
            return DeviceMemory(isLowRamDevice = am.isLowRamDevice, memoryClassMb = am.memoryClass)
        }
    }
}

/** The live and VOD profiles after device scaling. */
data class ResolvedLoadControlProfiles(val live: LoadControlProfile, val vod: LoadControlProfile)

/**
 * Tunable defaults (U14 adjusts these from device measurements).
 *
 * Byte caps are limited to [heapFraction] of the app's memory class (Java heap, where the
 * allocator lives), or [lowRamHeapFraction] on `isLowRamDevice()` devices, never below
 * [minTargetBufferBytes]. Low-RAM devices also scale max buffer durations by
 * [lowRamMaxBufferScale] and keep no back buffer.
 */
data class LoadControlTuning(
    val live: LoadControlProfile = LoadControlProfile.LIVE,
    val vod: LoadControlProfile = LoadControlProfile.VOD,
    val heapFraction: Double = 0.25,
    val lowRamHeapFraction: Double = 0.15,
    val lowRamMaxBufferScale: Double = 0.6,
    val minTargetBufferBytes: Int = 8 * LoadControlProfile.MB,
) {
    fun resolve(memory: DeviceMemory): ResolvedLoadControlProfiles =
        ResolvedLoadControlProfiles(scale(live, memory), scale(vod, memory))

    fun scale(profile: LoadControlProfile, memory: DeviceMemory): LoadControlProfile {
        val fraction = if (memory.isLowRamDevice) lowRamHeapFraction else heapFraction
        val heapCap = (memory.memoryClassMb.coerceAtLeast(16).toLong() * LoadControlProfile.MB * fraction).toLong()
        val bytes = minOf(profile.targetBufferBytes.toLong(), heapCap)
            .coerceAtLeast(minTargetBufferBytes.toLong())
            .toInt()
        if (!memory.isLowRamDevice) return profile.copy(targetBufferBytes = bytes)
        val maxBuffer = (profile.maxBufferMs * lowRamMaxBufferScale).toInt().coerceAtLeast(profile.minBufferMs)
        return profile.copy(targetBufferBytes = bytes, maxBufferMs = maxBuffer, backBufferMs = 0)
    }
}

/** Which profile the next prepared item uses. */
enum class PlaybackMode { LIVE, VOD }

/**
 * A [LoadControl] for the single long-lived player that switches between the live and VOD
 * profiles. Both delegates share one allocator (ExoPlayer reads the allocator once) and receive
 * every lifecycle call; decisions go to the active one. Switch [mode] only while the player is
 * stopped, before preparing the next item.
 */
class ProfileLoadControl(profiles: ResolvedLoadControlProfiles) : LoadControl {

    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val live = profiles.live.toDefaultLoadControl(allocator)
    private val vod = profiles.vod.toDefaultLoadControl(allocator)

    @Volatile
    var mode: PlaybackMode = PlaybackMode.LIVE

    private val active: DefaultLoadControl get() = if (mode == PlaybackMode.LIVE) live else vod
    private val inactive: DefaultLoadControl get() = if (mode == PlaybackMode.LIVE) vod else live

    override fun onPrepared(playerId: PlayerId) {
        live.onPrepared(playerId)
        vod.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) {
        // Active last, so the shared allocator ends up with the active profile's target size.
        inactive.onTracksSelected(parameters, trackGroups, trackSelections)
        active.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        live.onStopped(playerId)
        vod.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        live.onReleased(playerId)
        vod.onReleased(playerId)
    }

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = active.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = active.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = active.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = active.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = false // No preloading: max_connections is often 1.
}

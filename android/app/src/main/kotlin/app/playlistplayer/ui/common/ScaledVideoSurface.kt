package app.playlistplayer.ui.common

import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.playlistplayer.feature.settings.VideoScaling

/** The current video scaling and a way to step to the next one; provided once above the UI tree. */
class VideoScalingControl(val scaling: VideoScaling, val select: (VideoScaling) -> Unit, val cycle: () -> Unit)

val LocalVideoScaling = compositionLocalOf { VideoScalingControl(VideoScaling.FIT, {}, {}) }
val LocalLiveVideoScaling = compositionLocalOf { VideoScalingControl(VideoScaling.FIT, {}, {}) }

/** Size of the video picture inside a [containerWidth] x [containerHeight] box for [scaling]. */
internal fun scaledVideoSize(
    scaling: VideoScaling,
    videoAspect: Float,
    containerWidth: Float,
    containerHeight: Float,
): Pair<Float, Float> {
    if (scaling == VideoScaling.STRETCH || videoAspect <= 0f || containerWidth <= 0f || containerHeight <= 0f) {
        return containerWidth to containerHeight
    }
    val containerAspect = containerWidth / containerHeight
    val aspect = scaling.forcedAspect ?: videoAspect
    val widthLimited = if (scaling == VideoScaling.ZOOM) aspect < containerAspect else aspect > containerAspect
    return if (widthLimited) {
        containerWidth to containerWidth / aspect
    } else {
        containerHeight * aspect to containerHeight
    }
}

/**
 * Video output. SurfaceView (not TextureView): correct HDR output and frame timing. The picture
 * is sized from the video's own aspect ratio according to [LocalVideoScaling]; before the first
 * frame is decoded it fills [modifier]. Zoom crops to the box, so callers should pass bounded sizes.
 */
@Composable
fun ScaledVideoSurface(exoPlayer: ExoPlayer, modifier: Modifier = Modifier, scaling: VideoScaling = LocalVideoScaling.current.scaling) {
    var aspect by remember(exoPlayer) { mutableFloatStateOf(aspectOf(exoPlayer.videoSize)) }
    val surface = remember { mutableStateOf<SurfaceView?>(null) }
    val subtitles = remember { mutableStateOf<SubtitleView?>(null) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                aspect = aspectOf(videoSize)
            }

            // A bare SurfaceView draws no text: the cues must be handed to a SubtitleView.
            override fun onCues(cueGroup: CueGroup) {
                subtitles.value?.setCues(cueGroup.cues)
            }
        }
        exoPlayer.addListener(listener)
        aspect = aspectOf(exoPlayer.videoSize)
        onDispose { exoPlayer.removeListener(listener) }
    }

    BoxWithConstraints(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val (w, h) = with(density) {
            val size = scaledVideoSize(scaling, aspect, maxWidth.toPx(), maxHeight.toPx())
            size.first.toDp() to size.second.toDp()
        }
        AndroidView(
            factory = { context -> SurfaceView(context).also { surface.value = it } },
            modifier = Modifier.size(w, h),
        )
        AndroidView(
            factory = { context ->
                SubtitleView(context).apply {
                    setUserDefaultStyle()
                    setUserDefaultTextSize()
                    setCues(exoPlayer.currentCues.cues)
                }.also { subtitles.value = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
    val view = surface.value
    DisposableEffect(exoPlayer, view) {
        if (view != null) exoPlayer.setVideoSurfaceView(view)
        onDispose { if (view != null) exoPlayer.clearVideoSurfaceView(view) }
    }
}

private fun aspectOf(size: VideoSize): Float =
    if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f

object VideoScalingLabels {
    @androidx.annotation.StringRes
    fun label(scaling: VideoScaling): Int = when (scaling) {
        VideoScaling.FIT -> app.playlistplayer.R.string.video_scaling_fit
        VideoScaling.STRETCH -> app.playlistplayer.R.string.video_scaling_stretch
        VideoScaling.R4_3 -> app.playlistplayer.R.string.video_scaling_4_3
        VideoScaling.R16_9 -> app.playlistplayer.R.string.video_scaling_16_9
        VideoScaling.R2_35 -> app.playlistplayer.R.string.video_scaling_2_35
        VideoScaling.ZOOM -> app.playlistplayer.R.string.video_scaling_zoom
    }
}

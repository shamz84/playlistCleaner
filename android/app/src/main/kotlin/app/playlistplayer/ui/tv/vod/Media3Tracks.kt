package app.playlistplayer.ui.tv.vod

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.playlistplayer.feature.vod.TrackOption
import app.playlistplayer.feature.vod.TrackType

/**
 * Maps Media3 [Tracks] to [TrackOption]s (id = "groupIndex:trackIndex" within the current
 * track list) and applies a selection back as track overrides.
 */
internal object Media3Tracks {

    fun options(tracks: Tracks): List<TrackOption> = buildList {
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.TEXT
                else -> return@forEachIndexed
            }
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                    ?: "${if (type == TrackType.AUDIO) "Audio" else "Subtitles"} ${size + 1}"
                add(
                    TrackOption(
                        id = "$groupIndex:$trackIndex",
                        type = type,
                        label = label,
                        language = format.language,
                        selected = group.isTrackSelected(trackIndex),
                    ),
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun apply(player: ExoPlayer, audioId: String?, subtitleId: String?) {
        val groups = player.currentTracks.groups
        val builder = player.trackSelectionParameters.buildUpon()
        parse(audioId)?.let { (g, t) ->
            groups.getOrNull(g)?.takeIf { it.type == C.TRACK_TYPE_AUDIO }?.let {
                builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, t))
            }
        }
        val text = parse(subtitleId)?.let { (g, t) -> groups.getOrNull(g)?.takeIf { it.type == C.TRACK_TYPE_TEXT }?.let { it to t } }
        if (text == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(text.first.mediaTrackGroup, text.second))
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        val parts = id?.split(':') ?: return null
        if (parts.size != 2) return null
        val g = parts[0].toIntOrNull() ?: return null
        val t = parts[1].toIntOrNull() ?: return null
        return g to t
    }
}

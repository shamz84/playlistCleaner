package app.playlistplayer.ui.common

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.playlistplayer.feature.vod.RawTrack
import app.playlistplayer.feature.vod.TrackLabeler
import app.playlistplayer.feature.vod.TrackOption
import app.playlistplayer.feature.vod.TrackPreference
import app.playlistplayer.feature.vod.TrackType
import java.util.Locale

/**
 * Maps Media3 [Tracks] to [TrackOption]s (id = "groupIndex:trackIndex" within the current
 * track list, labels from [TrackLabeler]) and applies selections and language preferences back
 * to the player's [TrackSelectionParameters].
 */
object Media3Tracks {

    fun options(tracks: Tracks, displayLocale: Locale = Locale.getDefault()): List<TrackOption> =
        TrackLabeler.options(rawTracks(tracks), displayLocale)

    /** Supported audio and text tracks, in player order. */
    fun rawTracks(tracks: Tracks): List<RawTrack> = buildList {
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.TEXT
                else -> return@forEachIndexed
            }
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                add(
                    RawTrack(
                        id = "$groupIndex:$trackIndex",
                        type = type,
                        language = format.language,
                        label = format.label,
                        channelCount = format.channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
                        sampleMimeType = format.sampleMimeType,
                        codecs = format.codecs,
                        forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        sdh = format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0,
                        selected = group.isTrackSelected(trackIndex),
                    ),
                )
            }
        }
    }

    /** Applies a manual (or preference-resolved) choice as overrides; [subtitleId] null turns subtitles off. */
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

    /**
     * Before an on-demand item starts: drops the previous item's audio/text overrides and lets the
     * player pick by [preference] (preferred audio languages; subtitles in the preferred language,
     * or disabled when off). Embedded MKV/MP4 and HLS (WebVTT, CEA-608) text tracks all go
     * through this selection.
     */
    fun startWithPreference(player: ExoPlayer, preference: TrackPreference) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguages(*preference.audioLanguages.toTypedArray())
            .setPreferredTextLanguages(*preference.subtitleLanguages.toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, preference.subtitlesOff)
            .build()
    }

    /** After a manual choice: later selections (failover, next episode) prefer [preference]'s languages. */
    fun preferLanguages(player: ExoPlayer, preference: TrackPreference) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*preference.audioLanguages.toTypedArray())
            .setPreferredTextLanguages(*preference.subtitleLanguages.toTypedArray())
            .build()
    }

    /** The player's track-selection parameters before on-demand playback changed them. */
    class Snapshot internal constructor(internal val parameters: TrackSelectionParameters)

    fun snapshot(player: ExoPlayer): Snapshot = Snapshot(player.trackSelectionParameters)

    /**
     * When leaving on-demand playback: puts back the parameters [snapshot] captured (keeping the
     * current tunneling flag, a separate setting), so live playback on the shared player keeps
     * its own behaviour.
     */
    @OptIn(UnstableApi::class)
    fun restore(player: ExoPlayer, snapshot: Snapshot) {
        val saved = snapshot.parameters
        val current = player.trackSelectionParameters
        player.trackSelectionParameters = if (saved is DefaultTrackSelector.Parameters && current is DefaultTrackSelector.Parameters) {
            saved.buildUpon().setTunnelingEnabled(current.tunnelingEnabled).build()
        } else {
            saved
        }
    }
    private fun parse(id: String?): Pair<Int, Int>? {
        val parts = id?.split(':') ?: return null
        if (parts.size != 2) return null
        val g = parts[0].toIntOrNull() ?: return null
        val t = parts[1].toIntOrNull() ?: return null
        return g to t
    }

}

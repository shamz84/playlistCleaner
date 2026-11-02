@file:OptIn(UnstableApi::class)

package app.playlistplayer.ui.common

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.playlistplayer.feature.vod.TrackType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class Media3TracksTest {

    private fun group(format: Format, selected: Boolean = false, supported: Boolean = true) = Tracks.Group(
        TrackGroup(format),
        false,
        intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE),
        booleanArrayOf(selected),
    )

    private fun audio(language: String?, channels: Int, mime: String = MimeTypes.AUDIO_AAC) =
        Format.Builder().setSampleMimeType(mime).setLanguage(language).setChannelCount(channels).build()

    private fun text(language: String?, mime: String = MimeTypes.TEXT_VTT, selectionFlags: Int = 0, roleFlags: Int = 0) =
        Format.Builder().setSampleMimeType(mime).setLanguage(language).setSelectionFlags(selectionFlags).setRoleFlags(roleFlags).build()

    @Test
    fun mapsMultiAudioAndMultiSubtitleTracksFromMedia3() {
        val tracks = Tracks(
            listOf(
                group(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), selected = true),
                group(audio("eng", 6, MimeTypes.AUDIO_E_AC3), selected = true),
                group(audio("fr", 2)),
                group(audio("und", 2)),
                group(audio("de", 2), supported = false),
                group(text("en")),
                group(text("en", selectionFlags = C.SELECTION_FLAG_FORCED)),
                group(text("nl", roleFlags = C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND), selected = true),
                group(text(null, mime = MimeTypes.APPLICATION_CEA608)),
            ),
        )

        val options = Media3Tracks.options(tracks, Locale.ENGLISH)

        assertThat(options.map { it.id to it.label }).containsExactly(
            "1:0" to "English · 5.1 · Dolby Digital+",
            "2:0" to "French · Stereo",
            "3:0" to "Audio 3 · Stereo",
            "5:0" to "English",
            "6:0" to "English · Forced",
            "7:0" to "Dutch · SDH",
            "8:0" to "Subtitles 4 · CC",
        ).inOrder()
        assertThat(options.filter { it.type == TrackType.AUDIO }.map { it.id }).containsExactly("1:0", "2:0", "3:0").inOrder()
        assertThat(options.filter { it.selected }.map { it.id }).containsExactly("1:0", "7:0").inOrder()
        assertThat(options.single { it.id == "6:0" }.forced).isTrue()
        assertThat(options.first().language).isEqualTo("en")
    }
}

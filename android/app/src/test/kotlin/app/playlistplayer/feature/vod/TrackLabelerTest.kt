package app.playlistplayer.feature.vod

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class TrackLabelerTest {

    private fun labels(vararg tracks: RawTrack) = TrackLabeler.options(tracks.toList(), Locale.ENGLISH)

    @Test
    fun multiAudioStreamGetsLanguageChannelAndCodecLabels() {
        val options = labels(
            RawTrack("0:0", TrackType.AUDIO, "en", channelCount = 6, sampleMimeType = "audio/eac3", selected = true),
            RawTrack("1:0", TrackType.AUDIO, "fre", channelCount = 2, sampleMimeType = "audio/mp4a-latm"),
            RawTrack("2:0", TrackType.AUDIO, "de", channelCount = 8, sampleMimeType = "audio/true-hd"),
            RawTrack("3:0", TrackType.AUDIO, "es", channelCount = 1, codecs = "ac-3"),
        )
        assertThat(options.map { it.label }).containsExactly(
            "English · 5.1 · Dolby Digital+",
            "French · Stereo",
            "German · 7.1 · Dolby TrueHD",
            "Spanish · Mono · Dolby Digital",
        ).inOrder()
        assertThat(options.map { it.language }).containsExactly("en", "fr", "de", "es").inOrder()
        assertThat(options.map { it.selected }).containsExactly(true, false, false, false).inOrder()
        assertThat(options.map { it.type }.distinct()).containsExactly(TrackType.AUDIO)
    }

    @Test
    fun unknownAndUndeterminedLanguagesGetANumberedFallback() {
        val options = labels(
            RawTrack("0:0", TrackType.AUDIO, "en", channelCount = 2),
            RawTrack("0:1", TrackType.AUDIO, "und", channelCount = 6),
            RawTrack("0:2", TrackType.AUDIO, null),
            RawTrack("1:0", TrackType.TEXT, "und"),
        )
        assertThat(options.map { it.label }).containsExactly("English · Stereo", "Audio 2 · 5.1", "Audio 3", "Subtitles 1").inOrder()
        assertThat(options[1].language).isNull()
        assertThat(options[2].language).isNull()
    }

    @Test
    fun languageIsRecoveredFromANameOrAFullNameInTheLanguageField() {
        val options = labels(
            RawTrack("0:0", TrackType.TEXT, "english"),
            RawTrack("0:1", TrackType.TEXT, null, label = "Eng - SRT"),
            RawTrack("0:2", TrackType.TEXT, "und", label = "Deutsch [Forced]", forced = true),
            RawTrack("0:3", TrackType.TEXT, null, label = "Track 4"),
        )
        assertThat(options.map { it.label }).containsExactly(
            "English",
            "English · Eng - SRT",
            "German · Deutsch [Forced]",
            "Track 4",
        ).inOrder()
        assertThat(options.map { it.language }).containsExactly("en", "en", "de", null).inOrder()
    }

    @Test
    fun subtitleFormatDistinguishesTracksWithoutALanguage() {
        val options = labels(
            RawTrack("0:0", TrackType.TEXT, null, sampleMimeType = "application/x-subrip"),
            RawTrack("0:1", TrackType.TEXT, "und", sampleMimeType = "application/pgs"),
            RawTrack("0:2", TrackType.TEXT, "en", sampleMimeType = "application/x-subrip"),
        )
        assertThat(options.map { it.label }).containsExactly("Subtitles 1 · SRT", "Subtitles 2 · PGS", "English").inOrder()
    }

    @Test
    fun streamTrackNamesAreUsedWhenTheyAddInformation() {
        val options = labels(
            RawTrack("0:0", TrackType.AUDIO, "en", label = "Commentary", channelCount = 2),
            RawTrack("0:1", TrackType.AUDIO, "en", label = "English 5.1", channelCount = 6),
            RawTrack("0:2", TrackType.AUDIO, "eng", label = "ENG"),
            RawTrack("0:3", TrackType.AUDIO, null, label = "Director"),
        )
        assertThat(options.map { it.label })
            .containsExactly("English · Commentary · Stereo", "English · 5.1", "English", "Director")
            .inOrder()
    }

    @Test
    fun multiSubtitleStreamFlagsForcedSdhAndClosedCaptions() {
        val options = labels(
            RawTrack("2:0", TrackType.TEXT, "en"),
            RawTrack("2:1", TrackType.TEXT, "en", forced = true),
            RawTrack("2:2", TrackType.TEXT, "en", sdh = true, selected = true),
            RawTrack("3:0", TrackType.TEXT, "nl"),
            RawTrack("4:0", TrackType.TEXT, null, sampleMimeType = "application/cea-608"),
        )
        assertThat(options.map { it.label }).containsExactly(
            "English",
            "English · Forced",
            "English · SDH",
            "Dutch",
            "Subtitles 5 · CC",
        ).inOrder()
        assertThat(options.map { it.forced }).containsExactly(false, true, false, false, false).inOrder()
        assertThat(options.single { it.selected }.id).isEqualTo("2:2")
    }

    @Test
    fun identicalLabelsAreNumbered() {
        val options = labels(
            RawTrack("0:0", TrackType.AUDIO, "en", channelCount = 2),
            RawTrack("0:1", TrackType.AUDIO, "en", channelCount = 2),
            RawTrack("1:0", TrackType.TEXT, "en"),
        )
        assertThat(options.map { it.label }).containsExactly("English · Stereo", "English · Stereo (2)", "English").inOrder()
    }

    @Test
    fun languageTagsNormaliseAndMatchOnThePrimaryLanguage() {
        assertThat(TrackLanguage.normalize("eng")).isEqualTo("en")
        assertThat(TrackLanguage.normalize("ger")).isEqualTo("de")
        assertThat(TrackLanguage.normalize("pt_BR")).isEqualTo("pt-br")
        assertThat(TrackLanguage.normalize("iw")).isEqualTo("he")
        assertThat(TrackLanguage.normalize("und")).isNull()
        assertThat(TrackLanguage.normalize(" ")).isNull()
        assertThat(TrackLanguage.matches("en-US", "en")).isTrue()
        assertThat(TrackLanguage.matches("fra", "fr")).isTrue()
        assertThat(TrackLanguage.matches("en", "fr")).isFalse()
        assertThat(TrackLanguage.matches(null, "en")).isFalse()
        assertThat(TrackLanguage.displayName("pt-BR", Locale.ENGLISH)).isEqualTo("Portuguese (Brazil)")
        assertThat(TrackLanguage.displayName("qqq", Locale.ENGLISH)).isNull()
    }
}

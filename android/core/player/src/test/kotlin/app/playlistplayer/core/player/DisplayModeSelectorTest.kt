package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayModeSelectorTest {

    private val uhd60 = DisplayModeSpec(1, 3840, 2160, 60f)
    private val uhd50 = DisplayModeSpec(2, 3840, 2160, 50f)
    private val fhd60 = DisplayModeSpec(3, 1920, 1080, 60f)
    private val fhd50 = DisplayModeSpec(4, 1920, 1080, 50f)
    private val uhd24 = DisplayModeSpec(5, 3840, 2160, 24f)
    private val uhd23976 = DisplayModeSpec(6, 3840, 2160, 23.976f)
    private val fhd5994 = DisplayModeSpec(7, 1920, 1080, 59.94f)

    private val all = listOf(uhd60, uhd50, fhd60, fhd50, uhd24, uhd23976, fhd5994)

    private fun select(modes: List<DisplayModeSpec>, current: DisplayModeSpec?, w: Int, h: Int, fps: Float?) =
        DisplayModeSelector.select(modes, current, w, h, fps)

    @Test
    fun exactRefresh_keepsCurrentResolution() {
        assertThat(select(all, uhd60, 1920, 1080, 50f)).isEqualTo(uhd50)
        assertThat(select(all, fhd60, 1920, 1080, 50f)).isEqualTo(fhd50)
    }

    @Test
    fun integerMultiple_whenNoExactRefresh() {
        assertThat(select(all, uhd60, 1920, 1080, 25f)).isEqualTo(uhd50)
        assertThat(select(listOf(uhd60, uhd50), uhd60, 1280, 720, 30f)).isEqualTo(uhd60)
    }

    @Test
    fun exactBeatsMultiple_andFilmRatesAreDistinguished() {
        assertThat(select(all, uhd60, 3840, 2160, 23.976f)).isEqualTo(uhd23976)
        assertThat(select(all, uhd60, 3840, 2160, 24f)).isEqualTo(uhd24)
        assertThat(select(all, fhd60, 1920, 1080, 59.94f)).isEqualTo(fhd5994)
    }

    @Test
    fun exactRefreshAtAnotherResolution_beatsMultipleAtCurrentResolution() {
        val modes = listOf(uhd60, fhd50)
        assertThat(select(modes, uhd60, 1920, 1080, 50f)).isEqualTo(fhd50)
    }

    @Test
    fun neverDropsBelowContentResolution_noMatchMeansNoSwitch() {
        // 4K50 content on a display whose only 50 Hz mode is 1080p: stay put.
        assertThat(select(listOf(uhd60, fhd50), uhd60, 3840, 2160, 50f)).isNull()
    }

    @Test
    fun unknownFrameRate_orNoModes_isNoOp() {
        assertThat(select(all, uhd60, 1920, 1080, null)).isNull()
        assertThat(select(emptyList(), null, 1920, 1080, 50f)).isNull()
        assertThat(select(listOf(uhd60), uhd60, 1920, 1080, 50f)).isNull()
    }

    @Test
    fun contentLargerThanEveryMode_usesAllModes() {
        assertThat(select(listOf(fhd60, fhd50), fhd60, 7680, 4320, 50f)).isEqualTo(fhd50)
    }

    @Test
    fun refreshMultiple() {
        assertThat(DisplayModeSelector.refreshMultiple(50f, 50f)).isEqualTo(1)
        assertThat(DisplayModeSelector.refreshMultiple(50f, 25f)).isEqualTo(2)
        assertThat(DisplayModeSelector.refreshMultiple(59.94f, 29.97f)).isEqualTo(2)
        assertThat(DisplayModeSelector.refreshMultiple(120f, 24f)).isEqualTo(5)
        assertThat(DisplayModeSelector.refreshMultiple(60f, 59.94f)).isNull()
        assertThat(DisplayModeSelector.refreshMultiple(60f, 50f)).isNull()
        assertThat(DisplayModeSelector.refreshMultiple(30f, 60f)).isNull()
    }
}

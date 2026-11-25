package app.playlistplayer.ui.tv.epg

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuideLeftDoubleTapTest {
    private fun event(time: Long, action: Int, code: Int = KeyEvent.KEYCODE_DPAD_LEFT, repeat: Int = 0, flags: Int = 0) =
        KeyEvent(0, time, action, code, repeat, 0, 0, 0, flags)

    @Test
    fun `two separate left presses within the timeout jump to now once`() {
        val taps = GuideLeftDoubleTap(300)
        assertThat(taps.onKeyEvent(event(100, KeyEvent.ACTION_DOWN))).isFalse()
        assertThat(taps.onKeyEvent(event(150, KeyEvent.ACTION_UP))).isFalse()
        assertThat(taps.onKeyEvent(event(400, KeyEvent.ACTION_DOWN))).isTrue()
        assertThat(taps.onKeyEvent(event(410, KeyEvent.ACTION_UP))).isFalse()
        assertThat(taps.onKeyEvent(event(420, KeyEvent.ACTION_DOWN))).isFalse()
    }

    @Test
    fun `slow or interrupted presses do not jump`() {
        val taps = GuideLeftDoubleTap(300)
        taps.onKeyEvent(event(100, KeyEvent.ACTION_DOWN))
        taps.onKeyEvent(event(120, KeyEvent.ACTION_UP))
        assertThat(taps.onKeyEvent(event(401, KeyEvent.ACTION_DOWN))).isFalse()
        taps.onKeyEvent(event(410, KeyEvent.ACTION_UP))
        taps.onKeyEvent(event(420, KeyEvent.ACTION_DOWN, code = KeyEvent.KEYCODE_DPAD_RIGHT))
        assertThat(taps.onKeyEvent(event(430, KeyEvent.ACTION_DOWN))).isFalse()
    }

    @Test
    fun `held and cancelled presses cannot be mistaken for a double tap`() {
        val taps = GuideLeftDoubleTap(300)
        taps.onKeyEvent(event(100, KeyEvent.ACTION_DOWN))
        assertThat(taps.onKeyEvent(event(150, KeyEvent.ACTION_DOWN, repeat = 1))).isFalse()
        taps.onKeyEvent(event(160, KeyEvent.ACTION_UP))
        assertThat(taps.onKeyEvent(event(170, KeyEvent.ACTION_DOWN))).isFalse()
        taps.onKeyEvent(event(180, KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED))
        assertThat(taps.onKeyEvent(event(190, KeyEvent.ACTION_DOWN))).isFalse()
    }
}

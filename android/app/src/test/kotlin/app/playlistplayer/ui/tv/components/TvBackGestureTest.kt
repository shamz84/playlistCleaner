package app.playlistplayer.ui.tv.components

import android.os.Looper
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class TvBackGestureTest {
    private fun event(action: Int, repeat: Int = 0, code: Int = KeyEvent.KEYCODE_BACK) =
        KeyEvent(0, 0, action, code, repeat)

    @Test
    fun `short back runs only the short action`() {
        var short = 0
        var long = 0
        val gesture = TvBackGesture({ short++ }, { long++ })
        assertThat(gesture.handle(event(KeyEvent.ACTION_DOWN))).isTrue()
        assertThat(gesture.handle(event(KeyEvent.ACTION_UP))).isTrue()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()))
        assertThat(short).isEqualTo(1)
        assertThat(long).isEqualTo(0)
    }

    @Test
    fun `hold invokes exit once and never navigates back on release`() {
        var short = 0
        var long = 0
        val gesture = TvBackGesture({ short++ }, { long++ })
        gesture.handle(event(KeyEvent.ACTION_DOWN))
        gesture.handle(event(KeyEvent.ACTION_DOWN, repeat = 1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()))
        assertThat(long).isEqualTo(1)
        gesture.handle(event(KeyEvent.ACTION_UP))
        assertThat(short).isEqualTo(0)
        assertThat(long).isEqualTo(1)
    }

    @Test
    fun `cancelled gesture and unrelated keys do not trigger either action`() {
        var actions = 0
        val gesture = TvBackGesture({ actions++ }, { actions++ })
        assertThat(gesture.handle(event(KeyEvent.ACTION_DOWN, code = KeyEvent.KEYCODE_DPAD_LEFT))).isFalse()
        gesture.handle(event(KeyEvent.ACTION_DOWN))
        gesture.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()))
        assertThat(gesture.handle(event(KeyEvent.ACTION_UP))).isFalse()
        assertThat(actions).isEqualTo(0)
    }
}

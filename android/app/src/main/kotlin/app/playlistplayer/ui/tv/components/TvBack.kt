package app.playlistplayer.ui.tv.components

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.window.Dialog

internal val LocalTvLongBack = compositionLocalOf<(() -> Unit)?> { null }

/** Intercepts a complete Back gesture so a hold cannot also perform the short-Back action. */
internal class TvBackGesture(
    private val onShort: () -> Unit,
    private val onLong: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pressed = false
    private var longTriggered = false
    private val longPress = Runnable {
        if (pressed) {
            longTriggered = true
            onLong()
        }
    }

    fun handle(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    cancel()
                    pressed = true
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!pressed) return false
                val short = !event.isCanceled && !longTriggered
                cancel()
                if (short) onShort()
                return true
            }
            else -> return false
        }
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        pressed = false
        longTriggered = false
    }
}

/** Dialog windows receive keys independently of the activity. */
@Composable
internal fun TvDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val longBack = LocalTvLongBack.current
    val dismiss by rememberUpdatedState(onDismissRequest)
    val onLong by rememberUpdatedState(longBack)
    val gesture = remember { TvBackGesture(onShort = { dismiss() }, onLong = { onLong?.invoke() }) }
    DisposableEffect(gesture) { onDispose { gesture.cancel() } }
    Dialog(onDismissRequest = onDismissRequest) {
        Box(Modifier.onPreviewKeyEvent { event -> longBack != null && gesture.handle(event.nativeKeyEvent) }) {
            content()
        }
    }
}

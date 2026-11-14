package app.playlistplayer.ui.common

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Small translucent clock in the top-right corner, drawn over the video. Not focusable. */
@Composable
fun ClockOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val formatter = remember(context) {
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
            .withZone(ZoneId.systemDefault())
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    Box(modifier = modifier.fillMaxSize().padding(top = 16.dp, end = 24.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            text = formatter.format(Instant.ofEpochMilli(now)),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 24.sp,
        )
    }
}

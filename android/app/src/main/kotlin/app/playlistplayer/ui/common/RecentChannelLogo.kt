package app.playlistplayer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage

@Composable
fun RecentChannelLogo(name: String, logoUrl: String?, modifier: Modifier = Modifier) {
    @Composable
    fun Fallback() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White)
        }
    }
    SubcomposeAsyncImage(
        model = logoUrl,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        loading = { Fallback() },
        error = { Fallback() },
    )
}

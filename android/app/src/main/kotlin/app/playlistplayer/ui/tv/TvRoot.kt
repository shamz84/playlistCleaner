package app.playlistplayer.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Placeholder root of the TV UI tree (tv-material). Replaced by the side-rail shell in U9. */
@Composable
fun TvRoot() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Playlist Player (TV)", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

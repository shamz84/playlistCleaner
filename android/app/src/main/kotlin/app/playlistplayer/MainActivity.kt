package app.playlistplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.playlistplayer.ui.mobile.MobileRoot
import app.playlistplayer.ui.tv.TvRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PlaylistPlayerApp).container
        val formFactor = container.formFactor
        setContent {
            when (formFactor) {
                FormFactor.Tv -> TvRoot()
                FormFactor.Mobile -> MobileRoot()
            }
        }
    }
}

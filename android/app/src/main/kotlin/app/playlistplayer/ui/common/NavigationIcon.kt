package app.playlistplayer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.playlistplayer.R
import app.playlistplayer.feature.shell.Destination

@DrawableRes
fun Destination.iconRes(): Int = when (this) {
    Destination.LiveTv -> R.drawable.nav_rounded_live
    Destination.Home -> R.drawable.nav_rounded_home
    Destination.Movies -> R.drawable.nav_rounded_movies
    Destination.Series -> R.drawable.nav_rounded_series
    Destination.Search -> R.drawable.nav_rounded_search
    Destination.Settings -> R.drawable.nav_rounded_settings
}

@Composable
fun NavigationIcon(destination: Destination, tint: Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(destination.iconRes()),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(24.dp),
    )
}

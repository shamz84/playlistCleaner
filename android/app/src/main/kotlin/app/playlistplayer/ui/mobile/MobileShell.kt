package app.playlistplayer.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.playlistplayer.R
import app.playlistplayer.feature.shell.Destination
import app.playlistplayer.ui.common.NavigationIcon
import app.playlistplayer.ui.mobile.navigation.BottomDestinations
import app.playlistplayer.ui.mobile.navigation.MobileNavigationType

object MobileShellTags {
    const val TOP_BAR = "mobile.shell.topBar"
    const val BOTTOM_BAR = "mobile.shell.bottomBar"
    const val RAIL = "mobile.shell.rail"
    const val SETTINGS = "mobile.shell.settings"
    const val BACK = "mobile.shell.back"
    const val CONTENT = "mobile.shell.content"
    fun item(destination: Destination) = "mobile.shell.item.${destination.name}"
}

/**
 * Phone shell: top app bar (title, Back on pushed screens and Settings, and the Settings action) and
 * the top-level destinations in a bottom [NavigationBar], or a [NavigationRail] on wide windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileShell(
    current: Destination,
    title: String,
    showBack: Boolean,
    navigationType: MobileNavigationType,
    onSelect: (Destination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val settingsDescription = stringResource(R.string.mobile_open_settings)
    val backDescription = stringResource(R.string.mobile_back)
    val railDescription = stringResource(R.string.nav_rail_description)
    val topBar = @Composable {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(MobileShellTags.BACK).semantics { contentDescription = backDescription }) {
                        GlyphIcon("←")
                    }
                }
            },
            actions = {
                if (current != Destination.Settings) {
                    IconButton(
                        onClick = { onSelect(Destination.Settings) },
                        modifier = Modifier.testTag(MobileShellTags.SETTINGS).semantics { contentDescription = settingsDescription },
                    ) { NavigationIcon(Destination.Settings, LocalContentColor.current) }
                }
            },
            modifier = Modifier.testTag(MobileShellTags.TOP_BAR),
        )
    }

    when (navigationType) {
        MobileNavigationType.BottomBar -> Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = topBar,
            bottomBar = {
                NavigationBar(modifier = Modifier.testTag(MobileShellTags.BOTTOM_BAR).semantics { contentDescription = railDescription }) {
                    BottomDestinations.forEach { destination ->
                        val label = stringResource(destination.mobileLabelRes())
                        NavigationBarItem(
                            selected = current == destination,
                            onClick = { onSelect(destination) },
                            icon = { NavigationIcon(destination, LocalContentColor.current) },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag(MobileShellTags.item(destination)),
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize().testTag(MobileShellTags.CONTENT)) { content() }
        }

        MobileNavigationType.Rail -> Row(modifier = modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.testTag(MobileShellTags.RAIL).semantics { contentDescription = railDescription }) {
                BottomDestinations.forEach { destination ->
                    val label = stringResource(destination.mobileLabelRes())
                    NavigationRailItem(
                        selected = current == destination,
                        onClick = { onSelect(destination) },
                        icon = { NavigationIcon(destination, LocalContentColor.current) },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag(MobileShellTags.item(destination)),
                    )
                }
            }
            Scaffold(modifier = Modifier.weight(1f).fillMaxSize(), topBar = topBar) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize().testTag(MobileShellTags.CONTENT)) { content() }
            }
        }
    }
}

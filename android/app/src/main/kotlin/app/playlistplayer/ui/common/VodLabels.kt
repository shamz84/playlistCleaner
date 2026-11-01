package app.playlistplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.playlistplayer.R
import app.playlistplayer.feature.vod.ContinueCard
import app.playlistplayer.feature.vod.VodSort

/** Label of a full-list sort option (shared by the TV and phone browse screens). */
@Composable
fun sortLabel(sort: VodSort): String = stringResource(
    when (sort) {
        VodSort.RECENTLY_ADDED -> R.string.vod_sort_recently_added
        VodSort.A_Z -> R.string.vod_sort_a_z
        VodSort.RELEASE_YEAR -> R.string.vod_sort_release_year
    },
)

/** "S1 E5" for an episode in progress, "Next: S1 E6" for the next one; null for movies. */
@Composable
fun continueLabel(card: ContinueCard): String? {
    val season = card.season ?: return null
    val episode = card.episode ?: return null
    return if (card.upNext) stringResource(R.string.vod_up_next, season, episode) else stringResource(R.string.vod_episode_short, season, episode)
}

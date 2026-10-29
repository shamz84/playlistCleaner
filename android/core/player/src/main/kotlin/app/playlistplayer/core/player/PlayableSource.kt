package app.playlistplayer.core.player

import app.playlistplayer.core.consolidation.QualityTier

/**
 * One playable version of a channel or title, as the player sees it.
 *
 * [url] carries credentials (Xtream path) and must never be logged or shown; [toString] omits it.
 *
 * @property id stable version id (stream id or entry id) used to de-duplicate failover candidates.
 * @property label user-facing version label, e.g. "BBC One FHD", shown in the "Switched to" notice.
 * @property isLive live streams retry at the live edge and treat end-of-stream as a drop;
 *   VOD resumes at the last known position.
 * @property versionTier quality tier from consolidation, informational for UI and diagnostics.
 * @property mimeType optional explicit MIME type (e.g. HLS); inferred from the URL when null.
 * @property accountKey opaque key passed to [AccountStatusChecker] (typically the playlist id).
 */
data class PlayableSource(
    val id: String,
    val url: String,
    val label: String,
    val isLive: Boolean,
    val versionTier: QualityTier? = null,
    val mimeType: String? = null,
    val accountKey: String? = null,
) {
    override fun toString(): String =
        "PlayableSource(id=$id, label=$label, isLive=$isLive, versionTier=$versionTier)"
}

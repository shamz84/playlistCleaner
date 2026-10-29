package app.playlistplayer.core.player

/**
 * Failover order for one zap: the selected version first, then up to [maxFailovers] other
 * versions in the given order (the consolidation comparator's order). Duplicates by id and
 * repeats of the selected version are dropped.
 *
 * A new controller is created on every zap, so the selected (preferred) version is always tried
 * first again on the next zap even if it failed over last time.
 */
class FailoverController(
    sources: List<PlayableSource>,
    maxFailovers: Int = 5,
) {
    init {
        require(sources.isNotEmpty()) { "At least one source is required" }
        require(maxFailovers >= 0) { "maxFailovers must be >= 0" }
    }

    val preferred: PlayableSource = sources.first()

    /** Other versions eligible for failover, in order. */
    val alternatives: List<PlayableSource> = sources.asSequence()
        .drop(1)
        .filter { it.id != preferred.id }
        .distinctBy { it.id }
        .take(maxFailovers)
        .toList()

    private var nextIndex = 0

    /** Number of failovers performed so far. */
    val failoverCount: Int get() = nextIndex

    /** Number of versions tried so far, including the preferred one. */
    val triedCount: Int get() = 1 + nextIndex

    val hasNext: Boolean get() = nextIndex < alternatives.size

    /** The top untried other version, marking it tried; null when none are left. */
    fun next(): PlayableSource? = alternatives.getOrNull(nextIndex)?.also { nextIndex++ }
}

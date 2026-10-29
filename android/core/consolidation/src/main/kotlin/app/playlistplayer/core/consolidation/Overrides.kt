package app.playlistplayer.core.consolidation

/**
 * A user correction to VOD grouping. Records reference provider item ids (stream / series
 * ids), which are stable across catalog re-syncs, so the same records reapply after every
 * sync. Ids that no longer exist are ignored. Persistence belongs to a later unit; these are
 * plain data.
 */
sealed interface TitleOverride {
    val playlistId: String
    val itemIds: Set<String>
}

/**
 * Moves [itemIds] out of whatever titles hold them into one new title of their own (one per
 * [VodKind] if the ids span kinds). A title left empty disappears.
 */
data class SplitOverride(
    override val playlistId: String,
    override val itemIds: Set<String>,
) : TitleOverride

/**
 * Merges every title that holds any of [itemIds] into one title. Titles are merged only
 * within the same [VodKind]; movies and series never merge. The merged title keeps the key,
 * name and year of the earliest (lowest provider order) participating title.
 */
data class MergeOverride(
    override val playlistId: String,
    override val itemIds: Set<String>,
) : TitleOverride

/** Applies [TitleOverride] records, in list order, to grouped titles. */
object Overrides {

    fun apply(titles: List<VodTitle>, overrides: List<TitleOverride>): List<VodTitle> {
        if (overrides.isEmpty()) return titles
        var current = titles.toMutableList()
        for (override in overrides) {
            current = when (override) {
                is SplitOverride -> split(current, override)
                is MergeOverride -> merge(current, override)
            }
        }
        return current
    }

    private fun split(titles: MutableList<VodTitle>, override: SplitOverride): MutableList<VodTitle> {
        val moved = ArrayList<VodVersion>()
        val result = ArrayList<VodTitle>(titles.size + 1)
        for (title in titles) {
            if (title.playlistId != override.playlistId) {
                result += title
                continue
            }
            val (out, keep) = title.versions.partition { it.item.id in override.itemIds }
            if (out.isEmpty()) {
                result += title
                continue
            }
            moved += out
            if (keep.isNotEmpty()) result += title.copy(versions = keep)
        }
        for ((kind, versions) in moved.groupBy { it.item.kind }) {
            val anchor = versions.minOf { it.item.id }
            val key = "${TitleGrouper.scopePrefix(override.playlistId, kind)}|split:$anchor"
            result += TitleGrouper.buildTitle(key, override.playlistId, kind, versions)
        }
        return result
    }

    private fun merge(titles: MutableList<VodTitle>, override: MergeOverride): MutableList<VodTitle> {
        val participants = titles.filter { title ->
            title.playlistId == override.playlistId &&
                title.versions.any { it.item.id in override.itemIds }
        }
        if (participants.size < 2) return titles
        val result = titles.toMutableList()
        for ((_, group) in participants.groupBy { it.kind }) {
            if (group.size < 2) continue
            val target = group.minWith(
                compareBy<VodTitle>({ t -> t.versions.minOf { it.item.providerOrder } }, { it.key }),
            )
            val merged = target.copy(
                versions = group.flatMap { it.versions }
                    .sortedWith(compareBy({ it.item.providerOrder }, { it.item.id })),
            )
            val index = result.indexOf(target)
            result.removeAll { it in group }
            result.add(index.coerceAtMost(result.size), merged)
        }
        return result
    }
}

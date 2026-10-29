package app.playlistplayer.feature.settings

import java.text.Normalizer
import java.util.Locale

/** A group or category name offered to the suggester. */
data class GroupCandidate(val playlistId: Long, val groupKey: String, val displayName: String)

/** A proposed lock; [matched] is the normalised keyword that triggered it (for the UI). */
data class GroupSuggestion(val group: LockedGroup, val displayName: String, val matched: String)

/**
 * Proposes adult groups/categories to lock on first sync. It only suggests: the user confirms
 * and [ParentalControl.setLockedGroups] applies; nothing is ever hidden automatically.
 *
 * Matching:
 * - Names are NFKD-normalised (fullwidth, mathematical and superscript letters fold to ASCII),
 *   combining marks are dropped and text is lower-cased with [Locale.ROOT].
 * - The text is split into words on anything that is not a letter, digit or `+`. A keyword
 *   matches whole words only, so "Sussex", "Essex" or "Adultswim" do not match.
 * - `18+` / `+18` / 🔞 match as age markers; `18` alone (e.g. "Formula 1 2018") does not.
 * - Exclusion phrases are removed before matching: "Adult Swim" (Cartoon Network block) and
 *   "Young Adult" (book/film genre) are not adult content, but "Adult Swim XXX" still matches.
 */
class AdultGroupSuggester(
    keywords: Set<String> = DEFAULT_KEYWORDS,
    exclusions: List<String> = DEFAULT_EXCLUSIONS,
) {
    private val keywords: Set<String> = keywords.mapTo(mutableSetOf()) { normalize(it) }
    private val exclusions: List<List<String>> = exclusions.map { words(normalize(it)) }.filter { it.isNotEmpty() }

    /** The matched keyword, or null when [name] does not look adult. */
    fun match(name: String): String? {
        if (name.contains(NO_UNDER_18_EMOJI)) return NO_UNDER_18_EMOJI
        val words = withoutExcludedPhrases(words(normalize(name)))
        for (word in words) {
            if (AGE_MARKER.matches(word)) return "18+"
            val bare = word.trim('+')
            if (bare in keywords) return bare
        }
        for (phrase in multiWordKeywords) {
            if (containsPhrase(words, phrase)) return phrase.joinToString(" ")
        }
        return null
    }

    fun isAdult(name: String): Boolean = match(name) != null

    /** Suggestions for [candidates] not already in [alreadyLocked], in input order. */
    fun suggest(
        candidates: List<GroupCandidate>,
        alreadyLocked: Set<LockedGroup> = emptySet(),
    ): List<GroupSuggestion> = candidates.mapNotNull { candidate ->
        val group = LockedGroup(candidate.playlistId, candidate.groupKey)
        if (group in alreadyLocked) return@mapNotNull null
        match(candidate.displayName)?.let { GroupSuggestion(group, candidate.displayName, it) }
    }

    private val multiWordKeywords: List<List<String>> =
        this.keywords.map(::words).filter { it.size > 1 }

    /** Drops words covered by an exclusion phrase; the remainder is still matched ("Adult Swim XXX"). */
    private fun withoutExcludedPhrases(words: List<String>): List<String> {
        if (exclusions.isEmpty()) return words
        val covered = BooleanArray(words.size)
        for (phrase in exclusions) {
            for (start in 0..words.size - phrase.size) {
                if (phrase.indices.all { words[start + it] == phrase[it] }) {
                    phrase.indices.forEach { covered[start + it] = true }
                }
            }
        }
        // An empty separator keeps phrases from spanning a removed exclusion.
        return words.mapIndexed { i, w -> if (covered[i]) "" else w }
    }

    private fun containsPhrase(words: List<String>, phrase: List<String>): Boolean {
        if (phrase.size > words.size) return false
        return (0..words.size - phrase.size).any { start ->
            phrase.indices.all { words[start + it] == phrase[it] }
        }
    }

    companion object {
        private const val NO_UNDER_18_EMOJI = "🔞" // 🔞
        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}+]+")
        private val AGE_MARKER = Regex("\\+?18\\+|\\+18")

        val DEFAULT_KEYWORDS: Set<String> = setOf(
            "adult", "adults", "for adults", "xxx", "porn", "porno", "erotic", "erotica",
            "adulte", "adultes", "adulto", "adultos", "adulti", "erwachsene",
        )

        val DEFAULT_EXCLUSIONS: List<String> = listOf("adult swim", "young adult", "young adults")

        internal fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replace(COMBINING_MARKS, "")
                .lowercase(Locale.ROOT)

        private fun words(normalized: String): List<String> =
            normalized.split(SEPARATORS).filter { it.isNotEmpty() && it != "+" }
    }
}

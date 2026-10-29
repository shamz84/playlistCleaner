package app.playlistplayer.feature.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class AdultGroupSuggesterTest {

    private val suggester = AdultGroupSuggester()

    @Test
    fun `finds adult keywords in mixed case emoji and decorated names`() {
        listOf(
            "ADULTS",
            "For Adults",
            "XXX",
            "🔞 Night",          // 🔞
            "VIP | 18+ 🔥",       // 18+ 🔥
            "+18 Channels",
            "ᴬᴰᵁᴸᵀ ✨",                     // superscript letters fold via NFKD
            "ＡＤＵＬＴ",                     // fullwidth
            "𝐀𝐃𝐔𝐋𝐓", // mathematical bold ADULT
            "FR: Adultes",
            "Erotica Movies",
            "UK | xXx Premium",
            "Adult+",
        ).forEach { assertWithMessage(it).that(suggester.isAdult(it)).isTrue() }
    }

    @Test
    fun `ignores look-alikes and family content`() {
        listOf(
            "Adultswim",          // one word: not the keyword "adult"
            "Adult Swim",         // Cartoon Network block, excluded phrase
            "ADULT SWIM 🎬",
            "Young Adult Movies", // genre
            "Sussex",
            "Essex Radio",
            "Formula 1 2018",
            "UK 1080p",
            "Kids 3+",
            "Sports 180",
            "Pornichet",          // French town, not "porn"
            "",
        ).forEach { assertWithMessage(it).that(suggester.isAdult(it)).isFalse() }
    }

    @Test
    fun `an excluded phrase does not shield other keywords`() {
        assertThat(suggester.match("Adult Swim XXX")).isEqualTo("xxx")
    }

    @Test
    fun `reports the matched keyword`() {
        assertThat(suggester.match("For Adults")).isEqualTo("adults")
        assertThat(suggester.match("VIP 18+")).isEqualTo("18+")
        assertThat(suggester.match("XXX")).isEqualTo("xxx")
    }

    @Test
    fun `suggests only and skips groups already locked`() {
        val candidates = listOf(
            GroupCandidate(1, "g-adult", "🔞 ADULT"),
            GroupCandidate(1, "g-news", "UK News"),
            GroupCandidate(2, "g-xxx", "XXX"),
            GroupCandidate(2, "g-adult2", "For Adults"),
        )
        val suggestions = suggester.suggest(candidates, alreadyLocked = setOf(LockedGroup(2, "g-xxx")))
        assertThat(suggestions.map { it.group }).containsExactly(
            LockedGroup(1, "g-adult"),
            LockedGroup(2, "g-adult2"),
        ).inOrder()
    }

    @Test
    fun `custom keywords and exclusions are normalised`() {
        val custom = AdultGroupSuggester(keywords = setOf("Late Night"), exclusions = emptyList())
        assertThat(custom.isAdult("LATE NIGHT ✨")).isTrue()
        assertThat(custom.isAdult("Late")).isFalse()
        assertThat(custom.isAdult("Adult Swim")).isFalse()
    }
}

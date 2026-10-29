package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FailoverControllerTest {

    @Test
    fun preferredFirst_thenAlternativesInGivenOrder() {
        val controller = FailoverController(listOf(live("a"), live("b"), live("c")))
        assertThat(controller.preferred.id).isEqualTo("a")
        assertThat(controller.next()?.id).isEqualTo("b")
        assertThat(controller.next()?.id).isEqualTo("c")
        assertThat(controller.next()).isNull()
        assertThat(controller.failoverCount).isEqualTo(2)
        assertThat(controller.triedCount).isEqualTo(3)
    }

    @Test
    fun atMostFiveOtherVersions() {
        val controller = FailoverController((0..9).map { live("s$it") })
        assertThat(controller.alternatives.map { it.id }).containsExactly("s1", "s2", "s3", "s4", "s5").inOrder()
    }

    @Test
    fun duplicatesAndTheSelectedVersionAreDropped() {
        val controller = FailoverController(listOf(live("a"), live("a"), live("b"), live("b"), live("c")))
        assertThat(controller.alternatives.map { it.id }).containsExactly("b", "c").inOrder()
    }

    @Test
    fun maxFailoversIsConfigurable() {
        val controller = FailoverController((0..9).map { live("s$it") }, maxFailovers = 2)
        assertThat(controller.alternatives).hasSize(2)
    }
}

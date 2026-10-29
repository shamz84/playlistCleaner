package app.playlistplayer.core.consolidation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConsolidationModuleTest {
    @Test
    fun moduleCompilesAndTestsRun() {
        assertThat(ConsolidationModule.NAME).isEqualTo(":core:consolidation")
    }
}

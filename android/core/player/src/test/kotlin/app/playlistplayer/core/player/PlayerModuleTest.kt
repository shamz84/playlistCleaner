package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerModuleTest {
    @Test
    fun moduleCompilesAndTestsRun() {
        assertThat(PlayerModule.NAME).isEqualTo(":core:player")
    }
}

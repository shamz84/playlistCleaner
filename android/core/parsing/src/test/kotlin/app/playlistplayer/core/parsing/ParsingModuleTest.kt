package app.playlistplayer.core.parsing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParsingModuleTest {
    @Test
    fun moduleCompilesAndTestsRun() {
        assertThat(ParsingModule.NAME).isEqualTo(":core:parsing")
    }
}

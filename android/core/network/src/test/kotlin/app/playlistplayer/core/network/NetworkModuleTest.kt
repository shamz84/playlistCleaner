package app.playlistplayer.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkModuleTest {
    @Test
    fun moduleCompilesAndTestsRun() {
        assertThat(NetworkModule.NAME).isEqualTo(":core:network")
    }
}

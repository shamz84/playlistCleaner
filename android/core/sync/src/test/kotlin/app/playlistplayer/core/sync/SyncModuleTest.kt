package app.playlistplayer.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncModuleTest {
    @Test
    fun moduleCompilesAndTestsRun() {
        assertThat(SyncModule.NAME).isEqualTo(":core:sync")
    }
}

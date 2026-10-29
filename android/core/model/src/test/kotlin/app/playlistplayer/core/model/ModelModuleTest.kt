package app.playlistplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelModuleTest {
    @Test
    fun moduleCompilesAndTestsRun() {
        assertThat(ModelModule.NAME).isEqualTo(":core:model")
    }
}

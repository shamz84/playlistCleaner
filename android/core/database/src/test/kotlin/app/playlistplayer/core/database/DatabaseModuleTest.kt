package app.playlistplayer.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DatabaseModuleTest {
    @Test
    fun moduleCompilesAndTestsRun() {
        assertThat(DatabaseModule.NAME).isEqualTo(":core:database")
    }
}

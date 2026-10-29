package app.playlistplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecretToStringTest {
    @Test
    fun accountToStringHidesSecrets() {
        val account = XtreamAccount("http", "example.invalid", 8080, "alice", "s3cret")
        val text = account.toString()
        assertThat(text).doesNotContain("example.invalid")
        assertThat(text).doesNotContain("alice")
        assertThat(text).doesNotContain("s3cret")
        assertThat(text).doesNotContain("8080")
        assertThat(account.baseUrl).isEqualTo("http://example.invalid:8080")
    }

    @Test
    fun sourceReferenceToStringHidesSecrets() {
        assertThat(SourceReference.Drive("FILEID123", "RK").toString()).doesNotContain("FILEID123")
        assertThat(SourceReference.Drive("FILEID123", "RK").toString()).doesNotContain("RK")
        assertThat(SourceReference.M3uUrl("http://example.invalid/get.php?username=u&password=p").toString())
            .doesNotContain("example.invalid")
    }
}

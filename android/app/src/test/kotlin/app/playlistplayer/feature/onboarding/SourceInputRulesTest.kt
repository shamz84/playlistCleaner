package app.playlistplayer.feature.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceInputRulesTest {

    // Synthetic IDs only (never real Drive file IDs).
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123-_9" // 34 chars

    // --- Share-link extraction ---

    @Test
    fun `file d view link yields the id`() {
        val parsed = DriveLinkParser.parse("https://drive.google.com/file/d/$id/view?usp=sharing")
        assertThat(parsed).isEqualTo(DriveFieldInput.Parsed(id, resourceKey = null, fromLink = true))
    }

    @Test
    fun `file d link with resourcekey yields id and key`() {
        val parsed = DriveLinkParser.parse("https://drive.google.com/file/d/$id/view?usp=drive_link&resourcekey=0-AbC_dEf")
        assertThat(parsed).isEqualTo(DriveFieldInput.Parsed(id, resourceKey = "0-AbC_dEf", fromLink = true))
    }

    @Test
    fun `file u 0 d link yields the id`() {
        val parsed = DriveLinkParser.parse("https://drive.google.com/file/u/0/d/$id/view")
        assertThat((parsed as DriveFieldInput.Parsed).id).isEqualTo(id)
    }

    @Test
    fun `open id link yields the id`() {
        val parsed = DriveLinkParser.parse("https://drive.google.com/open?id=$id")
        assertThat(parsed).isEqualTo(DriveFieldInput.Parsed(id, resourceKey = null, fromLink = true))
    }

    @Test
    fun `uc id link yields the id with either parameter order`() {
        assertThat((DriveLinkParser.parse("https://drive.google.com/uc?id=$id&export=download") as DriveFieldInput.Parsed).id)
            .isEqualTo(id)
        assertThat((DriveLinkParser.parse("https://drive.google.com/uc?export=download&id=$id") as DriveFieldInput.Parsed).id)
            .isEqualTo(id)
    }

    @Test
    fun `resourcekey parameter name is case insensitive and works on open links`() {
        val parsed = DriveLinkParser.parse("https://drive.google.com/open?id=$id&ResourceKey=0-key")
        assertThat(parsed).isEqualTo(DriveFieldInput.Parsed(id, resourceKey = "0-key", fromLink = true))
    }

    @Test
    fun `link without scheme and with surrounding whitespace is accepted`() {
        val parsed = DriveLinkParser.parse("  drive.google.com/file/d/$id/view  ")
        assertThat((parsed as DriveFieldInput.Parsed).id).isEqualTo(id)
    }

    @Test
    fun `bare id is passed through, trimmed`() {
        assertThat(DriveLinkParser.parse("  $id ")).isEqualTo(DriveFieldInput.Parsed(id, resourceKey = null, fromLink = false))
    }

    @Test
    fun `non google link is unrecognised`() {
        assertThat(DriveLinkParser.parse("https://example.com/file/d/$id/view")).isEqualTo(DriveFieldInput.UnrecognisedLink)
    }

    @Test
    fun `google link without an id is unrecognised`() {
        assertThat(DriveLinkParser.parse("https://drive.google.com/drive/my-drive")).isEqualTo(DriveFieldInput.UnrecognisedLink)
        assertThat(DriveLinkParser.parse("https://drive.google.com/open?id=")).isEqualTo(DriveFieldInput.UnrecognisedLink)
    }

    @Test
    fun `blank input is empty`() {
        assertThat(DriveLinkParser.parse("   ")).isEqualTo(DriveFieldInput.Empty)
    }

    @Test
    fun `parsed input never reveals the id in toString`() {
        assertThat(DriveLinkParser.parse(id).toString()).doesNotContain(id)
        assertThat(SourceInput.DriveId(id, "0-key").toString()).doesNotContain(id)
        assertThat(SourceInput.M3uUrl("http://h/get.php?username=u&password=p").toString()).doesNotContain("password")
    }

    // --- Drive ID validation ---

    @Test
    fun `id length bounds are 25 to 60 inclusive`() {
        assertThat(DriveIdRules.validate("a".repeat(24))).isEqualTo(DriveIdValidation.TooShort)
        assertThat(DriveIdRules.validate("a".repeat(25))).isEqualTo(DriveIdValidation.Valid)
        assertThat(DriveIdRules.validate("a".repeat(60))).isEqualTo(DriveIdValidation.Valid)
        assertThat(DriveIdRules.validate("a".repeat(61))).isEqualTo(DriveIdValidation.TooLong)
    }

    @Test
    fun `id allows letters digits dash and underscore only`() {
        assertThat(DriveIdRules.validate(id)).isEqualTo(DriveIdValidation.Valid)
        assertThat(DriveIdRules.validate(id.dropLast(1) + " ")).isEqualTo(DriveIdValidation.InvalidCharacters)
        assertThat(DriveIdRules.validate(id.dropLast(1) + ".")).isEqualTo(DriveIdValidation.InvalidCharacters)
        assertThat(DriveIdRules.validate(id.dropLast(1) + "é")).isEqualTo(DriveIdValidation.InvalidCharacters)
    }

    @Test
    fun `invalid characters are reported before length`() {
        assertThat(DriveIdRules.validate("ab!")).isEqualTo(DriveIdValidation.InvalidCharacters)
    }

    @Test
    fun `empty id and unrecognised link map to their own validations`() {
        assertThat(DriveIdRules.validate("")).isEqualTo(DriveIdValidation.Empty)
        assertThat(DriveIdRules.validate(DriveFieldInput.Empty)).isEqualTo(DriveIdValidation.Empty)
        assertThat(DriveIdRules.validate(DriveFieldInput.UnrecognisedLink)).isEqualTo(DriveIdValidation.UnrecognisedLink)
    }

    @Test
    fun `display grouping splits into groups of four`() {
        assertThat(DriveIdRules.groupForDisplay("1AbCdEfGhI")).isEqualTo("1AbC dEfG hI")
        assertThat(DriveIdRules.groupForDisplay("")).isEqualTo("")
        assertThat(DriveIdRules.groupForDisplay("abcdef", groupSize = 3)).isEqualTo("abc def")
    }

    // --- M3U URL validation ---

    @Test
    fun `http and https urls with a host are valid`() {
        assertThat(M3uUrlRules.validate("http://example.test/get.php?username=u&password=p&type=m3u_plus"))
            .isEqualTo(M3uUrlValidation.Valid)
        assertThat(M3uUrlRules.validate("HTTPS://example.test:8080/list.m3u")).isEqualTo(M3uUrlValidation.Valid)
        assertThat(M3uUrlRules.validate("  https://my_host.example.test/list.m3u  ")).isEqualTo(M3uUrlValidation.Valid)
    }

    @Test
    fun `non http schemes and scheme-less input are rejected`() {
        assertThat(M3uUrlRules.validate("ftp://example.test/list.m3u")).isEqualTo(M3uUrlValidation.NotHttp)
        assertThat(M3uUrlRules.validate("example.test/list.m3u")).isEqualTo(M3uUrlValidation.NotHttp)
        assertThat(M3uUrlRules.validate("file:///sdcard/list.m3u")).isEqualTo(M3uUrlValidation.NotHttp)
    }

    @Test
    fun `http without host or with illegal characters is malformed`() {
        assertThat(M3uUrlRules.validate("http://")).isEqualTo(M3uUrlValidation.Malformed)
        assertThat(M3uUrlRules.validate("http:///list.m3u")).isEqualTo(M3uUrlValidation.Malformed)
        assertThat(M3uUrlRules.validate("http://exa mple.test/list.m3u")).isEqualTo(M3uUrlValidation.Malformed)
    }

    @Test
    fun `empty url is empty`() {
        assertThat(M3uUrlRules.validate(" ")).isEqualTo(M3uUrlValidation.Empty)
    }
}

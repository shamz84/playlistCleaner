package app.playlistplayer.core.consolidation

import app.playlistplayer.core.consolidation.QualityTier.FHD
import app.playlistplayer.core.consolidation.QualityTier.HD
import app.playlistplayer.core.consolidation.QualityTier.UHD
import app.playlistplayer.core.consolidation.QualityTier.UHD_8K
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionComparatorTest {

    private fun rank(prefs: VersionPreferences, vararg versions: Version): List<String> =
        versions.sortedWith(VersionComparator(prefs)).map { it.id }

    private fun live(id: String, name: String, order: Int) =
        Version.from(id, TitleTokenizer.tokenize(name, TokenizeMode.LIVE), order)

    @Test
    fun tierRankingIsExplicit() {
        val tiers = QualityTier.entries.sortedByDescending { it.rank }
        assertThat(tiers).containsExactly(
            UHD_8K, UHD, QualityTier.RAW, FHD, HD, QualityTier.SD, QualityTier.UNLABELED, QualityTier.LOW_SOURCE,
        ).inOrder()
        val versions = QualityTier.entries.map { Version(id = it.name, quality = it) }.shuffled(java.util.Random(7))
        assertThat(versions.sortedWith(VersionComparator()).map { it.id }).isEqualTo(tiers.map { it.name })
    }

    @Test
    fun manualPickBeatsEverything() {
        val prefs = VersionPreferences(manualPickId = "fr-sd")
        val order = rank(
            prefs,
            Version("en-4k", language = "en", quality = UHD),
            Version("fr-sd", language = "fr", quality = QualityTier.SD, isBackup = true),
        )
        assertThat(order).containsExactly("fr-sd", "en-4k").inOrder()
    }

    @Test
    fun languageListPositionBeatsQuality() {
        val prefs = VersionPreferences(languages = listOf("fr", "en"))
        val order = rank(
            prefs,
            Version("en-4k", language = "en", quality = UHD),
            Version("unknown-8k", language = null, quality = UHD_8K),
            Version("de-4k", language = "de", quality = UHD),
            Version("fr-hd", language = "fr", quality = HD),
        )
        // Listed languages in order, then unknown, then known-but-unlisted.
        assertThat(order).containsExactly("fr-hd", "en-4k", "unknown-8k", "de-4k").inOrder()
    }

    @Test
    fun languageTagsCompareCaseInsensitively() {
        val order = rank(
            VersionPreferences(languages = listOf("EN")),
            Version("fr", language = "fr", quality = UHD),
            Version("en", language = "En", quality = HD),
        )
        assertThat(order.first()).isEqualTo("en")
    }

    @Test
    fun capabilityCapOn1080pSdrDeviceDemotes4kHdr() {
        val device = DeviceCapabilities(maxVideoHeight = 1080, supportsHdr = false)
        val prefs = VersionPreferences(device = device)
        val uhdHdr = live("uhd-hdr", NameFixtures.liveName("Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ"), 0)
        val uhd50 = live("uhd-50", NameFixtures.liveName("Sky Sports Main Event UHD 50fps"), 1)
        val fhd50 = live("fhd-50", NameFixtures.liveName("Sky Sports Main Event 1080p50"), 2)
        val hd = live("hd", NameFixtures.liveName("Sky Sports Main Event ᴴᴰ"), 3)
        val hdrOnly = live("hdr-only", NameFixtures.liveName("Sky Sports 1 ᴴᴰᴿ"), 4)

        assertThat(device.fits(uhdHdr)).isFalse()
        assertThat(device.fits(fhd50)).isTrue()
        assertThat(rank(prefs, uhdHdr, uhd50, fhd50, hd, hdrOnly))
            .containsExactly("fhd-50", "hd", "uhd-50", "uhd-hdr", "hdr-only").inOrder()
        // Same versions on an unrestricted device: quality decides.
        assertThat(rank(VersionPreferences(), uhdHdr, uhd50, fhd50, hd))
            .containsExactly("uhd-hdr", "uhd-50", "fhd-50", "hd").inOrder()
    }

    @Test
    fun capabilityCapDemotes8kOn4kDeviceAndHevcWithoutDecoder() {
        val uhd4k = DeviceCapabilities(maxVideoHeight = 2160)
        assertThat(
            rank(VersionPreferences(device = uhd4k), Version("8k", quality = UHD_8K), Version("4k", quality = UHD)),
        ).containsExactly("4k", "8k").inOrder()

        val noHevc = DeviceCapabilities(supportsHevc = false)
        assertThat(
            rank(
                VersionPreferences(device = noHevc),
                Version("hevc-4k", quality = UHD, codec = "hevc"),
                Version("avc-fhd", quality = FHD),
            ),
        ).containsExactly("avc-fhd", "hevc-4k").inOrder()
    }

    @Test
    fun capabilityCapComesAfterLanguage() {
        val device = DeviceCapabilities(maxVideoHeight = 1080)
        val order = rank(
            VersionPreferences(device = device),
            Version("fr-fhd", language = "fr", quality = FHD),
            Version("en-4k", language = "en", quality = UHD),
        )
        assertThat(order).containsExactly("en-4k", "fr-fhd").inOrder()
    }

    @Test
    fun nonBackupBeatsBackupWithinTierButNotAcrossTiers() {
        val order = rank(
            VersionPreferences(),
            Version("uhd-backup", quality = UHD, isBackup = true, hdr = true, providerOrder = 0),
            Version("fhd", quality = FHD, providerOrder = 1),
            Version("uhd", quality = UHD, providerOrder = 2),
        )
        assertThat(order).containsExactly("uhd", "uhd-backup", "fhd").inOrder()
    }

    @Test
    fun withinTierHdrThenFpsThenProviderOrder() {
        val order = rank(
            VersionPreferences(),
            Version("fhd25", quality = FHD, fps = 25, providerOrder = 0),
            Version("fhd-b", quality = FHD, providerOrder = 2),
            Version("fhd50", quality = FHD, fps = 50, providerOrder = 3),
            Version("fhd-a", quality = FHD, providerOrder = 1),
        )
        assertThat(order).containsExactly("fhd50", "fhd25", "fhd-a", "fhd-b").inOrder()

        val sdrDevice = VersionPreferences(device = DeviceCapabilities(supportsHdr = false))
        // HDR is not a tie-break on an SDR device (it is demoted by the cap instead).
        assertThat(
            rank(
                VersionPreferences(),
                Version("sdr", quality = UHD, providerOrder = 0),
                Version("hdr", quality = UHD, hdr = true, providerOrder = 1),
            ),
        ).containsExactly("hdr", "sdr").inOrder()
        assertThat(
            rank(
                sdrDevice,
                Version("sdr", quality = UHD, providerOrder = 1),
                Version("hdr", quality = UHD, hdr = true, providerOrder = 0),
            ),
        ).containsExactly("sdr", "hdr").inOrder()
    }

    @Test
    fun unlabeledRanksBelowSd() {
        assertThat(
            rank(VersionPreferences(), Version("plain"), Version("sd", quality = QualityTier.SD)),
        ).containsExactly("sd", "plain").inOrder()
    }
}

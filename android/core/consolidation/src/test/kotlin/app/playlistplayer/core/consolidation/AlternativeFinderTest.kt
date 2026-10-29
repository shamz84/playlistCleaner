package app.playlistplayer.core.consolidation

import app.playlistplayer.core.consolidation.NameFixtures.liveName
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class AlternativeFinderTest {

    private var order = 0

    private fun entry(name: String, tvgId: String?, playlist: String = "p1", id: String = name) =
        LiveEntry(id = id, playlistId = playlist, name = name, tvgId = tvgId, providerOrder = order++)

    private fun AlternativeFinder.namesFor(e: LiveEntry, prefs: VersionPreferences = VersionPreferences()) =
        alternativesFor(e, prefs).map { it.name }

    @Test
    fun bbcOneEntriesStayListedAndRankOtherVersions() {
        val london = entry(liveName("BBC One London"), "bbcone.uk")
        val backup = entry("BBC One (Backup)", "bbcone.uk")
        val uhd = entry("BBC One 4K", "bbcone.uk")
        val all = listOf(london, backup, uhd)
        val finder = AlternativeFinder(all)

        assertThat(finder.namesFor(london)).containsExactly("BBC One 4K", "BBC One (Backup)").inOrder()
        assertThat(finder.namesFor(uhd)).containsExactly("BBC One London", "BBC One (Backup)").inOrder()
        // The finder never removes or merges entries: every input is still addressable.
        assertThat(all.map { finder.versionOf(it).id }).containsExactly(london.id, backup.id, uhd.id)
    }

    @Test
    fun regionalSiblingsSharingTvgIdAreNotVersionsOfEachOther() {
        val london = entry(liveName("BBC One London"), "bbcone.uk")
        val midlands = entry(liveName("BBC One East Midlands"), "bbcone.uk")
        val main = entry("BBC One", "bbcone.uk")
        val finder = AlternativeFinder(listOf(london, midlands, main))
        assertThat(finder.namesFor(london)).containsExactly("BBC One")
        assertThat(finder.namesFor(midlands)).containsExactly("BBC One")
        assertThat(finder.namesFor(main)).containsExactly("BBC One London", "BBC One East Midlands").inOrder()
    }

    @Test
    fun skySportsMainEvent1080p50ListsUhdFromOtherGroupFirst() {
        val tvg = "SkySpMainEvent.uk"
        // Real names; the first three sit in a "4K Channels" group, the rest in "Sky Sports".
        val family = listOf(
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ",
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ (Backup)",
            "Sky Sports Main Event UHD 50fps",
            "Sky Sports Main Event ᴴᴰ",
            "Sky Sports Main Event 1080p50",
            "Sky Sports Main Event 1080p25",
        ).map { entry(liveName(it), tvg) }
        val finder = AlternativeFinder(family)
        val fhd50 = family[4]

        assertThat(finder.namesFor(fhd50)).containsExactly(
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ",
            "Sky Sports Main Event UHD 50fps",
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ (Backup)",
            "Sky Sports Main Event 1080p25",
            "Sky Sports Main Event ᴴᴰ",
        ).inOrder()
    }

    @Test
    fun capabilityCapOnFhdSdrDeviceDemotesUhdAlternatives() {
        val family = listOf(
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ",
            "Sky Sports Main Event UHD 50fps",
            "Sky Sports Main Event 1080p50",
            "Sky Sports Main Event 1080p25",
            "Sky Sports Main Event ᴴᴰ",
        ).map { entry(liveName(it), "SkySpMainEvent.uk") }
        val finder = AlternativeFinder(family)
        val prefs = VersionPreferences(device = DeviceCapabilities(maxVideoHeight = 1080, supportsHdr = false))
        assertThat(finder.namesFor(family[2], prefs)).containsExactly(
            "Sky Sports Main Event 1080p25",
            "Sky Sports Main Event ᴴᴰ",
            "Sky Sports Main Event UHD 50fps",
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ",
        ).inOrder()
    }

    @Test
    fun eightMatchesReturnOnlyTheTopFive() {
        val names = listOf(
            "Sky Sports Main Event 1080p25", // the entry we look up
            "Sky Sports Main Event ᴴᴰ",
            "Sky Sports Main Event 720p25",
            "Sky Sports Main Event SD",
            "Sky Sports Main Event",
            "Sky Sports Main Event 1080p50",
            "Sky Sports Main Event UHD 50fps",
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ",
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ (Backup)",
        )
        val entries = names.map { entry(it, "SkySpMainEvent.uk") }
        val result = AlternativeFinder(entries).namesFor(entries[0])
        assertThat(result).hasSize(AlternativeFinder.MAX_ALTERNATIVES)
        assertThat(result).containsExactly(
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ",
            "Sky Sports Main Event UHD 50fps",
            "Sky Sports Main Event ᵁᴴᴰ ᴴᴰᴿ (Backup)",
            "Sky Sports Main Event 1080p50",
            "Sky Sports Main Event 720p25", // HD tier; 25fps beats the unlabelled-fps ᴴᴰ entry
        ).inOrder()
    }

    @Test
    fun plusOneChannelsAreNeverOtherVersions() {
        val ch4 = entry(liveName("Channel 4"), "channel4.uk")
        val ch4London = entry(liveName("Channel 4 London"), "channel4.uk")
        val ch4PlusOne = entry(liveName("Channel 4 +1"), "channel4.uk")
        val ch4PlusOneHd = entry("Channel 4 +1 HD", "channel4.uk")
        val finder = AlternativeFinder(listOf(ch4, ch4London, ch4PlusOne, ch4PlusOneHd))

        assertThat(finder.namesFor(ch4)).containsExactly("Channel 4 London")
        assertThat(finder.namesFor(ch4PlusOne)).containsExactly("Channel 4 +1 HD")
        // Empty tvg-id with identical names: still never a version across the +1 line.
        val e4 = entry(liveName("E4"), null)
        val e4PlusOne = entry(liveName("E4 +1"), null)
        assertThat(AlternativeFinder(listOf(e4, e4PlusOne)).namesFor(e4)).isEmpty()
    }

    @Test
    fun emptyTvgIdMatchesOnlyIdenticalNormalizedNames() {
        val tnt = entry(liveName("TNT"), null)
        val tntLower = entry(liveName("Tnt"), "")
        val tntSports = entry(liveName("TNT Sports 1"), null)
        val yes = entry(liveName("YES Network"), null)
        val yesLower = entry(liveName("Yes Network"), null)
        val yesShort = entry(liveName("Yes"), null)
        val finder = AlternativeFinder(listOf(tnt, tntLower, tntSports, yes, yesLower, yesShort))

        assertThat(finder.namesFor(tnt)).containsExactly("Tnt")
        assertThat(finder.namesFor(yes)).containsExactly("Yes Network")
        assertThat(finder.namesFor(yesShort)).isEmpty()
        assertThat(finder.namesFor(tntSports)).isEmpty()
    }

    @Test
    fun emptyTvgIdEntryMatchesIdenticalNameThatHasATvgId() {
        val tagged = entry(liveName("NBA TV"), "nbatv.us")
        val untagged = entry(liveName("NBA TV (Backup)"), null)
        val finder = AlternativeFinder(listOf(tagged, untagged))
        assertThat(finder.namesFor(tagged)).containsExactly("NBA TV (Backup)")
        assertThat(finder.namesFor(untagged)).containsExactly("NBA TV")
    }

    @Test
    fun differentTvgIdsWithSameNameAreNotVersions() {
        val a = entry(liveName("TBS"), "tbs.us")
        val b = entry(liveName("Tbs"), "tbs.ca")
        assertThat(AlternativeFinder(listOf(a, b)).namesFor(a)).isEmpty()
    }

    @Test
    fun matchingEntriesInADifferentPlaylistAreNeverReturned() {
        val mine = entry(liveName("ESPN"), "espn.us", playlist = "p1")
        val mineBackup = entry(liveName("ESPN (Backup)"), "espn.us", playlist = "p1")
        val other720 = entry(liveName("ESPN (720p)"), "espn.us", playlist = "p2")
        val otherSameId = entry(liveName("ESPN (Backup)"), "espn.us", playlist = "p2", id = mineBackup.id)
        val finder = AlternativeFinder(listOf(mine, mineBackup, other720, otherSameId))

        val result = finder.alternativesFor(mine)
        assertThat(result).containsExactly(mineBackup)
        assertThat(result.map { it.playlistId }).containsExactly("p1")
        assertThat(finder.alternativesFor(other720).map { it.playlistId }).containsExactly("p2")
    }

    @Test
    fun countryPrefixIsRegionAndKeepsCountriesApart() {
        val ukDisney = entry(liveName("UK: DISNEY JR"), null)
        val usDisney = entry("US: DISNEY JR", null)
        val plainDisney = entry("Disney Jr", null)
        val finder = AlternativeFinder(listOf(ukDisney, usDisney, plainDisney))

        assertThat(finder.tokensOf(ukDisney).language).isNull()
        assertThat(finder.tokensOf(ukDisney).region).isEqualTo("uk")
        assertThat(finder.namesFor(ukDisney)).containsExactly("Disney Jr")
        assertThat(finder.namesFor(plainDisney)).containsExactly("UK: DISNEY JR", "US: DISNEY JR").inOrder()
        // A region prefix is not a language, so an English preference does not reorder it.
        val en = VersionPreferences(languages = listOf("en"))
        assertThat(finder.namesFor(plainDisney, en)).containsExactly("UK: DISNEY JR", "US: DISNEY JR").inOrder()
    }

    @Test
    fun separatorsAreNeverVersions() {
        val header = entry(liveName("### SPORTS ###"), null)
        val sports = entry("Sports", null)
        val finder = AlternativeFinder(listOf(header, sports))
        assertThat(finder.namesFor(sports)).isEmpty()
        assertThat(finder.namesFor(header)).isEmpty()
    }

    @Test
    fun unknownPlaylistOrUnindexedEntryIsSafe() {
        val finder = AlternativeFinder(listOf(entry("ESPN", null)))
        assertThat(finder.alternativesFor(entry("ESPN", null, playlist = "nope"))).isEmpty()
        assertThat(finder.namesFor(entry("ESPN", null, id = "new-id"))).containsExactly("ESPN")
    }

    @Test
    fun realPlaylistNamesWithoutTvgIdsFindExpectedFamilies() {
        val entries = NameFixtures.live.mapIndexed { i, name -> entry(name, null, id = "i$i") }
        val finder = AlternativeFinder(entries)
        fun alts(name: String) = finder.namesFor(entries.first { it.name == liveName(name) })

        assertThat(alts("ESPN")).containsExactly("ESPN (720p)", "ESPN (Backup)").inOrder()
        assertThat(alts("FS1")).containsExactly("FS1 (1080p)", "FS1 (720p)").inOrder()
        assertThat(alts("TNT Sports 1")).containsExactly("TNT Sports 1 (1080p50)", "TNT Sports 1 (1080p25)").inOrder()
        assertThat(alts("Sky Sports +")).containsExactly("Sky Sports + 1080p50", "Sky Sports + 1080p25").inOrder()
        assertThat(alts("Sky Sports F1 ᴴᴰ")).containsExactly("Sky Sports F1 ᵁᴴᴰ ᴴᴰᴿ", "Sky Sports F1 UHD 50fps").inOrder()
        assertThat(alts("More 4")).containsExactly("More4")
        assertThat(alts("Nick Junior")).isEmpty() // "Nick Junior +1" is never a version
        assertThat(alts("Channel 4")).isEmpty() // "Channel 4 +1" is never a version
        assertThat(alts("Sky Sports Mix")).containsExactly("Sky Sports Mix 1080p50", "Sky Sports Mix 1080p25").inOrder()
    }

    @Test
    fun realPlaylistNamesNeverPairAcrossPlusOne() {
        val entries = NameFixtures.live.mapIndexed { i, name -> entry(name, null, id = "i$i") }
        val finder = AlternativeFinder(entries)
        for (e in entries) {
            val self = finder.tokensOf(e)
            for (alt in finder.alternativesFor(e)) {
                val other = finder.tokensOf(alt)
                assertWithMessage("${e.name} -> ${alt.name}").that(other.timeshiftHours).isEqualTo(self.timeshiftHours)
                assertWithMessage("${e.name} -> ${alt.name}").that(other.isSeparator).isFalse()
            }
        }
    }

    @Test
    fun performanceSmokeIndexesAndQueries15kEntriesQuickly() {
        val names = NameFixtures.live
        val entries = (0 until 50).flatMap { copy ->
            names.mapIndexed { i, name ->
                // tvg-ids shared within a copy by base family, empty for every third copy.
                val tvg = if (copy % 3 == 0) null else "fam-$copy-" + TitleTokenizer.normalize(name).take(12)
                LiveEntry(id = "$copy-$i", playlistId = "p1", name = "$name $copy", tvgId = tvg, providerOrder = copy * 1000 + i)
            }
        }
        assertThat(entries.size).isAtLeast(15_000)

        val start = System.nanoTime()
        val finder = AlternativeFinder(entries)
        val built = System.nanoTime()
        var total = 0
        for (e in entries) total += finder.alternativesFor(e).size
        val done = System.nanoTime()

        val buildMs = (built - start) / 1_000_000
        val queryMs = (done - built) / 1_000_000
        println("AlternativeFinder: ${entries.size} entries, build ${buildMs}ms, ${entries.size} lookups ${queryMs}ms, $total results")
        assertThat(total).isGreaterThan(0)
        assertThat(buildMs + queryMs).isLessThan(15_000L)
    }
}

package app.playlistplayer.core.sync.epg

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.entity.EpgMapMethod
import app.playlistplayer.core.database.entity.EpgMapOrigin
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.ProgrammeSource
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.parsing.xmltv.XmlPullEvent
import app.playlistplayer.core.parsing.xmltv.XmlPullSource
import app.playlistplayer.core.parsing.xmltv.XmlPullSourceFactory
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.DAY
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.HOUR
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.NOW
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.fixture
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.xml
import app.playlistplayer.core.sync.epg.EpgTestEnv.Companion.xmltv
import app.playlistplayer.core.sync.epg.EpgTestEnv.Programme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class EpgSyncerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: EpgTestEnv

    @Before
    fun setUp() {
        env = EpgTestEnv(temp.root)
    }

    @After
    fun tearDown() = env.close()

    private val noBackfill = EpgSyncConfig(backfillDelayMillis = 0)

    private suspend fun all(id: Long, key: String): List<ProgrammeEntity> =
        env.epg.programmes(id, listOf(key), NOW - 10 * DAY, NOW + 10 * DAY)

    private suspend fun allKeys(id: Long): Set<String> =
        env.epg.programmes(id, KEYS_OF_INTEREST, NOW - 10 * DAY, NOW + 10 * DAY).mapTo(HashSet()) { it.channelKey }

    @Test
    fun fixtureImportsOnlyPlaylistChannelsWithinWindowAndStoresMatchRate() = runBlocking<Unit> {
        val id = env.playlist()
        val outcome = env.syncer(noBackfill).sync(id, backfill = false) as EpgSyncOutcome.Imported

        assertThat(outcome.source).isEqualTo(EpgMapOrigin.PROVIDER)
        assertThat(outcome.matchedEntries).isEqualTo(4)
        assertThat(outcome.totalEntries).isEqualTo(5)
        assertThat(allKeys(id)).containsExactly("BBCOneOxford.uk", "bbc1.uk", "BBC2.uk")
        assertThat(env.epg.programmeCount(id)).isEqualTo(45)

        val state = env.epg.state(id)!!
        assertThat(state.matchRate).isWithin(1e-9).of(0.8)
        assertThat(state.matchedCount).isEqualTo(4)
        assertThat(state.providerMatchedCount).isEqualTo(4)
        assertThat(state.lastImportAt).isEqualTo(NOW)
        assertThat(state.lastError).isNull()
        assertThat(state.coverageFrom).isLessThan(NOW - 23 * HOUR)
        assertThat(state.coverageTo).isGreaterThan(NOW + 20 * HOUR)
        assertThat(env.h.store.syncState(id)!!.epgStale).isFalse()

        val methods = env.epg.mappings(id).associate { it.entryKey to it.method }
        assertThat(methods).containsExactly(
            "s:497001", EpgMapMethod.EXACT_ID.name,
            "s:162096", EpgMapMethod.EXACT_ID.name,
            "s:700001", EpgMapMethod.EXACT_ID.name,
            "s:700002", EpgMapMethod.DISPLAY_NAME.name,
        )
        // Fixture programmes are XMLTV, real provider titles, correct UTC instants (+0100 offsets).
        val first = all(id, "bbc1.uk").first()
        assertThat(first.title).isEqualTo("BBC News at Ten")
        assertThat(first.startMillis).isEqualTo(LocalDateTime.of(2026, 10, 28, 22, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        assertThat(first.sourceType).isEqualTo(ProgrammeSource.XMLTV)
    }

    @Test
    fun windowKeepsArchiveDaysBackAndTwoDaysAhead() = runBlocking<Unit> {
        val id = env.playlist()
        env.provider = {
            xml(
                xmltv(
                    listOf("bbc1.uk" to "BBC 1"),
                    listOf(
                        Programme("bbc1.uk", NOW - 5 * DAY, NOW - 5 * DAY + HOUR, "too old"),
                        Programme("bbc1.uk", NOW - 2 * DAY, NOW - 2 * DAY + HOUR, "archive"),
                        Programme("bbc1.uk", NOW + HOUR, NOW + 2 * HOUR, "soon"),
                        Programme("bbc1.uk", NOW + 3 * DAY, NOW + 3 * DAY + HOUR, "too far"),
                    ),
                ),
            )
        }
        env.syncer(noBackfill).sync(id, backfill = false)
        assertThat(all(id, "bbc1.uk").map { it.title }).containsExactly("archive", "soon").inOrder()
    }

    @Test
    fun reimportKeepsPastReplacesFutureAndPrunesOldRows() = runBlocking<Unit> {
        val id = env.playlist()
        fun doc(prefix: String, base: Long) = xml(
            xmltv(
                listOf("bbc1.uk" to "BBC 1"),
                listOf(
                    Programme("bbc1.uk", base - 2 * DAY, base - 2 * DAY + HOUR, "$prefix two days ago"),
                    Programme("bbc1.uk", base - 3 * HOUR, base - 2 * HOUR, "$prefix earlier"),
                    Programme("bbc1.uk", base, base + HOUR, "$prefix now"),
                    Programme("bbc1.uk", base + HOUR, base + 2 * HOUR, "$prefix next"),
                ),
            ),
        )
        env.provider = { doc("A", NOW) }
        env.syncer(noBackfill).sync(id, backfill = false)
        env.provider = { doc("B", NOW) }
        env.syncer(noBackfill).sync(id, backfill = false)

        assertThat(all(id, "bbc1.uk").map { it.title })
            .containsExactly("A two days ago", "A earlier", "B now", "B next").inOrder()

        // Five days later: rows that ended more than maxArchiveDays + 1 = 4 days ago are pruned.
        env.h.now = NOW + 5 * DAY
        env.provider = { doc("C", NOW + 5 * DAY) }
        val outcome = env.syncer(noBackfill).sync(id, backfill = false) as EpgSyncOutcome.Imported
        assertThat(outcome.pruned).isEqualTo(4)
        assertThat(all(id, "bbc1.uk").map { it.title }).containsExactly("C two days ago", "C earlier", "C now", "C next").inOrder()
    }

    @Test
    fun gzipBodyWithoutGzipHeadersIsDetected() = runBlocking<Unit> {
        val id = env.playlist()
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(fixture("xmltv_sample.xml").toByteArray()) } }.toByteArray()
        env.provider = {
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").body(Buffer().write(gz)).build()
        }
        val outcome = env.syncer(noBackfill).sync(id, backfill = false)
        assertThat(outcome).isInstanceOf(EpgSyncOutcome.Imported::class.java)
        assertThat(env.epg.programmeCount(id)).isEqualTo(45)
    }

    @Test
    fun cancellingWhileStagingWaitsForWriterReleasesTheParserAndPreservesPreviousGuide() = runBlocking<Unit> {
        val id = env.playlist()
        env.syncer(noBackfill).sync(id, backfill = false)
        val previous = all(id, "bbc1.uk")
        val parserReached = CountDownLatch(1)
        val continueParsing = CountDownLatch(1)
        val cancellationReached = CountDownLatch(1)
        val writerHeld = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val factory = XmlPullSourceFactory { input ->
            val delegate = AndroidXmlPullSource.open(input)
            object : XmlPullSource by delegate {
                override fun next(): XmlPullEvent {
                    val event = delegate.next()
                    if (event == XmlPullEvent.START_TAG && delegate.name == "programme" && parserReached.count > 0) {
                        parserReached.countDown()
                        check(continueParsing.await(10, TimeUnit.SECONDS))
                    }
                    return event
                }
            }
        }
        val logger = RedactingLogger({ _, _, message, _ ->
            if (message.contains("cancelled while downloading or staging")) cancellationReached.countDown()
        })
        val import = async(Dispatchers.IO) { env.syncer(EpgSyncConfig(batchSize = 1), factory, logger).sync(id, backfill = false) }
        val writer = async(Dispatchers.IO) {
            check(parserReached.await(10, TimeUnit.SECONDS))
            env.h.db.withWriteTransaction {
                writerHeld.complete(Unit)
                releaseWriter.await()
            }
        }
        try {
            assertThat(withContext(Dispatchers.IO) { parserReached.await(10, TimeUnit.SECONDS) }).isTrue()
            withTimeout(10_000) { writerHeld.await() }
            continueParsing.countDown()
            delay(300)
            import.cancel()
            assertThat(withContext(Dispatchers.IO) { cancellationReached.await(10, TimeUnit.SECONDS) }).isTrue()
        } finally {
            continueParsing.countDown()
            releaseWriter.complete(Unit)
            withTimeout(10_000) {
                import.cancelAndJoin()
                writer.await()
            }
        }
        assertThat(env.epg.pendingCount(id)).isEqualTo(0)
        assertThat(all(id, "bbc1.uk")).isEqualTo(previous)
        env.epg.updateState(id) { it.copy(lastErrorKind = null) }
    }

    @Test
    fun entriesSharingATvgIdResolveToTheSameProgrammes() = runBlocking<Unit> {
        val id = env.playlist()
        env.syncer(noBackfill).sync(id, backfill = false)

        assertThat(env.epg.channelKey(id, 1)).isEqualTo("bbc1.uk")
        assertThat(env.epg.channelKey(id, 2)).isEqualTo("bbc1.uk")
        assertThat(all(id, "bbc1.uk")).hasSize(15)
        val grid = env.epg.gridForGroup(id, 0, NOW - 3 * HOUR, NOW + 3 * HOUR)
        val one = grid.filter { it.channelOrdinal == 1 }.map { it.programme.id }
        val two = grid.filter { it.channelOrdinal == 2 }.map { it.programme.id }
        assertThat(one).isNotEmpty()
        assertThat(two).isEqualTo(one)
        // Bluey (ordinal 4) has no guide: no grid rows ("No guide data").
        assertThat(grid.none { it.channelOrdinal == 4 }).isTrue()
    }

    @Test
    fun overrideNotFoundOrNotXmlKeepsPreviousEpgAndRecordsError() = runBlocking<Unit> {
        val id = env.playlist()
        env.syncer(noBackfill).sync(id, backfill = false)
        val before = all(id, "bbc1.uk")
        val rateBefore = env.epg.state(id)!!.matchRate

        env.h.secureStore.putEpgOverrideUrl(id, env.overrideUrl)
        env.override = { MockResponse.Builder().code(404).body("not found").build() }
        val notFound = env.syncer(noBackfill).sync(id, backfill = false) as EpgSyncOutcome.Failed
        assertThat(notFound.kind).isEqualTo(EpgSyncer.KIND_HTTP)
        assertThat(all(id, "bbc1.uk")).isEqualTo(before)
        assertThat(env.epg.state(id)!!.lastErrorKind).isEqualTo(EpgSyncer.KIND_HTTP)
        assertThat(env.epg.state(id)!!.lastError).contains("404")
        assertThat(env.epg.state(id)!!.overrideUrlPresent).isTrue()

        env.override = { xml("<html><body>Login required</body></html>", "text/html") }
        val notXml = env.syncer(noBackfill).sync(id, backfill = false) as EpgSyncOutcome.Failed
        assertThat(notXml.kind).isEqualTo(EpgSyncer.KIND_MALFORMED)
        assertThat(all(id, "bbc1.uk")).isEqualTo(before)
        assertThat(env.epg.state(id)!!.matchRate).isEqualTo(rateBefore)
        assertThat(env.epg.state(id)!!.lastError).doesNotContain(env.server.hostName)
        assertThat(env.epg.pendingCount(id)).isEqualTo(0)
    }

    @Test
    fun malformedXmlMidwayCommitsNothingForThatImport() = runBlocking<Unit> {
        val id = env.playlist()
        env.syncer(noBackfill).sync(id, backfill = false)
        val before = env.epg.programmes(id, KEYS_OF_INTEREST, NOW - 10 * DAY, NOW + 10 * DAY)

        val full = fixture("xmltv_sample.xml").replace("BBC News at Ten", "Changed title")
        val cut = full.substring(0, full.length * 3 / 4)
        env.provider = { xml(cut) }
        val outcome = env.syncer(EpgSyncConfig(batchSize = 5)).sync(id, backfill = false)
        assertThat(outcome).isInstanceOf(EpgSyncOutcome.Failed::class.java)
        assertThat(env.epg.programmes(id, KEYS_OF_INTEREST, NOW - 10 * DAY, NOW + 10 * DAY)).isEqualTo(before)
        assertThat(env.epg.pendingCount(id)).isEqualTo(0)
        assertThat(env.epg.state(id)!!.lastErrorAt).isEqualTo(NOW)
    }

    @Test
    fun overrideFallsBackToProviderAndSwitchingBackReimportsAndUpdatesMatchRate() = runBlocking<Unit> {
        val id = env.playlist()
        env.h.secureStore.putEpgOverrideUrl(id, env.overrideUrl)
        env.override = {
            xml(
                xmltv(
                    listOf("bluey.247" to "Bluey", "bbc1.uk" to "BBC One"),
                    listOf(
                        Programme("bluey.247", NOW, NOW + HOUR, "Override Bluey"),
                        Programme("bbc1.uk", NOW, NOW + HOUR, "Override BBC"),
                    ),
                ),
            )
        }
        val withOverride = env.syncer(noBackfill).sync(id, backfill = false) as EpgSyncOutcome.Imported
        assertThat(withOverride.source).isEqualTo(EpgMapOrigin.OVERRIDE)
        assertThat(withOverride.overrideMatched).isEqualTo(3) // bluey + both bbc1.uk entries
        assertThat(withOverride.providerMatched).isEqualTo(2) // BBC One Oxford (API id) + BBC 2 (name)
        assertThat(env.epg.state(id)!!.matchRate).isWithin(1e-9).of(1.0)
        val origins = env.epg.mappings(id).associate { it.entryKey to it.origin }
        assertThat(origins["s:999001"]).isEqualTo(EpgMapOrigin.OVERRIDE.name)
        assertThat(origins["s:162096"]).isEqualTo(EpgMapOrigin.OVERRIDE.name)
        assertThat(origins["s:497001"]).isEqualTo(EpgMapOrigin.PROVIDER.name)
        assertThat(origins["s:700002"]).isEqualTo(EpgMapOrigin.PROVIDER.name)
        // bbc1.uk was claimed by the override, so provider programmes for it were not imported.
        assertThat(all(id, "bbc1.uk").map { it.title }).containsExactly("Override BBC")
        assertThat(all(id, "BBCOneOxford.uk")).hasSize(15)

        // Back to the provider guide.
        env.h.secureStore.removeEpgOverrideUrl(id)
        val provider = env.syncer(noBackfill).sync(id, backfill = false) as EpgSyncOutcome.Imported
        assertThat(provider.source).isEqualTo(EpgMapOrigin.PROVIDER)
        val state = env.epg.state(id)!!
        assertThat(state.matchRate).isWithin(1e-9).of(0.8)
        assertThat(state.overrideMatchedCount).isEqualTo(0)
        assertThat(state.overrideUrlPresent).isFalse()
        assertThat(env.epg.mappings(id).map { it.entryKey }).doesNotContain("s:999001")
        val futureBbc = all(id, "bbc1.uk").filter { it.startMillis >= NOW - HOUR }
        assertThat(futureBbc.map { it.title }).doesNotContain("Override BBC")
        assertThat(futureBbc).isNotEmpty()
        assertThat(all(id, "bluey.247").filter { it.startMillis >= NOW - HOUR }).isEmpty()
    }

    @Test
    fun backfillFillsOlderArchiveDaysInServerZoneWithoutDuplicatingXmltvSlots() = runBlocking<Unit> {
        val id = env.playlist()
        val outcome = env.syncer().sync(id) as EpgSyncOutcome.Imported
        assertThat(outcome.backfilledChannels).isEqualTo(2) // the two archive streams
        assertThat(env.requests.filter { it.startsWith("get_simple_data_table") })
            .containsExactly("get_simple_data_table:497001", "get_simple_data_table:162096").inOrder()

        val rows = all(id, "BBCOneOxford.uk")
        val archive = rows.filter { it.sourceType == ProgrammeSource.XTREAM_ARCHIVE }
        val xmltv = rows.filter { it.sourceType == ProgrammeSource.XMLTV }
        assertThat(xmltv).hasSize(15)
        assertThat(archive).isNotEmpty()
        // No archive row overlaps an XMLTV slot.
        for (a in archive) {
            assertThat(xmltv.none { it.startMillis < a.stopMillis && it.stopMillis > a.startMillis }).isTrue()
        }
        // All archive rows have ended and lie within the 3-day archive window.
        assertThat(archive.all { it.stopMillis <= NOW && it.startMillis >= NOW - 3 * DAY }).isTrue()
        // Server-local "2026-10-26 23:00:00" (Europe/Amsterdam, CET) is 22:00 UTC; *_timestamp is ignored.
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        val expected = LocalDateTime.of(2026, 10, 26, 23, 0).atZone(amsterdam).toInstant().toEpochMilli()
        assertThat(expected).isEqualTo(LocalDateTime.of(2026, 10, 26, 22, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        assertThat(archive.map { it.startMillis }).contains(expected)
        // Days 2-4: the earliest archive row is about 3 days back, well before the XMLTV coverage.
        assertThat(archive.minOf { it.startMillis }).isLessThan(xmltv.minOf { it.startMillis } - DAY)
        // Entries older than the archive window (2026-10-25) are not stored.
        val oldest = LocalDateTime.of(2026, 10, 25, 12, 0).atZone(amsterdam).toInstant().toEpochMilli()
        assertThat(archive.none { it.startMillis < oldest }).isTrue()
        assertThat(archive.first().title).isNotEmpty()

        // A second run inside the minimum interval does not call the API again and adds nothing.
        val count = env.epg.programmeCount(id)
        env.requests.clear()
        env.syncer().sync(id)
        assertThat(env.requests.filter { it.startsWith("get_simple_data_table") }).isEmpty()
        assertThat(env.epg.programmeCount(id)).isEqualTo(count)
    }

    @Test
    fun backfillIsCappedPerRun() = runBlocking<Unit> {
        val id = env.playlist()
        env.syncer(EpgSyncConfig(backfillDelayMillis = 0, maxBackfillChannelsPerRun = 1)).sync(id)
        assertThat(env.requests.filter { it.startsWith("get_simple_data_table") }).containsExactly("get_simple_data_table:497001")
        env.requests.clear()
        env.syncer(EpgSyncConfig(backfillDelayMillis = 0, maxBackfillChannelsPerRun = 1)).sync(id)
        assertThat(env.requests.filter { it.startsWith("get_simple_data_table") }).containsExactly("get_simple_data_table:162096")
    }

    @Test
    fun xtreamDisabledWithoutOverrideSkipsAndWithOverrideImports() = runBlocking<Unit> {
        val id = env.playlist(xtream = false)
        assertThat(env.syncer().sync(id)).isEqualTo(EpgSyncOutcome.Skipped(EpgSyncer.REASON_NO_SOURCE))
        assertThat(env.requests).isEmpty()

        env.h.secureStore.putEpgOverrideUrl(id, env.overrideUrl)
        env.override = { xml(fixture("xmltv_sample.xml")) }
        val outcome = env.syncer().sync(id) as EpgSyncOutcome.Imported
        assertThat(outcome.providerMatched).isEqualTo(0)
        // No catalog: entry 0 has no API epg id, so only bbc1.uk (x2) and BBC 2 (name) match.
        assertThat(outcome.overrideMatched).isEqualTo(3)
        assertThat(env.requests).containsExactly("/override.xml")
    }

    @Test
    fun episodeNumbersAreDisplayed() {
        val ns = app.playlistplayer.core.parsing.xmltv.XmltvEpisodeNumber("xmltv_ns", "1.4.0/1")
        val onscreen = app.playlistplayer.core.parsing.xmltv.XmltvEpisodeNumber("onscreen", "S02E05")
        assertThat(EpgSyncer.displayEpisode(listOf(ns))).isEqualTo("S2 E5")
        assertThat(EpgSyncer.displayEpisode(listOf(ns, onscreen))).isEqualTo("S02E05")
        assertThat(EpgSyncer.displayEpisode(emptyList())).isNull()
    }

    private companion object {
        val KEYS_OF_INTEREST = listOf(
            "BBCOneOxford.uk", "bbc1.uk", "BBC2.uk", "ITV1.uk", "BBC4.uk", "TS", "uk.Utsav Plus HD",
            "skysportsmainevent.uk", "SkySportsMainEvent.uk", "bluey.247",
        )
    }
}

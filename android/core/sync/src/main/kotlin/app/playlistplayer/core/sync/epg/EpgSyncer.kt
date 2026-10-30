package app.playlistplayer.core.sync.epg

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.entity.ChannelEpgMapEntity
import app.playlistplayer.core.database.entity.ChannelLiveInfo
import app.playlistplayer.core.database.entity.EpgMapMethod
import app.playlistplayer.core.database.entity.EpgMapOrigin
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.ProgrammeSource
import app.playlistplayer.core.database.entity.epgEntryKey
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.Redactor
import app.playlistplayer.core.network.epg.XmltvDownloadException
import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.network.xtream.XtreamException
import app.playlistplayer.core.parsing.xmltv.EpgChannelMatcher
import app.playlistplayer.core.parsing.xmltv.EpgMatchResult
import app.playlistplayer.core.parsing.xmltv.EpgPlaylistChannel
import app.playlistplayer.core.parsing.xmltv.XmlPullSourceFactory
import app.playlistplayer.core.parsing.xmltv.XmltvChannel
import app.playlistplayer.core.parsing.xmltv.XmltvEpisodeNumber
import app.playlistplayer.core.parsing.xmltv.XmltvHandler
import app.playlistplayer.core.parsing.xmltv.XmltvParseException
import app.playlistplayer.core.parsing.xmltv.XmltvParseOptions
import app.playlistplayer.core.parsing.xmltv.XmltvParser
import app.playlistplayer.core.parsing.xmltv.XmltvProgramme
import app.playlistplayer.core.parsing.xmltv.XtreamEpgDecoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** What one EPG sync did. */
sealed interface EpgSyncOutcome {
    val name: String

    data class Imported(
        val source: EpgMapOrigin,
        val matchedEntries: Int,
        val totalEntries: Int,
        val overrideMatched: Int,
        val providerMatched: Int,
        val programmesPublished: Int,
        val pruned: Int,
        val backfilledChannels: Int,
        val backfilledProgrammes: Int,
    ) : EpgSyncOutcome {
        override val name: String get() = "Imported"
        val matchRate: Double get() = if (totalEntries == 0) 0.0 else matchedEntries.toDouble() / totalEntries
    }

    /** Nothing to import: no channels yet, or neither an override nor an Xtream account. */
    data class Skipped(val reason: String) : EpgSyncOutcome {
        override val name: String get() = "Skipped"
    }

    /** Nothing committed; the previous guide stays. [message] is redacted. */
    data class Failed(val kind: String, val message: String, val retryable: Boolean) : EpgSyncOutcome {
        override val name: String get() = "Failed"
    }
}

/** Tunables for [EpgSyncer]; defaults follow the U1 provider measurements. */
data class EpgSyncConfig(
    /** An import replaces the committed guide from `now - importLookBackMillis` onward. */
    val importLookBackMillis: Long = HOUR,
    /** Programmes are kept up to `now + lookAheadMillis`. */
    val lookAheadMillis: Long = 2 * DAY,
    /** Past window for playlists without archive channels. */
    val minPastWindowMillis: Long = DAY,
    val batchSize: Int = 500,
    /** At most this many `get_simple_data_table` calls per run, sequential, in playlist order. */
    val maxBackfillChannelsPerRun: Int = 40,
    val backfillDelayMillis: Long = 300,
    /** A channel is not backfilled again within this interval (its older days may simply be absent). */
    val backfillMinIntervalMillis: Long = 20 * HOUR,
    /** The past window counts as covered when the earliest programme is at most this late. */
    val backfillGapToleranceMillis: Long = 2 * HOUR,
) {
    companion object {
        const val HOUR: Long = 3_600_000L
        const val DAY: Long = 24 * HOUR
    }
}

/**
 * The EPG import pipeline for one playlist, independent of WorkManager.
 *
 * 1. Source: the override URL (SecureStore) when set, else the provider `xmltv.php` of the
 *    playlist's account (Xtream processing on). With an override active and Xtream on, entries the
 *    override does not cover fall back to the provider guide in a second pass.
 * 2. Each pass streams the document (never buffered), keeps every `<channel>` (≈8k, small) so the
 *    matcher can use display names, matches the playlist entries in `onChannelsComplete`, and
 *    then keeps only the matched channels' programmes within
 *    `[now - max(maxArchiveDays, 1 d), now + 2 d)`, staged in batches under one import id.
 * 3. Commit (one transaction): replacement rule (see [EpgStore.commitImport]), mapping, match
 *    rate, coverage, pruning of rows older than `maxArchiveDays + 1` days. Any failure in either
 *    pass (HTTP error, non-XML body, malformed or truncated XML, DB error, cancellation) deletes
 *    the staged rows and records the error; the previous guide stays.
 * 4. Archive backfill: for archive channels whose past window starts later than their archive
 *    window, sequential `get_simple_data_table` calls (capped per run), decoded in the server
 *    timezone and stored as [ProgrammeSource.XTREAM_ARCHIVE] without overwriting existing slots.
 */
class EpgSyncer(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
    private val epgStore: EpgStore,
    private val accounts: suspend (playlistId: Long) -> XtreamAccount?,
    private val overrideUrls: suspend (playlistId: Long) -> String?,
    private val downloader: XmltvDownloader,
    private val xtreamClient: XtreamClient,
    private val pullFactory: XmlPullSourceFactory = AndroidXmlPullSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val config: EpgSyncConfig = EpgSyncConfig(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val parser = XmltvParser(pullFactory)

    suspend fun sync(playlistId: Long, backfill: Boolean = true): EpgSyncOutcome = mutex.withLock { syncLocked(playlistId, backfill) }

    private class PassResult(val match: EpgMatchResult, val programmesStaged: Int)

    private suspend fun syncLocked(playlistId: Long, backfill: Boolean): EpgSyncOutcome {
        val playlist = playlistStore.playlist(playlistId)
            ?: return EpgSyncOutcome.Failed(KIND_NO_PLAYLIST, "Playlist $playlistId does not exist", retryable = false)
        val overrideUrl = overrideUrls(playlistId)
        val account = if (playlist.xtreamEnabled) accounts(playlistId) else null
        epgStore.updateState(playlistId) { it.copy(overrideUrlPresent = overrideUrl != null) }
        if (overrideUrl == null && account == null) return EpgSyncOutcome.Skipped(REASON_NO_SOURCE)

        val channels = catalogStore.channelsWithLiveInfo(playlistId).filter { !it.isHeader }
        if (channels.isEmpty()) return EpgSyncOutcome.Skipped(REASON_NO_CHANNELS)

        val now = clock()
        val maxArchiveDays = channels.maxOf { it.effectiveArchiveDays }.coerceAtLeast(0)
        val windowFrom = now - maxOf(maxArchiveDays * EpgSyncConfig.DAY, config.minPastWindowMillis)
        val windowTo = now + config.lookAheadMillis
        val importStart = now - config.importLookBackMillis
        val pruneBefore = now - (maxArchiveDays + 1) * EpgSyncConfig.DAY
        val entries = channels.map { EpgPlaylistChannel(epgEntryKey(it.streamId, it.ordinal), it.tvgId, it.epgChannelId, it.name) }
            .distinctBy { it.key }

        val importId = epgStore.beginImport(playlistId)
        val job = currentCoroutineContext()[Job]
        val overrideMatch: EpgMatchResult?
        val providerMatch: EpgMatchResult?
        var staged = 0
        try {
            overrideMatch = overrideUrl?.let { url ->
                val result = downloader.download(url) { input ->
                    parsePass(input, playlistId, importId, entries, emptySet(), windowFrom, windowTo, job)
                }
                staged += result.programmesStaged
                result.match
            }
            val remaining = overrideMatch?.let { m -> entries.filter { it.key !in m.matches } } ?: entries
            providerMatch = if (account != null && remaining.isNotEmpty()) {
                val url = XmltvDownloader.providerUrl(account) ?: throw XmltvDownloadException.InvalidUrl()
                val claimed = overrideMatch?.wantedXmltvChannelIds?.mapTo(HashSet()) { it.foldKey() }.orEmpty()
                val result = downloader.download(url) { input ->
                    parsePass(input, playlistId, importId, remaining, claimed, windowFrom, windowTo, job)
                }
                staged += result.programmesStaged
                result.match
            } else {
                null
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { epgStore.discardImport(playlistId, importId) }
            throw e
        } catch (e: Exception) {
            epgStore.discardImport(playlistId, importId)
            val failure = classify(e, overridePass = overrideUrl != null)
            logger.w("EPG import for playlist $playlistId failed: ${failure.kind}: ${failure.message}")
            return recordFailure(playlistId, failure)
        }

        val overrideCount = overrideMatch?.matchedEntries ?: 0
        val providerCount = providerMatch?.matchedEntries ?: 0
        val matched = overrideCount + providerCount
        val total = entries.size
        val source = if (overrideUrl != null) EpgMapOrigin.OVERRIDE else EpgMapOrigin.PROVIDER
        val previousMappings = epgStore.mappings(playlistId)
        val mappings = buildMappings(playlistId, overrideMatch, providerMatch, previousMappings)

        val stats = try {
            epgStore.commitImport(playlistId, importId, importStart, pruneBefore, mappings) { state, _, coverageFrom, coverageTo ->
                state.copy(
                    activeSource = source.name,
                    lastImportAt = now,
                    lastAttemptAt = now,
                    lastErrorAt = null,
                    lastErrorKind = null,
                    lastError = null,
                    matchedCount = matched,
                    totalCount = total,
                    matchRate = if (total == 0) 0.0 else matched.toDouble() / total,
                    overrideMatchedCount = overrideCount,
                    providerMatchedCount = providerCount,
                    coverageFrom = coverageFrom,
                    coverageTo = coverageTo,
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { epgStore.discardImport(playlistId, importId) }
            throw e
        } catch (e: Exception) {
            epgStore.discardImport(playlistId, importId)
            logger.e("EPG commit for playlist $playlistId failed", e)
            return recordFailure(playlistId, EpgSyncOutcome.Failed(KIND_OTHER, Redactor.redact(e), retryable = false))
        }
        // A credential change during the import re-marks the EPG stale; keep it set in that case.
        if (account == null || accounts(playlistId) == account) playlistStore.clearEpgStale(playlistId)
        logger.i(
            "EPG for playlist $playlistId imported from ${source.name}: $matched/$total entries matched " +
                "(override $overrideCount, provider $providerCount), $staged staged, ${stats.published} published, ${stats.pruned} pruned",
        )

        var backfilledChannels = 0
        var backfilledProgrammes = 0
        if (backfill && account != null) {
            val result = backfillArchive(playlistId, account, channels, now)
            backfilledChannels = result.first
            backfilledProgrammes = result.second
        }

        return EpgSyncOutcome.Imported(
            source = source,
            matchedEntries = matched,
            totalEntries = total,
            overrideMatched = overrideCount,
            providerMatched = providerCount,
            programmesPublished = stats.published,
            pruned = stats.pruned,
            backfilledChannels = backfilledChannels,
            backfilledProgrammes = backfilledProgrammes,
        )
    }

    /**
     * One streaming pass over an XMLTV document. Runs on the download's IO thread; batches are
     * staged synchronously so parsing never runs ahead of the database by more than one batch.
     */
    private fun parsePass(
        input: InputStream,
        playlistId: Long,
        importId: Long,
        entries: List<EpgPlaylistChannel>,
        excludedIds: Set<String>,
        fromMillis: Long,
        toMillis: Long,
        job: Job?,
    ): PassResult {
        val handler = ImportHandler(playlistId, importId, entries, excludedIds, job)
        parser.parse(input, XmltvParseOptions(wantedChannelIds = null, fromMillis = fromMillis, toMillis = toMillis, batchSize = config.batchSize), handler)
        handler.finish()
        return PassResult(handler.match ?: EpgMatchResult(emptyMap(), entries.size), handler.staged)
    }

    private inner class ImportHandler(
        private val playlistId: Long,
        private val importId: Long,
        private val entries: List<EpgPlaylistChannel>,
        private val excludedIds: Set<String>,
        private val job: Job?,
    ) : XmltvHandler {
        private val channels = ArrayList<XmltvChannel>()
        private val allIds = HashSet<String>()
        private var wanted: Set<String> = emptySet()
        private var folded: Map<String, String> = emptyMap()

        /** Programmes without `stop`, closed by the channel's next programme. */
        private val open = HashMap<String, XmltvProgramme>()
        var match: EpgMatchResult? = null
            private set
        var staged = 0
            private set

        override fun onChannel(channel: XmltvChannel) {
            allIds += channel.id
            if (channel.id.foldKey() !in excludedIds) channels += channel
        }

        override fun onChannelsComplete(): Set<String> {
            val result = EpgChannelMatcher.match(entries, channels)
            match = result
            wanted = result.wantedXmltvChannelIds
            val byFold = HashMap<String, String?>()
            for (id in wanted) {
                val key = id.foldKey()
                byFold[key] = if (byFold.containsKey(key)) null else id
            }
            @Suppress("UNCHECKED_CAST")
            folded = byFold.filterValues { it != null } as Map<String, String>
            channels.clear()
            return wanted
        }

        override fun onProgrammes(batch: List<XmltvProgramme>) {
            if (job?.isActive == false) throw CancellationException("EPG import cancelled")
            val rows = ArrayList<ProgrammeEntity>(batch.size)
            for (programme in batch) {
                val key = resolveKey(programme.channelId) ?: continue
                val stop = programme.stopMillis
                if (stop == null) {
                    open.put(key, programme)?.let { previous ->
                        if (programme.startMillis > previous.startMillis) rows += previous.toEntity(key, programme.startMillis)
                    }
                    continue
                }
                open.remove(key)?.let { previous ->
                    if (programme.startMillis > previous.startMillis) rows += previous.toEntity(key, programme.startMillis)
                }
                rows += programme.toEntity(key, stop)
            }
            stage(rows)
        }

        fun finish() {
            val rows = open.map { (key, programme) -> programme.toEntity(key, programme.startMillis + OPEN_PROGRAMME_MILLIS) }
            open.clear()
            stage(rows)
        }

        private fun stage(rows: List<ProgrammeEntity>) {
            if (rows.isEmpty()) return
            runBlocking { epgStore.stage(importId, rows) }
            staged += rows.size
        }

        /**
         * The guide key of a programme's `channel`: its exact spelling when matched; otherwise the
         * matched id that differs only by case, unless that spelling is itself a distinct channel
         * in the document (the provider has e.g. both `skysportsmainevent.uk` and
         * `SkySportsMainEvent.uk`).
         */
        private fun resolveKey(channelId: String): String? {
            if (channelId in wanted) return channelId
            if (channelId in allIds) return null
            return folded[channelId.foldKey()]
        }

        private fun XmltvProgramme.toEntity(key: String, stop: Long) = ProgrammeEntity(
            playlistId = playlistId,
            channelKey = key,
            startMillis = startMillis,
            stopMillis = stop,
            title = title,
            subTitle = subTitle,
            description = description,
            categories = categories.joinToString("\n").ifEmpty { null },
            episodeNum = displayEpisode(episodeNumbers),
            source = ProgrammeSource.XMLTV.name,
        )
    }

    private fun buildMappings(
        playlistId: Long,
        overrideMatch: EpgMatchResult?,
        providerMatch: EpgMatchResult?,
        previous: List<ChannelEpgMapEntity>,
    ): List<ChannelEpgMapEntity> {
        val out = LinkedHashMap<String, ChannelEpgMapEntity>()
        overrideMatch?.matches?.forEach { (key, m) ->
            out[key] = ChannelEpgMapEntity(playlistId, key, m.xmltvChannelId, m.method.toMapMethod().name, EpgMapOrigin.OVERRIDE.name)
        }
        providerMatch?.matches?.forEach { (key, m) ->
            out.putIfAbsent(key, ChannelEpgMapEntity(playlistId, key, m.xmltvChannelId, m.method.toMapMethod().name, EpgMapOrigin.PROVIDER.name))
        }
        // Archive-only channels (keyed by stream id) keep their backfilled guide.
        previous.filter { it.origin == EpgMapOrigin.XTREAM.name }.forEach { out.putIfAbsent(it.entryKey, it) }
        return out.values.toList()
    }

    /** Returns (channels fetched, programmes inserted). Per-channel failures are logged and skipped. */
    private suspend fun backfillArchive(
        playlistId: Long,
        account: XtreamAccount,
        channels: List<ChannelLiveInfo>,
        now: Long,
    ): Pair<Int, Int> {
        val zone = catalogStore.accountStatus(playlistId)?.serverTimezone?.let { id ->
            try {
                ZoneId.of(id)
            } catch (_: DateTimeException) {
                null
            }
        }
        if (zone == null) {
            logger.w("EPG archive backfill for playlist $playlistId skipped: server timezone unknown")
            return 0 to 0
        }
        val mappings = epgStore.mappings(playlistId).associateBy { it.entryKey }
        val candidates = channels.filter { it.effectiveArchiveDays > 0 && it.streamId != null }.distinctBy { it.streamId }
        var fetched = 0
        var inserted = 0
        for (channel in candidates) {
            if (fetched >= config.maxBackfillChannelsPerRun) break
            currentCoroutineContext().ensureActive()
            val streamId = channel.streamId ?: continue
            val entryKey = epgEntryKey(streamId, channel.ordinal)
            val mapping = mappings[entryKey]
            val lastBackfill = mapping?.lastBackfillAt
            if (lastBackfill != null && now - lastBackfill < config.backfillMinIntervalMillis) continue
            val archiveDays = channel.effectiveArchiveDays
            val channelKey = mapping?.channelKey ?: "$XTREAM_KEY_PREFIX$streamId"
            val earliest = epgStore.earliestStart(playlistId, channelKey)
            if (earliest != null && earliest <= now - archiveDays * EpgSyncConfig.DAY + config.backfillGapToleranceMillis) continue

            if (fetched > 0) pause(config.backfillDelayMillis)
            fetched++
            val listings = try {
                xtreamClient.simpleDataTable(account, streamId)
            } catch (e: XtreamException) {
                if (e is XtreamException.AuthFailed || (e is XtreamException.Http && e.isAuthFailure)) {
                    logger.w("EPG archive backfill for playlist $playlistId stopped: account rejected")
                    break
                }
                logger.w("EPG archive backfill for stream $streamId failed: ${e.message}")
                continue
            }
            val rows = XtreamEpgDecoder.decode(listings, zone).programmes
                .filter { it.isInArchiveWindow(now, archiveDays) }
                .map {
                    ProgrammeEntity(
                        playlistId = playlistId,
                        channelKey = channelKey,
                        startMillis = it.startMillis,
                        stopMillis = it.stopMillis,
                        title = it.title,
                        subTitle = null,
                        description = it.description,
                        categories = null,
                        episodeNum = null,
                        source = ProgrammeSource.XTREAM_ARCHIVE.name,
                    )
                }
            if (mapping == null) {
                epgStore.putMappings(
                    listOf(ChannelEpgMapEntity(playlistId, entryKey, channelKey, EpgMapMethod.STREAM_ID.name, EpgMapOrigin.XTREAM.name, now)),
                )
            }
            inserted += epgStore.insertArchive(playlistId, channelKey, rows)
            epgStore.markBackfilled(playlistId, entryKey, now)
        }
        epgStore.updateState(playlistId) { it.copy(lastBackfillAt = now, backfilledChannels = fetched) }
        if (fetched > 0) logger.i("EPG archive backfill for playlist $playlistId: $fetched channels, $inserted programmes")
        return fetched to inserted
    }

    private fun classify(e: Exception, overridePass: Boolean): EpgSyncOutcome.Failed {
        val prefix = if (overridePass) "EPG source" else "Provider EPG"
        return when (e) {
            is XmltvDownloadException.Http -> EpgSyncOutcome.Failed(KIND_HTTP, "$prefix returned HTTP ${e.code}", e.retryable)
            is XmltvDownloadException.InvalidUrl -> EpgSyncOutcome.Failed(KIND_INVALID_URL, e.message.orEmpty(), retryable = false)
            is XmltvDownloadException -> EpgSyncOutcome.Failed(KIND_NETWORK, Redactor.redact(e.message), retryable = true)
            is XmltvParseException ->
                if (generateSequence<Throwable>(e) { it.cause }.take(8).any { it is IOException }) {
                    EpgSyncOutcome.Failed(KIND_NETWORK, "$prefix download interrupted: ${Redactor.redact(e.message)}", retryable = true)
                } else {
                    EpgSyncOutcome.Failed(KIND_MALFORMED, "$prefix is not a valid XMLTV document: ${Redactor.redact(e.message)}", retryable = false)
                }
            is IOException -> EpgSyncOutcome.Failed(KIND_NETWORK, Redactor.redact(e), retryable = true)
            else -> EpgSyncOutcome.Failed(KIND_OTHER, Redactor.redact(e), retryable = false)
        }
    }

    private suspend fun recordFailure(playlistId: Long, failure: EpgSyncOutcome.Failed): EpgSyncOutcome.Failed {
        val now = clock()
        epgStore.updateState(playlistId) {
            it.copy(lastAttemptAt = now, lastErrorAt = now, lastErrorKind = failure.kind, lastError = failure.message.take(MAX_ERROR))
        }
        return failure
    }

    companion object {
        const val KIND_NO_PLAYLIST = "NoSuchPlaylist"
        const val KIND_HTTP = "Http"
        const val KIND_NETWORK = "Network"
        const val KIND_MALFORMED = "Malformed"
        const val KIND_INVALID_URL = "InvalidUrl"
        const val KIND_OTHER = "Other"

        const val REASON_NO_SOURCE = "NoEpgSource"
        const val REASON_NO_CHANNELS = "NoChannels"

        /** Guide key for archive-only channels that have no XMLTV channel. */
        const val XTREAM_KEY_PREFIX = "xtream:"

        private const val OPEN_PROGRAMME_MILLIS = EpgSyncConfig.HOUR
        private const val MAX_ERROR = 300

        private fun String.foldKey(): String = trim().lowercase(Locale.ROOT)

        private fun app.playlistplayer.core.parsing.xmltv.EpgMatchMethod.toMapMethod(): EpgMapMethod = when (this) {
            app.playlistplayer.core.parsing.xmltv.EpgMatchMethod.EXACT_ID -> EpgMapMethod.EXACT_ID
            app.playlistplayer.core.parsing.xmltv.EpgMatchMethod.CASE_INSENSITIVE_ID -> EpgMapMethod.CASE_INSENSITIVE_ID
            app.playlistplayer.core.parsing.xmltv.EpgMatchMethod.DISPLAY_NAME -> EpgMapMethod.DISPLAY_NAME
        }

        /** `onscreen` as given, else `xmltv_ns` (`season.episode.part`, zero-based) as `S1 E2`. */
        internal fun displayEpisode(numbers: List<XmltvEpisodeNumber>): String? {
            numbers.firstOrNull { it.system.equals("onscreen", ignoreCase = true) }?.let { return it.value }
            val ns = numbers.firstOrNull { it.system.equals("xmltv_ns", ignoreCase = true) } ?: return numbers.firstOrNull()?.value
            val parts = ns.value.split('.')
            fun part(i: Int): Int? = parts.getOrNull(i)?.substringBefore('/')?.trim()?.toIntOrNull()
            val season = part(0)
            val episode = part(1)
            return when {
                season != null && episode != null -> "S${season + 1} E${episode + 1}"
                episode != null -> "E${episode + 1}"
                season != null -> "S${season + 1}"
                else -> null
            }
        }
    }
}

package app.playlistplayer.core.sync

import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.SyncStateEntity
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.Redactor
import app.playlistplayer.core.network.source.PlaylistSourceFactory
import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.network.source.SourceFetchResult
import app.playlistplayer.core.network.source.SourceVersion
import app.playlistplayer.core.parsing.m3u.AccountExtraction
import app.playlistplayer.core.parsing.m3u.AccountExtractor
import app.playlistplayer.core.parsing.m3u.M3uEntry
import app.playlistplayer.core.parsing.m3u.M3uParser
import app.playlistplayer.core.parsing.m3u.XtreamUrl
import app.playlistplayer.core.sync.secure.SecureStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/** What one playlist sync did. */
sealed interface SyncOutcome {
    val name: String

    data class Updated(
        val channelCount: Int,
        val groupCount: Int,
        val credentialsChanged: Boolean,
        val mixedAccounts: Boolean,
    ) : SyncOutcome {
        override val name: String get() = "Updated"
    }

    data object Unchanged : SyncOutcome {
        override val name: String get() = "Unchanged"
    }

    /** The download succeeded but was refused (e.g. channel count collapsed); cached copy kept. */
    data class Rejected(val previousCount: Int, val newCount: Int, val reason: String) : SyncOutcome {
        override val name: String get() = "Rejected"
    }

    /** Nothing changed; the previous generation stays active. [message] is redacted. */
    data class Failed(val kind: String, val message: String, val retryable: Boolean) : SyncOutcome {
        override val name: String get() = "Failed"
    }
}

/**
 * The playlist sync pipeline, independent of WorkManager so it can be tested directly.
 *
 * metadata check → skip if unchanged → stream download → parse → derive account → write a new
 * generation in batches → flip the active generation → store the account in [SecureStore].
 *
 * Failure tolerance: any failure (HTTP error, connection drop mid-download, parse/DB error,
 * cancellation) discards the incomplete generation and records the failure in SyncState, so the
 * previous playlist stays visible with a "using cached copy from <date>" banner. A refresh whose
 * channel count drops by more than [maxChannelDropFraction] is rejected with a warning.
 *
 * Only stream ids and attributes reach Room; URLs exist only while a line is being parsed.
 */
class PlaylistSyncer(
    private val store: PlaylistStore,
    private val secureStore: SecureStore,
    private val sourceFactory: PlaylistSourceFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val parser: M3uParser = M3uParser(),
    private val batchSize: Int = PlaylistStore.DEFAULT_BATCH_SIZE,
    private val maxChannelDropFraction: Double = 0.5,
) {
    private val mutex = Mutex()

    private class ParsedGeneration(
        val generation: Long,
        val channelCount: Int,
        val groupCount: Int,
        val headerCount: Int,
        val skippedWithoutUrl: Int,
        val extraction: AccountExtraction,
    )

    suspend fun sync(playlistId: Long): SyncOutcome = mutex.withLock { syncLocked(playlistId) }

    private suspend fun syncLocked(playlistId: Long): SyncOutcome {
        val playlist = store.playlist(playlistId)
            ?: return SyncOutcome.Failed(KIND_NO_PLAYLIST, "Playlist $playlistId does not exist", retryable = false)
        val reference = secureStore.sourceReference(playlistId)
            ?: return recordFailure(playlistId, KIND_MISSING_SOURCE, "No source stored for this playlist", retryable = false)

        val state = store.syncState(playlistId)
        // No fingerprint before the first successful sync, so the first sync always downloads.
        val previous = state?.takeIf { it.activeGeneration != null }?.toSourceVersion()
        var generation: Long? = null

        val result = try {
            sourceFactory.create(reference).fetch(previous) { input ->
                val allocated = store.beginGeneration(playlistId)
                generation = allocated
                writeGeneration(playlistId, allocated, input)
            }
        } catch (e: CancellationException) {
            generation?.let { withContext(NonCancellable) { store.discardGeneration(playlistId, it) } }
            throw e
        } catch (e: Exception) {
            generation?.let { store.discardGeneration(playlistId, it) }
            logger.e("Playlist $playlistId sync failed", e)
            return recordFailure(playlistId, SourceError.KIND_OTHER, Redactor.redact(e), retryable = false)
        }

        return when (result) {
            is SourceFetchResult.Unchanged -> {
                generation?.let { store.discardGeneration(playlistId, it) }
                val now = clock()
                store.updateSyncState(playlistId) {
                    it.clearFailure().copy(
                        lastAttemptAt = now,
                        lastSuccessAt = now,
                        sourceModifiedTime = result.version.modifiedTime ?: it.sourceModifiedTime,
                    )
                }
                logger.i("Playlist $playlistId unchanged")
                SyncOutcome.Unchanged
            }
            is SourceFetchResult.Failed -> {
                generation?.let { store.discardGeneration(playlistId, it) }
                val error = result.error
                recordFailure(playlistId, error.kind, error.message, retryable = error.isRetryable())
            }
            is SourceFetchResult.Fetched -> commit(playlistId, playlist.xtreamEnabled, state, result.value, result.version)
        }
    }

    private suspend fun writeGeneration(playlistId: Long, generation: Long, input: InputStream): ParsedGeneration {
        val writer = store.writer(playlistId, generation, batchSize)
        val extractor = AccountExtractor()
        var headers = 0
        // The source owns (and closes) the stream; the reader is not closed here.
        val reader = parser.open(input)
        for (entry in reader) {
            extractor.add(entry.url)
            if (entry.isHeader) headers++
            writer.add(entry.toEntity(playlistId, generation))
        }
        val groups = reader.groups.mapIndexed { ordinal, (name, count) ->
            ChannelGroupEntity(playlistId, generation, ordinal, name, count)
        }
        writer.writeGroups(groups)
        return ParsedGeneration(generation, writer.channelsWritten, groups.size, headers, reader.skippedWithoutUrl, extractor.result())
    }

    private suspend fun commit(
        playlistId: Long,
        xtreamEnabled: Boolean,
        state: SyncStateEntity?,
        parsed: ParsedGeneration,
        version: SourceVersion,
    ): SyncOutcome {
        val now = clock()
        if (parsed.channelCount == 0) {
            store.discardGeneration(playlistId, parsed.generation)
            return recordFailure(playlistId, KIND_EMPTY, "The downloaded playlist contains no channels", retryable = false)
        }
        val activeCount = if (state?.activeGeneration != null) state.channelCount else 0
        if (activeCount > 0 && parsed.channelCount < activeCount * (1.0 - maxChannelDropFraction)) {
            store.discardGeneration(playlistId, parsed.generation)
            val warning = "Refresh rejected: channel count dropped from $activeCount to ${parsed.channelCount}; keeping the cached copy"
            store.updateSyncState(playlistId) { it.copy(lastAttemptAt = now, lastWarning = warning, lastWarningAt = now) }
            logger.w("Playlist $playlistId: $warning")
            return SyncOutcome.Rejected(activeCount, parsed.channelCount, warning)
        }

        val newAccount = parsed.extraction.account
        val oldAccount = secureStore.account(playlistId)
        val credentialsChanged = newAccount != oldAccount

        store.activateGeneration(playlistId, parsed.generation) {
            it.clearFailure().copy(
                lastAttemptAt = now,
                lastSuccessAt = now,
                contentUpdatedAt = now,
                lastWarning = null,
                lastWarningAt = null,
                sourceMd5 = version.md5,
                sourceVersion = version.version,
                sourceEtag = version.etag,
                sourceLastModified = version.lastModified,
                sourceModifiedTime = version.modifiedTime,
                channelCount = parsed.channelCount,
                groupCount = parsed.groupCount,
                mixedAccounts = parsed.extraction.mixedAccounts,
                // New or changed credentials invalidate the Xtream catalog, EPG and catch-up URLs.
                catalogStale = it.catalogStale || credentialsChanged,
                epgStale = it.epgStale || credentialsChanged,
            )
        }
        if (credentialsChanged) storeAccount(playlistId, newAccount)
        if (xtreamEnabled && !parsed.extraction.isXtreamShaped) {
            // Xtream processing is unavailable when the playlist's URLs are not Xtream-shaped.
            store.setXtreamEnabled(playlistId, false)
        }
        logger.i(
            "Playlist $playlistId updated: ${parsed.channelCount} channels, ${parsed.groupCount} groups, " +
                "${parsed.headerCount} headers, ${parsed.skippedWithoutUrl} skipped, credentialsChanged=$credentialsChanged, " +
                "mixedAccounts=${parsed.extraction.mixedAccounts}",
        )
        return SyncOutcome.Updated(parsed.channelCount, parsed.groupCount, credentialsChanged, parsed.extraction.mixedAccounts)
    }

    private suspend fun storeAccount(playlistId: Long, account: XtreamAccount?) {
        if (account != null) secureStore.putAccount(playlistId, account) else secureStore.removeAccount(playlistId)
    }

    private suspend fun recordFailure(playlistId: Long, kind: String, message: String, retryable: Boolean): SyncOutcome.Failed {
        val now = clock()
        val redacted = Redactor.redact(message)
        store.updateSyncState(playlistId) {
            it.copy(lastAttemptAt = now, lastFailureAt = now, lastFailureKind = kind, lastFailureMessage = redacted)
        }
        logger.w("Playlist $playlistId sync failed: $kind: $redacted")
        return SyncOutcome.Failed(kind, redacted, retryable)
    }

    private fun SourceError.isRetryable(): Boolean = when (this) {
        is SourceError.Network -> true
        is SourceError.Other -> httpCode.let { it == null || it >= 500 }
        else -> false
    }

    companion object {
        const val KIND_NO_PLAYLIST = "NoSuchPlaylist"
        const val KIND_MISSING_SOURCE = "MissingSource"
        const val KIND_EMPTY = "EmptyPlaylist"

        private val CREDENTIAL_QUERY = Regex("""(?i)[?&](username|password|token)=""")

        private fun SyncStateEntity.toSourceVersion() = SourceVersion(
            md5 = sourceMd5,
            version = sourceVersion,
            etag = sourceEtag,
            lastModified = sourceLastModified,
            modifiedTime = sourceModifiedTime,
        )

        private fun SyncStateEntity.clearFailure() = copy(lastFailureAt = null, lastFailureKind = null, lastFailureMessage = null)

        /** Logo URLs are kept unless they look credential-bearing. */
        private fun safeLogo(logo: String?): String? {
            if (logo == null) return null
            val parsed = XtreamUrl.parse(logo)
            if (parsed != null && parsed.kind != XtreamUrl.Kind.SHORT_LIVE) return null
            if (CREDENTIAL_QUERY.containsMatchIn(logo)) return null
            return logo
        }

        internal fun M3uEntry.toEntity(playlistId: Long, generation: Long) = ChannelEntity(
            playlistId = playlistId,
            generation = generation,
            ordinal = ordinal,
            groupOrdinal = groupOrdinal,
            name = name,
            streamId = streamId,
            containerExtension = containerExtension,
            tvgId = tvgId,
            tvgName = tvgName,
            logo = safeLogo(tvgLogo),
            cuid = cuid,
            tvgChno = tvgChno,
            catchupType = catchupType,
            catchupDays = catchupDays,
            isHeader = isHeader,
        )
    }
}

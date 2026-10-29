package app.playlistplayer.core.sync.catalog

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.CatalogWriter
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.CatalogSyncStateEntity
import app.playlistplayer.core.database.entity.LiveStreamInfoEntity
import app.playlistplayer.core.database.entity.SeriesCategoryEntity
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.Redactor
import app.playlistplayer.core.network.xtream.AccountInfo
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.network.xtream.XtreamException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** What one catalog sync did. */
sealed interface CatalogSyncOutcome {
    val name: String

    /** A new catalog generation is active. */
    data class Updated(
        val liveInfoCount: Int,
        /** Playlist stream ids absent from get_live_streams (e.g. merged 24/7 channels). */
        val liveMissingCount: Int,
        val vodCategoryCount: Int,
        val vodCount: Int,
        val seriesCategoryCount: Int,
        val seriesCount: Int,
        /** List elements that could not be decoded at all. */
        val skippedCount: Int,
    ) : CatalogSyncOutcome {
        override val name: String get() = "Updated"
    }

    /** Account status refreshed; the catalog is recent and not stale, so it was not re-fetched. */
    data object Fresh : CatalogSyncOutcome {
        override val name: String get() = "Fresh"
    }

    /** Nothing to do: Xtream processing is off, or the playlist has no Xtream account. */
    data class Skipped(val reason: String) : CatalogSyncOutcome {
        override val name: String get() = "Skipped"
    }

    /** `auth: 0`, HTTP 401 or a non-Active status. No catalog call was made; the old catalog stays. */
    data class AccountExpired(val status: String?) : CatalogSyncOutcome {
        override val name: String get() = "AccountExpired"
    }

    /** The previous catalog stays active. [message] is redacted. */
    data class Failed(val kind: String, val message: String, val retryable: Boolean) : CatalogSyncOutcome {
        override val name: String get() = "Failed"
    }
}

/**
 * The Xtream catalog pipeline for one playlist, independent of WorkManager:
 *
 * account info → (auth failed / not Active: record AccountExpired, stop) → write account status
 * → if the catalog is due: get_live_streams (joined to the playlist's stream ids), VOD categories
 * and streams, series categories and series, each streamed in batches into a new catalog
 * generation → flip atomically → clear `catalogStale`.
 *
 * The catalog is due when forced, when `catalogStale` is set (first sync, credentials changed),
 * when no catalog is active, or when the last success is older than [refreshIntervalMillis].
 * Any failure (network, HTTP, malformed body, DB error, cancellation) discards the partial
 * generation; the previous catalog stays active.
 */
class CatalogSyncer(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
    private val accounts: suspend (playlistId: Long) -> XtreamAccount?,
    private val client: XtreamClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val batchSize: Int = XtreamClient.DEFAULT_BATCH_SIZE,
    private val refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS,
) {
    private val mutex = Mutex()

    suspend fun sync(playlistId: Long, force: Boolean = false): CatalogSyncOutcome = mutex.withLock { syncLocked(playlistId, force) }

    private suspend fun syncLocked(playlistId: Long, force: Boolean): CatalogSyncOutcome {
        val playlist = playlistStore.playlist(playlistId)
            ?: return CatalogSyncOutcome.Failed(KIND_NO_PLAYLIST, "Playlist $playlistId does not exist", retryable = false)
        if (!playlist.xtreamEnabled) return CatalogSyncOutcome.Skipped(REASON_DISABLED)
        val account = accounts(playlistId) ?: run {
            recordFailure(playlistId, KIND_NO_ACCOUNT, "No Xtream account stored for this playlist", retryable = false)
            return CatalogSyncOutcome.Skipped(REASON_NO_ACCOUNT)
        }
        val now = clock()

        val info = try {
            client.accountInfo(account)
        } catch (e: XtreamException.Http) {
            if (e.isAuthFailure) return accountExpired(playlistId, AccountStatusEntity.STATUS_AUTH_FAILED, now, info = null)
            return recordFailure(playlistId, kindOf(e), e.message.orEmpty(), e.retryable)
        } catch (e: XtreamException) {
            return recordFailure(playlistId, kindOf(e), e.message.orEmpty(), e.retryable)
        }
        catalogStore.putAccountStatus(info.toAccountStatusEntity(playlistId, now))
        if (!info.isActive) {
            val status = if (info.isAuthenticated) info.userInfo.status else AccountStatusEntity.STATUS_AUTH_FAILED
            return accountExpired(playlistId, status, now, info)
        }

        val state = catalogStore.state(playlistId)
        val stale = playlistStore.syncState(playlistId)?.catalogStale == true
        val lastSuccessAt = state?.lastSuccessAt
        val due = force || stale || state?.activeGeneration == null || lastSuccessAt == null ||
            now - lastSuccessAt >= refreshIntervalMillis
        if (!due) {
            catalogStore.updateState(playlistId) { it.clearFailure().copy(lastAttemptAt = now, accountExpired = false) }
            return CatalogSyncOutcome.Fresh
        }

        val generation = catalogStore.beginGeneration(playlistId)
        val counts = try {
            writeCatalog(catalogStore.writer(playlistId, generation, batchSize), account)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { catalogStore.discardGeneration(playlistId, generation) }
            throw e
        } catch (e: XtreamException) {
            catalogStore.discardGeneration(playlistId, generation)
            if (e is XtreamException.AuthFailed || (e is XtreamException.Http && e.isAuthFailure)) {
                return accountExpired(playlistId, AccountStatusEntity.STATUS_AUTH_FAILED, now, info = null)
            }
            logger.w("Catalog sync for playlist $playlistId failed", e)
            return recordFailure(playlistId, kindOf(e), e.message.orEmpty(), e.retryable)
        } catch (e: Exception) {
            catalogStore.discardGeneration(playlistId, generation)
            logger.e("Catalog sync for playlist $playlistId failed", e)
            return recordFailure(playlistId, KIND_OTHER, Redactor.redact(e), retryable = false)
        }

        catalogStore.activateGeneration(playlistId, generation) {
            it.clearFailure().copy(
                lastAttemptAt = now,
                lastSuccessAt = now,
                contentUpdatedAt = now,
                accountExpired = false,
                liveInfoCount = counts.liveInfoCount,
                liveMissingCount = counts.liveMissingCount,
                vodCategoryCount = counts.vodCategoryCount,
                vodCount = counts.vodCount,
                seriesCategoryCount = counts.seriesCategoryCount,
                seriesCount = counts.seriesCount,
            )
        }
        // A credential change during the sync re-marks the catalog stale; only clear it when the
        // catalog was fetched with the account that is still stored.
        if (accounts(playlistId) == account) playlistStore.clearCatalogStale(playlistId)
        logger.i(
            "Catalog for playlist $playlistId updated: live ${counts.liveInfoCount} (${counts.liveMissingCount} missing), " +
                "vod ${counts.vodCount}/${counts.vodCategoryCount} categories, series ${counts.seriesCount}/" +
                "${counts.seriesCategoryCount} categories, ${counts.skippedCount} skipped",
        )
        return counts
    }

    private suspend fun writeCatalog(writer: CatalogWriter, account: XtreamAccount): CatalogSyncOutcome.Updated {
        val playlistId = writer.playlistId
        val generation = writer.generation
        var skipped = 0

        // Live: the playlist decides which channels exist; the API only enriches known stream ids.
        val wanted = HashSet(catalogStore.playlistStreamIds(playlistId))
        val matched = HashSet<Long>()
        skipped += client.streamLiveStreams(account, batchSize) { batch ->
            val rows = batch.mapNotNull { stream ->
                val id = stream.streamId ?: return@mapNotNull null
                if (id !in wanted) return@mapNotNull null
                matched.add(id)
                LiveStreamInfoEntity(
                    playlistId = playlistId,
                    generation = generation,
                    streamId = id,
                    tvArchive = stream.tvArchive == true,
                    archiveDays = stream.archiveDays,
                    epgChannelId = stream.epgChannelId?.trim()?.ifEmpty { null },
                    categoryId = stream.categoryId,
                )
            }
            if (rows.isNotEmpty()) writer.insertLiveInfo(rows)
        }.skipped

        val vodCategories = client.vodCategories(account).mapNotNull { it.categoryId?.let { id -> id to it } }
            .distinctBy { it.first }
            .mapIndexed { ordinal, (id, c) -> VodCategoryEntity(playlistId, generation, id, c.categoryName.orEmpty(), c.parentId, ordinal) }
        writer.insertVodCategories(vodCategories)

        var vodOrder = 0
        val vodStats = client.streamVodStreams(account, batchSize) { batch ->
            val rows = batch.mapNotNull { vod ->
                val id = vod.streamId ?: return@mapNotNull null
                VodStreamEntity(
                    playlistId = playlistId,
                    generation = generation,
                    streamId = id,
                    name = vod.name.orEmpty(),
                    icon = SafeImageUrl.filter(vod.streamIcon, account),
                    rating = vod.rating,
                    added = vod.added,
                    categoryId = vod.categoryId,
                    containerExtension = vod.containerExtension,
                    tmdb = vod.tmdb,
                    isAdult = vod.isAdult == true,
                    providerOrder = vodOrder++,
                )
            }
            if (rows.isNotEmpty()) writer.insertVodStreams(rows)
        }
        skipped += vodStats.skipped

        val seriesCategories = client.seriesCategories(account).mapNotNull { it.categoryId?.let { id -> id to it } }
            .distinctBy { it.first }
            .mapIndexed { ordinal, (id, c) -> SeriesCategoryEntity(playlistId, generation, id, c.categoryName.orEmpty(), c.parentId, ordinal) }
        writer.insertSeriesCategories(seriesCategories)

        var seriesOrder = 0
        val seriesStats = client.streamSeries(account, batchSize) { batch ->
            val rows = batch.mapNotNull { s ->
                val id = s.seriesId ?: return@mapNotNull null
                SeriesEntity(
                    playlistId = playlistId,
                    generation = generation,
                    seriesId = id,
                    name = s.name.orEmpty(),
                    cover = SafeImageUrl.filter(s.cover, account),
                    plot = s.plot,
                    genre = s.genre,
                    releaseDate = s.releaseDate,
                    rating = s.rating,
                    categoryId = s.categoryId,
                    tmdb = s.tmdb,
                    lastModified = s.lastModified,
                    providerOrder = seriesOrder++,
                )
            }
            if (rows.isNotEmpty()) writer.insertSeries(rows)
        }
        skipped += seriesStats.skipped

        return CatalogSyncOutcome.Updated(
            liveInfoCount = matched.size,
            liveMissingCount = wanted.size - matched.size,
            vodCategoryCount = vodCategories.size,
            vodCount = vodOrder,
            seriesCategoryCount = seriesCategories.size,
            seriesCount = seriesOrder,
            skippedCount = skipped,
        )
    }

    private suspend fun accountExpired(playlistId: Long, status: String?, now: Long, info: AccountInfo?): CatalogSyncOutcome.AccountExpired {
        if (info == null) {
            // HTTP 401 / auth 0 on a list call: keep the last known timezone for catch-up.
            val previous = catalogStore.accountStatus(playlistId)
            catalogStore.putAccountStatus(
                AccountStatusEntity(
                    playlistId = playlistId,
                    status = status,
                    isActive = false,
                    expDate = previous?.expDate,
                    maxConnections = previous?.maxConnections,
                    activeCons = null,
                    allowedFormats = previous?.allowedFormats.orEmpty(),
                    serverTimezone = previous?.serverTimezone,
                    isTrial = previous?.isTrial,
                    updatedAt = now,
                ),
            )
        }
        val message = "Xtream account is not active (${status ?: "unknown status"})"
        catalogStore.updateState(playlistId) {
            it.copy(
                lastAttemptAt = now,
                lastFailureAt = now,
                lastFailureKind = KIND_ACCOUNT_EXPIRED,
                lastFailureMessage = Redactor.redact(message),
                accountExpired = true,
            )
        }
        logger.w("Playlist $playlistId: $message; catalog sync stopped")
        return CatalogSyncOutcome.AccountExpired(status)
    }

    private suspend fun recordFailure(playlistId: Long, kind: String, message: String, retryable: Boolean): CatalogSyncOutcome.Failed {
        val now = clock()
        val redacted = Redactor.redact(message)
        catalogStore.updateState(playlistId) {
            it.copy(lastAttemptAt = now, lastFailureAt = now, lastFailureKind = kind, lastFailureMessage = redacted)
        }
        logger.w("Catalog sync for playlist $playlistId failed: $kind: $redacted")
        return CatalogSyncOutcome.Failed(kind, redacted, retryable)
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000

        const val KIND_NO_PLAYLIST = "NoSuchPlaylist"
        const val KIND_NO_ACCOUNT = "NoAccount"
        const val KIND_ACCOUNT_EXPIRED = "AccountExpired"
        const val KIND_NETWORK = "Network"
        const val KIND_HTTP = "Http"
        const val KIND_MALFORMED = "Malformed"
        const val KIND_OTHER = "Other"

        const val REASON_DISABLED = "XtreamDisabled"
        const val REASON_NO_ACCOUNT = "NoAccount"

        fun kindOf(e: XtreamException): String = when (e) {
            is XtreamException.Network -> KIND_NETWORK
            is XtreamException.Http -> KIND_HTTP
            is XtreamException.Malformed -> KIND_MALFORMED
            is XtreamException.AuthFailed -> KIND_ACCOUNT_EXPIRED
        }

        private fun CatalogSyncStateEntity.clearFailure() = copy(lastFailureAt = null, lastFailureKind = null, lastFailureMessage = null)
    }
}

/** Maps a `player_api.php` response to the persisted account status row. */
fun AccountInfo.toAccountStatusEntity(playlistId: Long, now: Long): AccountStatusEntity = AccountStatusEntity(
    playlistId = playlistId,
    status = if (isAuthenticated) userInfo.status else AccountStatusEntity.STATUS_AUTH_FAILED,
    isActive = isActive,
    expDate = userInfo.expiresAtMillis,
    maxConnections = userInfo.maxConnections,
    activeCons = userInfo.activeConnections,
    allowedFormats = userInfo.allowedOutputFormats.joinToString(","),
    serverTimezone = serverInfo?.timezone,
    isTrial = userInfo.isTrial,
    updatedAt = now,
)

/**
 * Artwork URLs are kept unless they could carry credentials (account username/password in the
 * path or credential query parameters). Only http(s) URLs are kept.
 */
object SafeImageUrl {
    private val CREDENTIAL_QUERY = Regex("""(?i)[?&;](username|password|user|pass|token)=""")

    fun filter(url: String?, account: XtreamAccount): String? {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        if (CREDENTIAL_QUERY.containsMatchIn(trimmed)) return null
        val decoded = try {
            java.net.URLDecoder.decode(trimmed, "UTF-8")
        } catch (_: IllegalArgumentException) {
            trimmed
        }
        val segments = decoded.substringBefore('?').split('/')
        val hasUser = account.username.length >= 3 && segments.any { it == account.username }
        val hasPass = account.password.length >= 3 && segments.any { it == account.password }
        if (hasUser && hasPass) return null
        return trimmed
    }
}

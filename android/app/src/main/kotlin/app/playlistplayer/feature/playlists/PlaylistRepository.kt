package app.playlistplayer.feature.playlists

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.CatalogSyncStateEntity
import app.playlistplayer.core.database.entity.EpgSourceStateEntity
import app.playlistplayer.core.database.entity.SyncStateEntity
import app.playlistplayer.core.model.Playlist
import app.playlistplayer.core.model.PlaylistSourceType
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.source.M3uUrlSource
import app.playlistplayer.core.sync.PlaylistRegistry
import app.playlistplayer.core.sync.PlaylistSyncScheduler
import app.playlistplayer.core.sync.StreamUrlVault
import app.playlistplayer.core.sync.secure.SecureStore
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.settings.ProtectedAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gate for settings actions (R27 with U12's parental PIN). Returns true to proceed. The settings
 * unit binds it to `ParentalControl.authorize` plus its PIN prompt; the default allows everything.
 */
typealias PlaylistAuthorizer = suspend (ProtectedAction) -> Boolean

/** Latest sync result of a playlist's channel list. */
enum class SyncHealth {
    /** Added, first sync not finished yet. */
    NeverSynced,

    Ok,

    /** The last refresh failed; the previous copy is still shown ("using cached copy from <date>"). */
    UsingCachedCopy,

    /** The first sync failed: there are no channels. */
    Failed,
}

/** Xtream account status (shown only while Xtream processing is on). */
data class AccountSummary(
    /** Provider status (`Active`, `Expired`, `Banned`, `Disabled`) or `AuthFailed`. */
    val status: String?,
    val isActive: Boolean,
    /** Epoch millis; null means no expiry. */
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val checkedAt: Long,
)

/** What a playlist stores (Settings → storage). */
data class StorageCounts(
    val groups: Int = 0,
    val channels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val programmes: Int = 0,
)

/** One row of Settings → Playlists. Contains no secret (source reference and account stay encrypted). */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val sourceType: PlaylistSourceType,
    val xtreamEnabled: Boolean,
    /** Whether the toggle may be turned on: an Xtream account was derived from the playlist (R24). */
    val xtreamAvailable: Boolean,
    val createdAt: Long,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    /** When the channel list last changed. */
    val contentUpdatedAt: Long?,
    val health: SyncHealth,
    /** Kind and redacted message of the last playlist sync failure (see `SourceError` kinds). */
    val failureKind: String?,
    val failureMessage: String?,
    /** e.g. "Refresh rejected: channel count dropped …". */
    val warning: String?,
    val catalogFailureKind: String?,
    val guideFailureKind: String?,
    val accountExpired: Boolean,
    val account: AccountSummary?,
    val counts: StorageCounts,
) {
    /** Only the playlist whose refresh failed shows the cached-copy banner. */
    val showCachedCopyBanner: Boolean get() = health == SyncHealth.UsingCachedCopy
}

sealed interface PlaylistActionResult {
    data object Done : PlaylistActionResult

    data class Added(val playlistId: Long) : PlaylistActionResult

    /** The authorizer refused (wrong PIN, cancelled prompt, lockout). Nothing changed. */
    data object NotAuthorized : PlaylistActionResult

    data object NotFound : PlaylistActionResult

    data class Invalid(val reason: InvalidReason) : PlaylistActionResult

    /** Xtream processing cannot be turned on: the playlist is not Xtream-shaped (R24). */
    data object XtreamUnavailable : PlaylistActionResult
}

enum class InvalidReason { BlankName, BlankDriveId, InvalidUrl }

/**
 * Playlist management (R23–R27): list with status, add, rename, edit source, toggle Xtream
 * processing, refresh and remove. Sync work goes through [scheduler] (the serial pipeline).
 *
 * Mutating actions take an [PlaylistAuthorizer] (default [authorize]) so Settings can gate them
 * with the parental PIN: rename/edit source → [ProtectedAction.EditPlaylist], Xtream toggle →
 * [ProtectedAction.ChangeXtreamProcessing], remove → [ProtectedAction.RemovePlaylist]. Adding and
 * refreshing are not gated.
 */
class PlaylistRepository(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
    private val epgStore: EpgStore,
    private val registry: PlaylistRegistry,
    private val secureStore: SecureStore,
    private val vault: StreamUrlVault,
    private val scheduler: PlaylistSyncScheduler,
    private val authorize: PlaylistAuthorizer = { true },
    /** Extra per-playlist cleanup on removal (parental locked groups, favourites, progress). */
    private val onRemoved: suspend (playlistId: Long) -> Unit = {},
    private val logger: RedactingLogger = RedactingLogger.NONE,
) {
    // ---- reads ----

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePlaylists(): Flow<List<PlaylistSummary>> =
        playlistStore.observePlaylists().flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(playlists.map(::observeSummary)) { it.toList() }
            }
        }.distinctUntilChanged()

    suspend fun playlists(): List<PlaylistSummary> = playlistStore.playlists().map { summarize(it) }

    suspend fun summary(playlistId: Long): PlaylistSummary? = playlistStore.playlist(playlistId)?.let { summarize(it) }

    private fun observeSummary(playlist: Playlist): Flow<PlaylistSummary> =
        combine(
            playlistStore.observeSyncState(playlist.id),
            catalogStore.observeState(playlist.id),
            catalogStore.observeAccountStatus(playlist.id),
            epgStore.observeState(playlist.id),
        ) { sync, catalog, account, epg -> Snapshot(sync, catalog, account, epg) }
            .map { build(playlist, it, xtreamAvailable(playlist.id)) }

    private class Snapshot(
        val sync: SyncStateEntity?,
        val catalog: CatalogSyncStateEntity?,
        val account: AccountStatusEntity?,
        val epg: EpgSourceStateEntity?,
    )

    private suspend fun summarize(playlist: Playlist): PlaylistSummary = build(
        playlist,
        Snapshot(
            playlistStore.syncState(playlist.id),
            catalogStore.state(playlist.id),
            catalogStore.accountStatus(playlist.id),
            epgStore.state(playlist.id),
        ),
        xtreamAvailable(playlist.id),
    )

    private fun build(playlist: Playlist, s: Snapshot, xtreamAvailable: Boolean): PlaylistSummary {
        val sync = s.sync
        val hasChannels = sync?.activeGeneration != null
        val health = when {
            sync?.lastFailureAt != null -> if (hasChannels) SyncHealth.UsingCachedCopy else SyncHealth.Failed
            hasChannels -> SyncHealth.Ok
            else -> SyncHealth.NeverSynced
        }
        val account = s.account?.takeIf { playlist.xtreamEnabled }?.let {
            AccountSummary(it.status, it.isActive, it.expDate, it.maxConnections, it.activeCons, it.updatedAt)
        }
        return PlaylistSummary(
            id = playlist.id,
            name = playlist.name,
            sourceType = playlist.sourceType,
            xtreamEnabled = playlist.xtreamEnabled,
            xtreamAvailable = xtreamAvailable,
            createdAt = playlist.createdAt,
            lastSuccessAt = sync?.lastSuccessAt,
            lastAttemptAt = sync?.lastAttemptAt,
            contentUpdatedAt = sync?.contentUpdatedAt,
            health = health,
            failureKind = sync?.lastFailureKind,
            failureMessage = sync?.lastFailureMessage,
            warning = sync?.lastWarning,
            catalogFailureKind = s.catalog?.lastFailureKind?.takeIf { playlist.xtreamEnabled },
            guideFailureKind = s.epg?.lastErrorKind,
            accountExpired = playlist.xtreamEnabled && s.catalog?.accountExpired == true,
            account = account,
            counts = StorageCounts(
                groups = if (hasChannels) sync.groupCount else 0,
                channels = if (hasChannels) sync.channelCount else 0,
                movies = if (playlist.xtreamEnabled && s.catalog?.activeGeneration != null) s.catalog.vodCount else 0,
                series = if (playlist.xtreamEnabled && s.catalog?.activeGeneration != null) s.catalog.seriesCount else 0,
                programmes = s.epg?.programmeCount ?: 0,
            ),
        )
    }

    /**
     * Same rule as the playlist sync: an account was derived, and either most playable entries are
     * Xtream-shaped or the source is a `get.php?username=&password=` URL.
     */
    suspend fun xtreamAvailable(playlistId: Long): Boolean {
        if (secureStore.account(playlistId) == null) return false
        val source = secureStore.sourceReference(playlistId)
        if (source is SourceReference.M3uUrl && M3uUrlSource.accountFromUrl(source.url) != null) return true
        val (playable, xtream) = playlistStore.playableChannelCounts(playlistId)
        return playable > 0 && xtream * 2 > playable
    }

    // ---- actions ----

    /** Adds a playlist and queues its first sync in the background (the onboarder syncs inline instead). */
    suspend fun add(name: String, source: SourceInput, xtreamEnabled: Boolean): PlaylistActionResult {
        val reference = when (val r = source.toReference()) {
            is SourceCheck.Invalid -> return PlaylistActionResult.Invalid(r.reason)
            is SourceCheck.Valid -> r.reference
        }
        val id = registry.add(name.trim(), reference, xtreamEnabled)
        scheduler.refreshNow(id)
        return PlaylistActionResult.Added(id)
    }

    suspend fun rename(playlistId: Long, name: String, authorizer: PlaylistAuthorizer = authorize): PlaylistActionResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return PlaylistActionResult.Invalid(InvalidReason.BlankName)
        playlistStore.playlist(playlistId) ?: return PlaylistActionResult.NotFound
        if (!authorizer(ProtectedAction.EditPlaylist)) return PlaylistActionResult.NotAuthorized
        playlistStore.renamePlaylist(playlistId, trimmed.take(MAX_NAME_LENGTH))
        return PlaylistActionResult.Done
    }

    /**
     * Replaces the playlist's source and re-syncs it. The old fingerprint is forgotten so the new
     * source downloads in full; a changed account (credentials or provider) is detected by the sync,
     * which marks the catalog and guide stale so they are re-fetched with the new account.
     */
    suspend fun editSource(playlistId: Long, source: SourceInput, authorizer: PlaylistAuthorizer = authorize): PlaylistActionResult {
        val reference = when (val r = source.toReference()) {
            is SourceCheck.Invalid -> return PlaylistActionResult.Invalid(r.reason)
            is SourceCheck.Valid -> r.reference
        }
        playlistStore.playlist(playlistId) ?: return PlaylistActionResult.NotFound
        if (!authorizer(ProtectedAction.EditPlaylist)) return PlaylistActionResult.NotAuthorized
        // Stop a download of the old source that may be running.
        scheduler.cancelAll(playlistId)
        secureStore.putSourceReference(playlistId, reference)
        playlistStore.changeSource(playlistId, reference.type)
        scheduler.refreshNow(playlistId)
        return PlaylistActionResult.Done
    }

    /**
     * Off: stops catalog/guide work, deletes the catalog (VOD, series, account status) and the
     * provider guide; live channels and an override guide stay. On: queues a forced catalog sync
     * and guide import.
     */
    suspend fun setXtreamEnabled(playlistId: Long, enabled: Boolean, authorizer: PlaylistAuthorizer = authorize): PlaylistActionResult {
        val playlist = playlistStore.playlist(playlistId) ?: return PlaylistActionResult.NotFound
        if (playlist.xtreamEnabled == enabled) return PlaylistActionResult.Done
        if (enabled && !xtreamAvailable(playlistId)) return PlaylistActionResult.XtreamUnavailable
        if (!authorizer(ProtectedAction.ChangeXtreamProcessing)) return PlaylistActionResult.NotAuthorized
        playlistStore.setXtreamEnabled(playlistId, enabled)
        if (enabled) {
            scheduler.syncCatalogAndGuide(playlistId, forceCatalog = true, forceGuide = true)
        } else {
            // Flag first: queued catalog/guide steps now skip; then stop a running one and clear.
            scheduler.cancelCatalogAndGuide(playlistId)
            catalogStore.clearPlaylist(playlistId)
            epgStore.clearProviderData(playlistId)
            // An override guide keeps its daily refresh (the guide step re-schedules it).
            if (secureStore.epgOverrideUrl(playlistId) != null) scheduler.syncCatalogAndGuide(playlistId)
        }
        return PlaylistActionResult.Done
    }

    /** Cancels all of the playlist's work and deletes its rows, stream URLs, secrets and EPG override. */
    suspend fun remove(playlistId: Long, authorizer: PlaylistAuthorizer = authorize): PlaylistActionResult {
        playlistStore.playlist(playlistId) ?: return PlaylistActionResult.NotFound
        if (!authorizer(ProtectedAction.RemovePlaylist)) return PlaylistActionResult.NotAuthorized
        scheduler.cancelAll(playlistId)
        registry.remove(playlistId) // every playlist-keyed table + SecureStore slots (source, account, EPG override)
        vault.deletePlaylist(playlistId)
        try {
            onRemoved(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w("Post-removal cleanup for playlist $playlistId failed", e)
        }
        return PlaylistActionResult.Done
    }

    suspend fun refresh(playlistId: Long): PlaylistActionResult {
        playlistStore.playlist(playlistId) ?: return PlaylistActionResult.NotFound
        scheduler.refreshNow(playlistId)
        return PlaylistActionResult.Done
    }

    suspend fun refreshAll() {
        scheduler.refreshAll()
    }

    /** Applies the Settings refresh interval to the periodic "refresh all" work. */
    suspend fun setRefreshInterval(hours: Int) {
        scheduler.schedulePeriodic(hours.toLong())
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

internal sealed interface SourceCheck {
    data class Invalid(val reason: InvalidReason) : SourceCheck
    data class Valid(val reference: SourceReference) : SourceCheck
}

/** Validates the input and converts it to the stored reference. */
internal fun SourceInput.toReference(): SourceCheck = when (this) {
    is SourceInput.DriveId ->
        if (id.isBlank()) SourceCheck.Invalid(InvalidReason.BlankDriveId) else SourceCheck.Valid(SourceReference.Drive(id.trim(), resourceKey?.trim()))
    is SourceInput.M3uUrl -> {
        val trimmed = url.trim()
        if (!M3uUrlSource.isValidUrl(trimmed)) SourceCheck.Invalid(InvalidReason.InvalidUrl) else SourceCheck.Valid(SourceReference.M3uUrl(trimmed))
    }
}

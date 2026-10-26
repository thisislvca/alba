package me.lvca.alba.engine.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.lvca.alba.engine.cache.MediaFileCache
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.CatalogCheckpointEntity
import me.lvca.alba.engine.database.CatalogItemEntity
import me.lvca.alba.engine.database.CatalogScanEntity
import me.lvca.alba.engine.database.CollectionEntity
import me.lvca.alba.engine.database.CollectionMemberEntity
import me.lvca.alba.engine.database.mediaDetails
import me.lvca.alba.engine.database.toEntity
import me.lvca.alba.engine.database.toModel
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.model.AvailabilityResolver
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.RefreshSummary
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.source.*
import me.lvca.alba.engine.source.CloudCatalogSource
import me.lvca.alba.engine.source.DeviceCatalogSource
import java.util.UUID

class DefaultGalleryRepository(
    private val database: AlbaDatabase,
    private val cloudSource: CloudCatalogSource,
    private val deviceSource: DeviceCatalogSource?,
    private val cache: MediaFileCache,
    private val now: () -> Long = System::currentTimeMillis,
    /** Waits between attempts while iCloud asks Alba to slow down; tests pass a recorder. */
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : GalleryRepository {
    private val dao = database.catalogDao()
    private val sync = database.catalogSyncDao()
    private val library = database.libraryDao()
    private val refreshLock = Mutex()

    /** One library sync at a time; edits never take it. */
    private val syncLock = Mutex()
    private val editLock = Mutex()
    private val previewPublicationLock = Mutex()
    private val catalogPermits = Semaphore(2)
    private val mediaLocks = Array(MEDIA_LOCK_STRIPES) { Mutex() }

    override suspend fun accountProfile() = cloudSource.accountProfile()
    override suspend fun storageUsage() = cloudSource.storageUsage()

    override suspend fun localStorageUsage() = cache.usage()

    override suspend fun clearLocalMedia(category: LocalMediaCategory) = refreshLock.withLock {
        exclusiveCatalog {
            val removed = cache.clear(category)
            database.withTransaction {
                removed.chunked(400).forEach { paths ->
                    dao.clearPreviewPaths(paths)
                    dao.clearViewerPaths(paths)
                    dao.clearOriginalPaths(paths)
                }
            }
        }
    }

    // Serialize edits with refresh so a snapshot obtained before an edit cannot overwrite it.
    private suspend fun <T> edit(block: suspend (String) -> T): T = refreshLock.withLock {
        // Wait for shared-album reads before taking the catalog permits, so a slow read never stalls downloads.
        editLock.withLock { exclusiveCatalog { block(cloudSource.accountLabel) } }
    }

    // Shared-album reads publish nothing locally, so they skip the refresh lock and catalog permits that made
    // opening a discussion wait for a whole library refresh. They still wait for edits, so a read never sees a
    // shared change half applied. The account check after the call refuses a result that straddled an account
    // switch; the refresh lock never fenced these reads, because sign-out changes the account label first and
    // only then clears the catalog under that lock.
    private suspend fun <T> readShared(block: suspend () -> T): T = editLock.withLock {
        val account = cloudSource.accountLabel
        block().also { checkAccount(account) }
    }

    /** Refuses to publish or return a result once the signed-in account is no longer [account]. */
    private fun checkAccount(account: String, message: String = "Check failed.") {
        check(cloudSource.accountLabel == account) { message }
    }

    private fun checkEditAccount(account: String) =
        checkAccount(account, StoredMessage.ACCOUNT_CHANGED_DURING_EDIT_REFRESH_TO_CHECK_THE_RESULT.code)

    override suspend fun setFavorite(mediaId: String, favorite: Boolean) = edit { account ->
        require(!SharedMediaIdentity.isShared(mediaId)) { "Shared albums use reactions, not personal favorites." }
        val local = dao.findById(mediaId)
        if (local?.origin == MediaOrigin.DEVICE.name) {
            val owner = "device:${local.sourceRevision.substringBefore(':')}"
            if (favorite) library.putMembers(listOf(CollectionMemberEntity(owner, GalleryQuery.FAVORITES, mediaId)))
            else library.removeMember(owner, GalleryQuery.FAVORITES, mediaId)
            dao.setLocalFavorite(mediaId, false)
            return@edit
        }
        requireCloudItem(mediaId)
        cloudSource.setFavorite(mediaId, favorite)
        checkEditAccount(account)
        if (favorite) library.putMembers(listOf(CollectionMemberEntity(account, GalleryQuery.FAVORITES, mediaId)))
        else library.removeMember(account, GalleryQuery.FAVORITES, mediaId)
    }

    override suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String, caption: String): Boolean = edit { account ->
        val album = requireNotNull(library.collection(account, id)).toModel()
        require(album.shared?.canContribute == true) { "You cannot add photos to this album." }
        val ids = mediaIds.distinct()
        require(ids.size in 1..50)
        ids.forEach { require(!SharedMediaIdentity.isShared(it)); requireNotNull(dao.findById(it)) }
        val cloud = ids.filter { requireNotNull(dao.findById(it)).origin == MediaOrigin.ICLOUD.name }
        val local = ids - cloud.toSet()
        // One contribution is one post; its caption goes on the first post written.
        var pendingCaption = caption.trim()
        var captionKept = true
        if (album.shared?.generation == SharedAlbumGeneration.MODERN && cloud.isNotEmpty()) {
            captionKept = cloudSource.contributeToSharedAlbum(id, cloud, operationId, pendingCaption)
            pendingCaption = ""
        }
        val files = if (album.shared?.generation == SharedAlbumGeneration.LEGACY) ids else local
        for (mediaId in files) {
            val item = requireNotNull(dao.findById(mediaId))
            require(!item.isTrashed && supportsLegacySharedUpload(MediaKind.valueOf(item.kind), item.mimeType)) {
                "Choose JPEG, PNG, HEIC, MP4 or MOV. Live Photo pairs need a library copy."
            }
            captionKept = uploadShared(account, id, operationId, "$operationId:$mediaId", item.fileName, pendingCaption) { output ->
                if (item.origin == MediaOrigin.DEVICE.name) requireNotNull(deviceSource).writeOriginal(requireNotNull(item.localUri), output)
                else if (cache.isReadable(item.originalCachePath)) java.io.File(item.originalCachePath!!).inputStream().use { it.copyTo(output) }
                else cloudSource.writeOriginal(mediaId, output)
            } && captionKept
            pendingCaption = ""
        }
        checkAccount(account, "Account changed. Refresh to check the contribution.")
        // Copies have their own remote identities. Only a fresh shared snapshot can publish membership.
        captionKept
    }

    override suspend fun contributeFilesToSharedAlbum(id: String, files: List<SharedUploadFile>, operationId: String, caption: String): Boolean = edit { account ->
        val album = requireNotNull(library.collection(account, id)).toModel()
        require(album.shared?.canContribute == true) { "You cannot add photos to this album." }
        require(files.size in 1..50)
        var pendingCaption = caption.trim()
        var captionKept = true
        files.forEachIndexed { index, file ->
            captionKept = uploadShared(account, id, operationId, "$operationId:file:$index", file.name, pendingCaption) { file.write(it) } && captionKept
            pendingCaption = ""
        }
        checkAccount(account, "Account changed. Refresh to check the contribution.")
        captionKept
    }

    /** Reads only the shared albums from Apple and counts them; the catalog is not touched. */
    override suspend fun sharedAlbumCounts() = readShared {
        val snapshot = cloudSource.sharedAlbums()
        val byId = snapshot.records.associateBy { it.id }
        snapshot.collections.collections.map { album ->
            val members = snapshot.collections.members[album.id].orEmpty().mapNotNull(byId::get)
            SharedAlbumCount(album.id, album.name, members.size, members.maxOfOrNull { it.addedAtEpochMillis ?: it.capturedAtEpochMillis } ?: 0)
        }
    }

    /** Stages one file, checks its format and uploads it to the shared album [id]. */
    private suspend fun uploadShared(account: String, id: String, operationId: String, stageKey: String, name: String, caption: String,
        write: suspend (java.io.OutputStream) -> Unit): Boolean {
        val staged = cache.writeAtomically("shared-stage:$stageKey", "shared") { output -> write(output) }
        return try {
            val prepared = prepareSharedUpload(staged)
            checkAccount(account, "Account changed before shared upload.")
            cloudSource.uploadSharedPhoto(id, prepared.format.fileName(name), prepared.source, operationId, caption)
        } finally { cache.delete(staged.absolutePath) }
    }

    override suspend fun sharedActivity(id: String, rank: Int) = readShared { cloudSource.sharedActivity(id, rank) }
    override suspend fun sharedPostDiscussion(id: String) = readShared { cloudSource.sharedPostDiscussion(id) }
    override suspend fun sharedPostPhotos(id: String) = readShared { cloudSource.sharedPostPhotos(id) }
    override suspend fun sharedPostPreviews(ids: List<String>) = readShared {
        require(ids.size <= SHARED_POST_PREVIEW_BATCH)
        if (ids.isEmpty()) emptyMap() else cloudSource.sharedPostPreviews(ids)
    }
    override suspend fun pendingSharedInvitations() = readShared { cloudSource.pendingSharedInvitations() }
    override suspend fun resolveSharedInvitation(url: String) = readShared { cloudSource.resolveSharedInvitation(url) }
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) = edit { account ->
        cloudSource.respondSharedInvitation(id, accept, operationId); checkAccount(account)
    }
    override suspend fun sharedManagement(id: String) = readShared { cloudSource.sharedManagement(id) }
    override suspend fun sharedDiscussion(mediaId: String) = readShared {
        requireCloudItem(mediaId)
        cloudSource.sharedDiscussion(mediaId)
    }
    override suspend fun changeSharedAlbum(id: String, command: SharedCommand, operationId: String) = edit { account ->
        val info = requireNotNull(library.collection(account, id)).toModel().shared ?: error("Not a shared album")
        if (command.action == SharedAction.SAVE_TO_LIBRARY && info.generation == SharedAlbumGeneration.LEGACY) {
            require(command.subject.substringBeforeLast(':') == id)
            val item = requireCloudItem(command.subject)
            val staged = cache.writeAtomically("shared-save:$operationId:${item.mediaId}", "shared") { cloudSource.writeOriginal(item.mediaId, it) }
            try {
                val prepared = prepareSharedUpload(staged)
                val format = prepared.format
                checkAccount(account)
                cloudSource.saveLegacySharedPhoto(item.mediaId, format.fileName(item.fileName), prepared.source)
            } finally { cache.delete(staged.absolutePath) }
        } else cloudSource.changeSharedAlbum(id, command, operationId)
        checkAccount(account, "Account changed. Refresh to check the result.")
    }

    override suspend fun createSharedAlbum(name: String, operationId: String, generation: me.lvca.alba.engine.model.SharedAlbumGeneration): GalleryCollection = edit { account ->
        val album = cloudSource.createSharedAlbum(validAlbumName(name), operationId, generation)
        checkEditAccount(account)
        library.putCollections(listOf(album.toEntity(account)))
        album
    }

    override suspend fun createAlbum(name: String): GalleryCollection = edit { account ->
        val album = cloudSource.createAlbum(validAlbumName(name))
        checkEditAccount(account)
        library.putCollections(listOf(CollectionEntity(account, album.id, album.name, album.parentId, album.isFolder, album.position)))
        album
    }

    override suspend fun deleteAlbum(id: String) = edit { account ->
        editableAlbum(account, id)
        cloudSource.deleteAlbum(id)
        checkEditAccount(account)
        database.withTransaction {
            library.deleteCollectionMembers(account, id)
            library.deleteCollection(account, id)
        }
    }

    override suspend fun renameAlbum(id: String, name: String) = edit { account ->
        val album = editableAlbum(account, id)
        val title = validAlbumName(name)
        cloudSource.renameAlbum(id, title)
        checkEditAccount(account)
        library.putCollections(listOf(album.copy(name = title)))
    }

    override suspend fun addToAlbum(id: String, mediaIds: List<String>) = edit { account ->
        editableAlbum(account, id)
        val ids = mediaIds.distinct()
        require(ids.isNotEmpty() && ids.size <= 50) { StoredMessage.SELECT_UP_TO_50_ICLOUD_PHOTOS_AT_A_TIME.code }
        ids.forEach { require(!SharedMediaIdentity.isShared(it)); requireCloudItem(it) }
        cloudSource.addToAlbum(id, ids)
        checkEditAccount(account)
        library.putMembers(ids.map { CollectionMemberEntity(account, id, it) })
    }

    override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) = edit { account ->
        editableAlbum(account, id)
        val ids = mediaIds.distinct()
        require(ids.isNotEmpty() && ids.size <= 50)
        ids.forEach { require(!SharedMediaIdentity.isShared(it)); requireCloudItem(it) }
        cloudSource.removeFromAlbum(id, ids)
        checkEditAccount(account)
        database.withTransaction { ids.forEach { library.removeMember(account, id, it) } }
    }

    override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) = edit { account ->
        val ids = mediaIds.distinct()
        require(ids.isNotEmpty() && ids.size <= 50)
        val items = ids.map { require(!SharedMediaIdentity.isShared(it)); requireCloudItem(it) }
        cloudSource.setCloudTrashed(ids, trashed)
        checkEditAccount(account)
        dao.upsertItems(items.map { it.copy(isTrashed = trashed) })
    }

    override suspend fun setHidden(mediaIds: List<String>, hidden: Boolean) = edit { account ->
        val ids = mediaIds.distinct()
        require(ids.isNotEmpty() && ids.size <= 50) { StoredMessage.SELECT_UP_TO_50_ICLOUD_PHOTOS_AT_A_TIME.code }
        val items = ids.map { require(!SharedMediaIdentity.isShared(it)); requireCloudItem(it) }
        require(items.none { it.isTrashed })
        cloudSource.setHidden(ids, hidden)
        checkEditAccount(account)
        dao.upsertItems(items.map { it.copy(isHidden = hidden) })
    }

    private suspend fun editableAlbum(account: String, id: String): CollectionEntity {
        require(!SharedMediaIdentity.isShared(id) && !id.startsWith("smart:") && !id.startsWith("device-folder:")) { StoredMessage.MEDIA_COLLECTIONS_CANNOT_BE_EDITED.code }
        return requireNotNull(library.collection(account, id)) { StoredMessage.ALBUM_IS_NO_LONGER_AVAILABLE_REFRESH_THE_LIBRARY.code }
            .also { require(!it.isFolder) { StoredMessage.CHOOSE_AN_ALBUM_NOT_A_FOLDER.code } }
    }
    private fun validAlbumName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 255) { StoredMessage.USE_AN_ALBUM_NAME_BETWEEN_1_AND_255_CHARACTERS.code }
    }

    override suspend fun exportOriginal(mediaId: String, output: java.io.OutputStream, motion: Boolean) = withMediaLock(mediaId) {
        val item = requireCloudItem(mediaId)
        require(!motion || item.kind == MediaKind.LIVE_PHOTO.name) { StoredMessage.THIS_PHOTO_HAS_NO_MOTION_CLIP.code }
        val path = if (motion) item.motionCachePath else item.originalCachePath
        if (cache.isReadable(path)) java.io.File(path!!).inputStream().use { it.copyTo(output) }
        else if (motion) cloudSource.writeMotion(mediaId, output) else cloudSource.writeOriginal(mediaId, output)
        Unit
    }

    override suspend fun findMedia(id: String): GalleryMedia? = dao.findById(id)?.let(::toModel)

    override fun observeGallery(): Flow<List<GalleryMedia>> = flow {
        // Cache writes do not invalidate catalog metadata. Reuse model conversion
        // and filesystem probes for unchanged records while thumbnails arrive.
        val models = mutableMapOf<String, CachedGalleryModel>()
        val metadata = combine(dao.observeAll().distinctUntilChanged(),
            library.observeMembers().distinctUntilChanged(), dao.observeDisplayLinks().distinctUntilChanged()) { entities, members, links ->
            val byMedia = members.groupBy { it.mediaId }
            val byId = entities.associateBy { it.mediaId }
            val devices = links.filter { it.accountId == cloudSource.writeAccountId }.groupBy { it.cloudId }
                .mapValues { (_, matches) -> matches.minBy { it.deviceId }.deviceId }
            entities.map { entity ->
                val collections = buildSet {
                    addAll(byMedia[entity.mediaId].orEmpty().filter { member ->
                        !member.account.startsWith("device:") || (entity.origin == MediaOrigin.DEVICE.name && member.account == "device:" + entity.sourceRevision.substringBefore(':'))
                    }.map { it.collectionId })
                    entity.deviceFolderId?.let(::add)
                    if (!SharedMediaIdentity.isShared(entity.mediaId) && entity.kind == MediaKind.VIDEO.name) add("smart:videos")
                    if (entity.deviceFolderName.equals("Screenshots", ignoreCase = true)) add("smart:screenshots")
                }
                GalleryMetadata(entity, collections, devices[entity.mediaId], devices[entity.mediaId]?.let { byId[it]?.localUri })
            }
        }
        combine(metadata, dao.observeCache().distinctUntilChanged()) { entries, cacheRows ->
            val caches = cacheRows.associateBy { it.mediaId }
            val ids = entries.mapTo(HashSet(entries.size)) { it.entity.mediaId }
            models.keys.retainAll(ids)
            entries.map { entry ->
                val cached = caches[entry.entity.mediaId]
                val previous = models[entry.entity.mediaId]
                if (previous?.metadata == entry && previous.cache == cached) previous.model
                else {
                    val base = if (previous?.metadata?.entity == entry.entity && previous.cache == cached) previous.model
                        else toModel(cached?.applyTo(entry.entity) ?: entry.entity)
                    val model = base.copy(collectionIds = entry.collections,
                        isFavorite = entry.entity.localFavorite || GalleryQuery.FAVORITES in entry.collections,
                        linkedDeviceId = entry.deviceId, linkedDeviceReference = entry.deviceReference)
                    models[entry.entity.mediaId] = CachedGalleryModel(entry, cached, model)
                    model
                }
            }
        }.collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    private data class CachedGalleryModel(val metadata: GalleryMetadata,
        val cache: me.lvca.alba.engine.database.MediaCacheEntity?, val model: GalleryMedia)

    private data class GalleryMetadata(val entity: CatalogItemEntity, val collections: Set<String>,
        val deviceId: String?, val deviceReference: String?)

    override fun observeCollections(): Flow<List<GalleryCollection>> = library.observeCollections().map { rows ->
        rows.map { it.toModel() }
    }

    override suspend fun openPlayback(mediaId: String, position: Long, length: Long): MediaRead {
        require(position >= 0 && length >= -1) { "Invalid playback range" }
        val item = requireCloudItem(mediaId)
        val playbackPath = if (item.kind == MediaKind.LIVE_PHOTO.name) item.motionCachePath else item.originalCachePath
        if (cache.isReadable(playbackPath)) {
            val file = java.io.RandomAccessFile(playbackPath!!, "r")
            file.seek(position)
            val available = (file.length() - position).coerceAtLeast(0).let { if (length < 0) it else minOf(it, length) }
            return object : MediaRead {
                override val length = available
                override val input = object : java.io.InputStream() {
                    private var remaining = available
                    override fun read(): Int = if (remaining == 0L) -1 else file.read().also { if (it >= 0) remaining-- }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else if (remaining == 0L) -1 else
                        file.read(b, off, minOf(len.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
                }
                override fun close() = file.close()
            }
        }
        return cloudSource.openPlayback(mediaId, position, length)
    }

    override suspend fun refreshDevice() = refreshLock.withLock { exclusiveCatalog {
        deviceSource?.let { publishDeviceScan(it.scanImages(), now()) }
        Unit
    } }

    private suspend fun publishDeviceScan(deviceRecords: List<DeviceMediaRecord>, refreshedAt: Long) {
        if (deviceSource != null) {
            val deviceScanId = UUID.randomUUID().toString()
            val favorites = dao.localFavorites().associateBy { it.mediaId }
            database.withTransaction {
                dao.upsertItems(
                    deviceRecords.map { record ->
                        CatalogItemEntity(
                            mediaId = record.id,
                            fileName = record.fileName,
                            capturedAtEpochMillis = record.capturedAtEpochMillis,
                            addedAtEpochMillis = record.addedAtEpochMillis,
                            width = record.width,
                            height = record.height,
                            origin = MediaOrigin.DEVICE.name,
                            mimeType = record.mimeType,
                            sourceRevision = record.sourceRevision,
                            localUri = record.contentUri,
                            localFavorite = favorites[record.id]?.let { it.sourceRevision.substringBefore(':') == record.sourceRevision.substringBefore(':') } ?: false,
                            kind = if (record.mimeType.startsWith("video/")) MediaKind.VIDEO.name else MediaKind.PHOTO.name,
                            durationMillis = record.durationMillis, byteCount = record.byteCount,
                            deviceFolderId = record.deviceFolderId, deviceFolderName = record.deviceFolderName,
                            isTrashed = record.isTrashed, expiresAtEpochMillis = record.expiresAtEpochMillis,
                            previewCachePath = null,
                            originalCachePath = null,
                            accentStartArgb = DEFAULT_DEVICE_ACCENT_START,
                            accentEndArgb = DEFAULT_DEVICE_ACCENT_END,
                            lastSeenScanId = deviceScanId,
                        )
                    },
                )
                dao.deleteMissing(MediaOrigin.DEVICE.name, deviceScanId)
                dao.upsertCheckpoint(
                    CatalogCheckpointEntity(
                        scope = "device:images",
                        changeToken = deviceScanId,
                        refreshedAtEpochMillis = refreshedAt,
                    ),
                )
            }
        }
    }

    override fun observeCatalogScan(): Flow<CatalogScanProgress?> = sync.observeScans().map { scans ->
        scans.firstOrNull { it.account == cloudSource.accountLabel }?.let { CatalogScanProgress(it.loadedCount, it.totalCount) }
    }.distinctUntilChanged()

    override suspend fun resumeCatalogScan(): RefreshSummary? = syncLock.withLock {
        if (sync.scan(cloudSource.accountLabel) == null) null else refreshUnderSyncLock(includeDeviceMedia = false)
    }

    override suspend fun refresh(includeDeviceMedia: Boolean): RefreshSummary = syncLock.withLock {
        refreshUnderSyncLock(includeDeviceMedia)
    }

    /**
     * Reads the library into the catalog page by page, never holding it in memory, then replaces what Recently Deleted,
     * Hidden, shared albums and albums list, and removes the rows the read no longer saw.
     *
     * Only one refresh runs at a time ([syncLock]). The refresh lock that edits share is taken only to write each page
     * and to publish, so favorites, trash, phone scans and signing out never wait for a long first read. Publishing
     * reads what edits change (albums, favorites, Recently Deleted, Hidden) again under that lock, so a page fetched
     * before an edit cannot undo it for long.
     */
    private suspend fun refreshUnderSyncLock(includeDeviceMedia: Boolean): RefreshSummary {
        val refreshedAt = now()
        val deviceRecords = refreshLock.withLock {
            val records = if (includeDeviceMedia && deviceSource != null) deviceSource.scanImages() else emptyList()
            if (includeDeviceMedia) exclusiveCatalog { publishDeviceScan(records, refreshedAt) }
            records
        }
        val account = cloudSource.accountLabel
        retainPreviewPaths()
        val libraryRead = syncLibrary(account)
        return refreshLock.withLock { publishRefresh(account, libraryRead, refreshedAt, deviceRecords.size) }
    }

    private suspend fun publishRefresh(account: String, libraryRead: LibraryRead, refreshedAt: Long, deviceCount: Int): RefreshSummary {
        val trash = throttled { cloudSource.recentlyDeleted() }
        val hiddenRead = hiddenSnapshot(account)
        val personalCollections = throttled { cloudSource.collections() }
        var sharedAlbumsUnavailable = false
        val shared = try { cloudSource.sharedAlbums() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            checkAccount(account, "Account changed during shared refresh")
            sharedAlbumsUnavailable = true
            // An optional service outage must not prevent the personal library from refreshing.
            // Preserve only this account's last complete shared snapshot, including its cached bytes.
            val previous = library.observeCollections().first().filter { it.account == account && it.sharedGeneration != null }
            val collectionIds = previous.map { it.collectionId }.toSet()
            val records = sync.cloudItemsWithPrefix("shared:$account:").map(::toRemote)
            val recordIds = records.map { it.id }.toSet()
            val members = library.observeMembers().first().filter { it.account == account && it.collectionId in collectionIds && it.mediaId in recordIds }
            SharedCatalogSnapshot(records, CollectionSnapshot(previous.map { it.toModel() },
                members.groupBy { it.collectionId }.mapValues { (_, rows) -> rows.map { it.mediaId }.toSet() }))
        }
        // The library rows are in the catalog already. These snapshots are small and replace their rows whole; Hidden
        // comes after Recently Deleted so the Hidden album's own reading wins for a photo in both.
        val snapshots = (trash + hiddenRead.records + shared.records).associateBy { it.id }.values.toList()
        val collections = CollectionSnapshot(personalCollections.collections + shared.collections.collections,
            personalCollections.members + shared.collections.members)
        checkAccount(account, StoredMessage.ACCOUNT_CHANGED_DURING_REFRESH.code)

        exclusiveCatalog {
            checkAccount(account, "Account changed before publishing refresh")
            database.withTransaction {
                // A finished read removes every row it did not see and could not find by name, as a snapshot did.
                libraryRead.completedScanId?.let { sync.unstampOutsideScan(it) }
                sync.unstampSnapshots()
                // Downloads continue during network sync. Read their current paths only once
                // publication owns the catalog, so refresh cannot discard a finished download.
                writeRecords(snapshots, libraryRead.stamp)
                sync.deleteUnstamped()
                val scope = "icloud:$account"
                // A read without a change token must not leave an older token behind for the next refresh.
                if (libraryRead.changeToken.isBlank()) sync.deleteCheckpoint(scope)
                else dao.upsertCheckpoint(CatalogCheckpointEntity(scope, libraryRead.changeToken, refreshedAt))
                sync.deleteScan(account)
                // A read under any other account is over: this one replaced its rows.
                sync.deleteOtherScans(account)
                library.clearMembers()
                library.clearCollections()
                library.putCollections(collections.collections.map { it.toEntity(account) })
                val present = collections.members.values.flatten().distinct().chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMapTo(HashSet()) { sync.existingIds(it) }
                library.putMembers(collections.members.flatMap { (id, members) ->
                    members.filter { it in present }.map { CollectionMemberEntity(account, id, it) }
                })
            }

            val pruneResult = cache.pruneTo(sync.cloudCachePaths().toSet())
            pruneResult.evictedPaths.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { paths ->
                dao.clearPreviewPaths(paths); dao.clearViewerPaths(paths)
            }
        }

        return RefreshSummary(
            cloudItemCount = sync.cloudCount(),
            deviceItemCount = deviceCount,
            refreshedAtEpochMillis = refreshedAt,
            sharedAlbumsUnavailable = sharedAlbumsUnavailable,
            hiddenUnavailable = !hiddenRead.complete,
        )
    }

    /** Upgrades old preview paths even if the upcoming network refresh is offline, a page of rows at a time. */
    private suspend fun retainPreviewPaths() {
        var after = ""
        do {
            val rows = exclusiveCatalog {
                sync.cloudItemsAfter(after, CATALOG_WALK_SIZE).onEach { item ->
                    val retained = cache.retainPreview(item.mediaId, item.previewCachePath)?.absolutePath
                    if (retained != null && retained != item.previewCachePath) dao.setPreviewPath(item.mediaId, retained)
                }
            }
            after = rows.lastOrNull()?.mediaId ?: after
        } while (rows.size == CATALOG_WALK_SIZE)
    }

    /**
     * How the library rows got into the catalog this refresh. [stamp] marks the rows seen; [completedScanId] is set
     * when a full read finished, so publishing removes every row it did not see.
     */
    private class LibraryRead(val stamp: String, val completedScanId: String?, val changeToken: String)

    /**
     * Brings the library rows up to date: continues an unfinished full read, applies changes since the saved token, or
     * starts a full read when there is no token or iCloud no longer accepts it.
     */
    private suspend fun syncLibrary(account: String): LibraryRead {
        if (sync.scan(account) == null) {
            val checkpoint = dao.checkpoint("icloud:$account")?.changeToken?.takeUnless { it.startsWith("rank:") }
            if (checkpoint != null) {
                val stamp = UUID.randomUUID().toString()
                try { return LibraryRead(stamp, null, applyChanges(checkpoint, stamp, account)) }
                catch (_: CatalogResetRequired) { /* Read the whole library again, below. */ }
            }
        }
        return try { readLibrary(account) }
        catch (_: CatalogResetRequired) {
            // The token captured before the read expired while it ran: read once more from the first page.
            writeLocked(account) { sync.deleteScan(account) }
            try { readLibrary(account) }
            catch (_: CatalogResetRequired) { abandonScan(account, "The library's change token expired twice during one read") }
        }
    }

    /**
     * The full library read. Each page is written to the catalog in its own transaction together with the cursor that
     * follows it, so a read cut short by process death, a lost connection or the system stopping background work
     * continues from the last page written. Rows already in the catalog stay browsable throughout, and new ones show
     * up as their pages land. Rows it did not see are looked up by name before publishing removes any.
     */
    private suspend fun readLibrary(account: String): LibraryRead {
        var scan = sync.scan(account) ?: startScan(account)
        var resumed = scan.cursor != null
        val visitedCursors = mutableSetOf<String?>()
        while (!scan.pagesComplete) {
            checkAccount(account, "Account changed during catalog refresh")
            if (!visitedCursors.add(scan.cursor)) abandonScan(account, "The library repeated a page cursor")
            val page = try { throttled { cloudSource.fetchPage(cursor = scan.cursor, limit = PAGE_SIZE) } }
            catch (rejected: CatalogCursorRejected) { if (resumed && !scan.restarted) null else throw rejected }
            catch (invalid: IllegalArgumentException) { if (resumed && !scan.restarted) null else throw invalid }
            resumed = false
            val current = scan
            scan = writeLocked(account) {
                check(sync.scan(account)?.scanId == current.scanId) { "The library read was discarded" }
                // A saved cursor the source refuses starts the read over once from the first page; rows stay.
                val next = if (page == null) current.copy(cursor = null, loadedCount = 0, restarted = true)
                    else current.copy(cursor = page.nextCursor, pagesComplete = page.nextCursor == null,
                        loadedCount = current.loadedCount + page.records.size)
                page?.let { writeRecords(it.records, current.scanId) }
                next.copy(updatedAtEpochMillis = now()).also { sync.upsertScan(it) }
            }
            if (page == null) visitedCursors.clear()
        }
        val token = scan.catchUpToken?.let { applyChanges(it, scan.scanId, account) }.orEmpty()
        keepUnseenRowsThatStillExist(account, scan.scanId)
        return LibraryRead(scan.scanId, scan.scanId, token)
    }

    /**
     * Looks up by name the account's library rows the read did not see, and keeps those still in the library. Pages
     * are read by position, so a photo deleted earlier in the library moves the rest up one place, and the photo that
     * crossed a page boundary is never on any page. Only rows iCloud no longer has are left for publishing to remove.
     */
    private suspend fun keepUnseenRowsThatStillExist(account: String, scanId: String) {
        var after = ""
        do {
            val rows = sync.unseenLibraryRows("icloud:$account:", scanId, after, LOOKUP_CHUNK)
            after = rows.lastOrNull()?.mediaId ?: after
            val names = rows.mapNotNull { it.assetRecordName }
            val existing = if (names.isEmpty()) emptyList() else throttled { cloudSource.libraryRecords(names) }.orEmpty()
            if (existing.isNotEmpty()) writeLocked(account) { writeRecords(existing, scanId) }
        } while (rows.size == LOOKUP_CHUNK)
    }

    private suspend fun startScan(account: String): CatalogScanEntity {
        // Capture the token first: catching up from it after the last page covers every change made during the read.
        val token = throttled { cloudSource.captureSyncToken() }
        val total = try { cloudSource.libraryItemCount() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val startedAt = now()
        val scan = CatalogScanEntity(account = account, scanId = UUID.randomUUID().toString(), cursor = null,
            pagesComplete = false, catchUpToken = token, loadedCount = 0, totalCount = total,
            startedAtEpochMillis = startedAt, updatedAtEpochMillis = startedAt)
        writeLocked(account) { sync.upsertScan(scan) }
        return scan
    }

    /** Discards the account's unfinished read, keeping every row, so the next refresh starts a fresh one. */
    private suspend fun abandonScan(account: String, reason: String): Nothing {
        writeLocked(account) { sync.deleteScan(account) }
        throw CatalogScanAbandoned(reason)
    }

    /** Writes to the catalog for [account] in one transaction, in turn with edits, and only while it is signed in. */
    private suspend fun <T> writeLocked(account: String, block: suspend () -> T): T = refreshLock.withLock {
        exclusiveCatalog {
            checkAccount(account, "Account changed before publishing page")
            database.withTransaction { block() }
        }
    }

    /** Applies library changes since [start] to the catalog a page at a time, and returns the token after them. */
    private suspend fun applyChanges(start: String, stamp: String, account: String): String {
        var token = start
        val visited = mutableSetOf<String>()
        val known = KnownCloudRecords { master -> sync.assetRecordNames(master) }
        do {
            checkAccount(account, "Account changed during sync")
            check(visited.add(token)) { "Repeated change cursor" }
            val page = throttled { cloudSource.changes(token, known) }
            writeLocked(account) {
                page.deletedRecordNames.toList().chunked(SQLITE_BIND_CHUNK_SIZE / 2).forEach { names ->
                    sync.unstampRecords(names, names)
                }
                writeRecords(page.upserts, stamp)
            }
            token = page.nextToken
        } while (page.moreComing)
        return token
    }

    /** Writes [records] stamped [stamp], keeping the cached files of rows whose resources did not change. */
    private suspend fun writeRecords(records: List<RemoteMediaRecord>, stamp: String) {
        if (records.isEmpty()) return
        val existing = records.map { it.id }.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap { dao.findByIds(it) }.associateBy { it.mediaId }
        dao.upsertItems(records.map { record ->
            catalogEntity(record, existing[record.id]?.takeIf { it.resourceFingerprint == record.resourceFingerprint }, stamp)
        })
    }

    /**
     * Runs [block], waiting and trying again while iCloud asks Alba to slow down: as long as it says, or with growing
     * waits when it does not. A wait longer than Alba keeps a refresh open for fails the refresh, so background work
     * can come back later.
     */
    private suspend fun <T> throttled(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            val outcome = runCatching { block() }
            val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
            attempt++
            pause(throttleWait(error, attempt) ?: throw error)
        }
    }

    /** How long to wait before attempt [attempt] after [error], or null when it is not throttling or waited enough. */
    private fun throttleWait(error: Throwable, attempt: Int): Long? {
        if (error is kotlinx.coroutines.CancellationException || attempt > MAX_THROTTLE_RETRIES) return null
        val throttle = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<ThrottledFailure>().firstOrNull()
            ?: return null
        val wait = throttle.retryAfterMillis?.coerceAtLeast(MIN_THROTTLE_WAIT_MILLIS)
            ?: (MIN_THROTTLE_WAIT_MILLIS shl (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT))
        return wait.takeIf { it <= MAX_THROTTLE_WAIT_MILLIS }
    }

    private class HiddenRead(val records: List<RemoteMediaRecord>, val complete: Boolean)

    /**
     * The Hidden album. An incomplete read keeps the last hidden rows rather than failing the whole refresh, and is
     * reported in the refresh summary so the user hears about it.
     */
    private suspend fun hiddenSnapshot(account: String): HiddenRead =
        try { HiddenRead(throttled { cloudSource.hiddenMedia() }, complete = true) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            checkAccount(account, "Account changed during hidden refresh")
            HiddenRead(sync.hiddenCloudItems("icloud:$account:").map(::toRemote), false)
        }

    override suspend fun ensureViewer(mediaId: String) = withMediaLock(mediaId) {
        val entity = requireCloudItem(mediaId)
        if (cache.isReadable(entity.viewerCachePath) || cache.isReadable(entity.originalCachePath) || entity.kind == MediaKind.VIDEO.name) return@withMediaLock
        previewPublicationLock.withLock {
            val file = cache.writeAtomically(mediaId, "viewer") { output -> cloudSource.writeDisplay(mediaId, output) }
            database.withTransaction {
                dao.setViewerPath(mediaId, file.absolutePath)
                cache.trimViewers().evictedPaths.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { paths ->
                    dao.clearPreviewPaths(paths); dao.clearViewerPaths(paths)
                }
            }
        }
    }

    override suspend fun ensurePreview(mediaId: String) = withMediaLock(mediaId) {
        val entity = requireCloudItem(mediaId)
        val retained = cache.retainPreview(mediaId, entity.previewCachePath)
        if (retained != null) {
            if (retained.absolutePath != entity.previewCachePath) dao.setPreviewPath(mediaId, retained.absolutePath)
            return@withMediaLock
        }
        // Only the same item's writes are serialized. A slow thumbnail or full-size
        // viewer must not hold a global lock over every visible tile's network request.
        val file = cache.writeAtomically(mediaId, "preview") { output -> cloudSource.writePreview(mediaId, output) }
        dao.setPreviewPath(mediaId, file.absolutePath)
    }

    override suspend fun keepOriginalOffline(mediaId: String) = withMediaLock(mediaId) {
        val entity = requireCloudItem(mediaId)
        if (!cache.isReadable(entity.originalCachePath)) {
            val file = cache.writeAtomically(mediaId, "original") { output -> cloudSource.writeOriginal(mediaId, output) }
            dao.setOriginalPath(mediaId, file.absolutePath)
        }
        if (entity.kind == MediaKind.LIVE_PHOTO.name && !cache.isReadable(entity.motionCachePath)) {
            val file = cache.writeAtomically(mediaId, "motion") { output -> cloudSource.writeMotion(mediaId, output) }
            dao.setMotionPath(mediaId, file.absolutePath)
        }
    }

    override suspend fun removeCachedOriginal(mediaId: String) = withMediaLock(mediaId) {
        val entity = requireCloudItem(mediaId)
        cache.delete(entity.originalCachePath)
        cache.delete(entity.motionCachePath)
        dao.setOriginalPath(mediaId, null)
        dao.setMotionPath(mediaId, null)
    }

    override suspend fun clearCloudCatalog() {
        refreshLock.withLock { exclusiveCatalog {
        cache.clearAll()
        database.withTransaction {
            dao.deleteByOrigin(MediaOrigin.ICLOUD.name)
            dao.deleteCheckpoints("icloud:%")
            sync.deleteScans()
            library.clearMembers()
            library.clearCollections()
        }
        } }
    }

    private fun toRemote(it: CatalogItemEntity) = RemoteMediaRecord(it.mediaId, it.fileName, it.capturedAtEpochMillis,
        it.width, it.height, it.sourceRevision, it.accentStartArgb, it.accentEndArgb,
        MediaKind.valueOf(it.kind), it.mimeType, it.durationMillis, it.masterRecordName, it.assetRecordName, it.resourceFingerprint, it.motionMimeType, it.addedAtEpochMillis,
        byteCount = it.byteCount, isHidden = it.isHidden, details = it.mediaDetails)

    private fun catalogEntity(record: RemoteMediaRecord, existing: CatalogItemEntity?, scanId: String) =
        CatalogItemEntity(
            mediaId = record.id,
            fileName = record.fileName,
            capturedAtEpochMillis = record.capturedAtEpochMillis,
            addedAtEpochMillis = record.addedAtEpochMillis,
            width = record.width,
            height = record.height,
            byteCount = record.byteCount,
            origin = MediaOrigin.ICLOUD.name,
            sourceRevision = record.sourceRevision,
            localUri = null,
            previewCachePath = existing?.previewCachePath,
            viewerCachePath = existing?.viewerCachePath,
            originalCachePath = existing?.originalCachePath,
            motionCachePath = existing?.motionCachePath,
            accentStartArgb = record.accentStartArgb,
            accentEndArgb = record.accentEndArgb,
            lastSeenScanId = scanId,
            kind = record.kind.name, mimeType = record.mimeType, durationMillis = record.durationMillis,
            masterRecordName = record.masterRecordName, assetRecordName = record.assetRecordName,
            resourceFingerprint = record.resourceFingerprint, motionMimeType = record.motionMimeType, isTrashed = record.isTrashed,
            isHidden = record.isHidden, details = record.details.takeIf { it != MediaDetails() },
        )

    private suspend fun requireCloudItem(mediaId: String): CatalogItemEntity {
        val entity = requireNotNull(dao.findById(mediaId)) { "Unknown media item: $mediaId" }
        require(entity.origin == MediaOrigin.ICLOUD.name) { "Device originals are not cache entries" }
        return entity
    }

    private suspend fun <T> withMediaLock(mediaId: String, block: suspend () -> T): T =
        catalogPermits.withPermit { mediaLocks[Math.floorMod(mediaId.hashCode(), mediaLocks.size)].withLock { block() } }

    private suspend fun <T> exclusiveCatalog(block: suspend () -> T): T = catalogPermits.withPermit {
        catalogPermits.withPermit { block() }
    }

    private fun toModel(entity: CatalogItemEntity): GalleryMedia {
        val origin = MediaOrigin.valueOf(entity.origin)
        val previewReadable = cache.isReadable(entity.previewCachePath)
        val originalReadable = cache.isReadable(entity.originalCachePath)
        return GalleryMedia(
            id = entity.mediaId,
            sourceRevision = entity.sourceRevision,
            fileName = entity.fileName,
            capturedAtEpochMillis = entity.capturedAtEpochMillis,
            addedAtEpochMillis = entity.addedAtEpochMillis,
            width = entity.width,
            height = entity.height,
            origin = origin,
            availability = AvailabilityResolver.resolve(origin, previewReadable, originalReadable &&
                (entity.kind != MediaKind.LIVE_PHOTO.name || cache.isReadable(entity.motionCachePath))),
            motionReference = entity.motionCachePath?.takeIf(cache::isReadable),
            motionMimeType = entity.motionMimeType,
            previewReference = when {
                origin == MediaOrigin.DEVICE -> entity.localUri
                previewReadable -> entity.previewCachePath
                else -> null
            },
            originalReference = when {
                origin == MediaOrigin.DEVICE -> entity.localUri
                originalReadable -> entity.originalCachePath
                else -> null
            },
            accentStartArgb = entity.accentStartArgb,
            accentEndArgb = entity.accentEndArgb,
            viewerReference = entity.viewerCachePath?.takeIf(cache::isReadable),
            byteCount = entity.byteCount ?: entity.originalCachePath?.takeIf { originalReadable }?.let { java.io.File(it).length() }, deviceFolderId = entity.deviceFolderId, deviceFolderName = entity.deviceFolderName,
            isTrashed = entity.isTrashed, expiresAtEpochMillis = entity.expiresAtEpochMillis,
            kind = MediaKind.valueOf(entity.kind), mimeType = entity.mimeType, durationMillis = entity.durationMillis,
            isHidden = entity.isHidden, details = entity.mediaDetails,
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val CATALOG_WALK_SIZE = 500
        const val LOOKUP_CHUNK = 200
        const val MAX_CAUSE_DEPTH = 8
        const val MAX_THROTTLE_RETRIES = 5
        const val MIN_THROTTLE_WAIT_MILLIS = 2_000L
        const val MAX_BACKOFF_SHIFT = 5
        const val MAX_THROTTLE_WAIT_MILLIS = 60_000L
        const val MEDIA_LOCK_STRIPES = 64
        const val SQLITE_BIND_CHUNK_SIZE = 900
        const val DEFAULT_DEVICE_ACCENT_START = 0xFF5C6670L
        const val DEFAULT_DEVICE_ACCENT_END = 0xFF1F252BL
    }
}

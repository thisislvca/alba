package dev.mela.engine.repository

import androidx.room.withTransaction
import dev.mela.engine.cache.MediaFileCache
import dev.mela.engine.database.CatalogCheckpointEntity
import dev.mela.engine.database.CatalogItemEntity
import dev.mela.engine.database.MelaDatabase
import dev.mela.engine.model.AvailabilityResolver
import dev.mela.engine.model.GalleryMedia
import dev.mela.engine.model.GalleryRepository
import dev.mela.engine.model.MediaOrigin
import dev.mela.engine.model.RefreshSummary
import dev.mela.engine.source.CloudCatalogSource
import dev.mela.engine.source.DeviceCatalogSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.mela.engine.model.*
import dev.mela.engine.source.*
import dev.mela.engine.database.toEntity
import dev.mela.engine.database.toModel
import dev.mela.engine.database.CollectionEntity
import dev.mela.engine.database.CollectionMemberEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DefaultGalleryRepository(
    private val database: MelaDatabase,
    private val cloudSource: CloudCatalogSource,
    private val deviceSource: DeviceCatalogSource?,
    private val cache: MediaFileCache,
    private val now: () -> Long = System::currentTimeMillis,
) : GalleryRepository {
    private val dao = database.catalogDao()
    private val library = database.libraryDao()
    private val refreshLock = Mutex()
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
        exclusiveCatalog { block(cloudSource.accountLabel) }
    }

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
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        if (favorite) library.putMembers(listOf(CollectionMemberEntity(account, GalleryQuery.FAVORITES, mediaId)))
        else library.removeMember(account, GalleryQuery.FAVORITES, mediaId)
    }

    override suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String) = edit { account ->
        val album = requireNotNull(library.collection(account, id)).toModel()
        require(album.shared?.canContribute == true) { "You cannot add photos to this album." }
        val ids = mediaIds.distinct()
        require(ids.size in 1..50)
        ids.forEach { require(!SharedMediaIdentity.isShared(it)); requireNotNull(dao.findById(it)) }
        val cloud = ids.filter { requireNotNull(dao.findById(it)).origin == MediaOrigin.ICLOUD.name }
        val local = ids - cloud.toSet()
        if (album.shared?.generation == SharedAlbumGeneration.MODERN && cloud.isNotEmpty()) {
            cloudSource.contributeToSharedAlbum(id, cloud, operationId)
        }
        val files = if (album.shared?.generation == SharedAlbumGeneration.LEGACY) ids else local
        for (mediaId in files) {
            val item = requireNotNull(dao.findById(mediaId))
            require(!item.isTrashed && supportsLegacySharedUpload(MediaKind.valueOf(item.kind), item.mimeType)) {
                "Choose JPEG, PNG, HEIC, MP4 or MOV. Live Photo pairs need a library copy."
            }
            val staged = cache.writeAtomically("shared-stage:$operationId:$mediaId", "shared") { output ->
                if (item.origin == MediaOrigin.DEVICE.name) requireNotNull(deviceSource).writeOriginal(requireNotNull(item.localUri), output)
                else if (cache.isReadable(item.originalCachePath)) java.io.File(item.originalCachePath!!).inputStream().use { it.copyTo(output) }
                else cloudSource.writeOriginal(mediaId, output)
            }
            try {
                val format = UploadMediaFormat.detect(staged.inputStream().use { it.readPrefix(4096) })
                UploadMediaValidator.validate(staged, format)
                check(cloudSource.accountLabel == account) { "Account changed before shared upload." }
                cloudSource.uploadSharedPhoto(id, format.fileName(item.fileName), stagedSource(staged), operationId)
            } finally { cache.delete(staged.absolutePath) }
        }
        check(cloudSource.accountLabel == account) { "Account changed. Refresh to check the contribution." }
        // Copies have their own remote identities. Only a fresh shared snapshot can publish membership.
    }

    private fun stagedSource(file: java.io.File): OneShotUploadSource {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); var count = input.read(buffer)
            while (count >= 0) { digest.update(buffer, 0, count); count = input.read(buffer) } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return object : OneShotUploadSource {
            private val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            override val byteCount = file.length()
            override val sha256Hex = hash
            override fun openOnce(): java.io.InputStream { check(opened.compareAndSet(false, true)); return file.inputStream() }
        }
    }
    override suspend fun sharedActivity(id: String, rank: Int) = edit { account -> cloudSource.sharedActivity(id, rank).also { check(cloudSource.accountLabel == account) } }
    override suspend fun sharedPostDiscussion(id: String) = edit { account -> cloudSource.sharedPostDiscussion(id).also { check(cloudSource.accountLabel == account) } }
    override suspend fun sharedPostPhotos(id: String) = edit { account -> cloudSource.sharedPostPhotos(id).also { check(cloudSource.accountLabel == account) } }
    override suspend fun pendingSharedInvitations() = edit { account -> cloudSource.pendingSharedInvitations().also { check(cloudSource.accountLabel == account) } }
    override suspend fun resolveSharedInvitation(url: String) = edit { account -> cloudSource.resolveSharedInvitation(url).also { check(cloudSource.accountLabel == account) } }
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) = edit { account ->
        cloudSource.respondSharedInvitation(id, accept, operationId); check(cloudSource.accountLabel == account)
    }
    override suspend fun sharedManagement(id: String) = edit { account ->
        cloudSource.sharedManagement(id).also { check(cloudSource.accountLabel == account) }
    }
    override suspend fun sharedDiscussion(mediaId: String) = edit { account ->
        requireCloudItem(mediaId)
        cloudSource.sharedDiscussion(mediaId).also { check(cloudSource.accountLabel == account) }
    }
    override suspend fun changeSharedAlbum(id: String, command: SharedCommand, operationId: String) = edit { account ->
        val info = requireNotNull(library.collection(account, id)).toModel().shared ?: error("Not a shared album")
        if (command.action == SharedAction.SAVE_TO_LIBRARY && info.generation == SharedAlbumGeneration.LEGACY) {
            require(command.subject.substringBeforeLast(':') == id)
            val item = requireCloudItem(command.subject)
            val staged = cache.writeAtomically("shared-save:$operationId:${item.mediaId}", "shared") { cloudSource.writeOriginal(item.mediaId, it) }
            try {
                val format = UploadMediaFormat.detect(staged.inputStream().use { it.readPrefix(4096) })
                UploadMediaValidator.validate(staged, format)
                check(cloudSource.accountLabel == account)
                cloudSource.saveLegacySharedPhoto(item.mediaId, format.fileName(item.fileName), stagedSource(staged))
            } finally { cache.delete(staged.absolutePath) }
        } else cloudSource.changeSharedAlbum(id, command, operationId)
        check(cloudSource.accountLabel == account) { "Account changed. Refresh to check the result." }
    }

    override suspend fun createSharedAlbum(name: String, operationId: String, generation: dev.mela.engine.model.SharedAlbumGeneration): GalleryCollection = edit { account ->
        val album = cloudSource.createSharedAlbum(validAlbumName(name), operationId, generation)
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        library.putCollections(listOf(album.toEntity(account)))
        album
    }

    override suspend fun createAlbum(name: String): GalleryCollection = edit { account ->
        val album = cloudSource.createAlbum(validAlbumName(name))
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        library.putCollections(listOf(CollectionEntity(account, album.id, album.name, album.parentId, album.isFolder, album.position)))
        album
    }

    override suspend fun deleteAlbum(id: String) = edit { account ->
        editableAlbum(account, id)
        cloudSource.deleteAlbum(id)
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        database.withTransaction {
            library.deleteCollectionMembers(account, id)
            library.deleteCollection(account, id)
        }
    }

    override suspend fun renameAlbum(id: String, name: String) = edit { account ->
        val album = editableAlbum(account, id)
        val title = validAlbumName(name)
        cloudSource.renameAlbum(id, title)
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        library.putCollections(listOf(album.copy(name = title)))
    }

    override suspend fun addToAlbum(id: String, mediaIds: List<String>) = edit { account ->
        editableAlbum(account, id)
        val ids = mediaIds.distinct()
        require(ids.isNotEmpty() && ids.size <= 50) { "Select up to 50 iCloud photos at a time." }
        ids.forEach { require(!SharedMediaIdentity.isShared(it)); requireCloudItem(it) }
        cloudSource.addToAlbum(id, ids)
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        library.putMembers(ids.map { CollectionMemberEntity(account, id, it) })
    }

    override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) = edit { account ->
        editableAlbum(account, id)
        val ids = mediaIds.distinct()
        require(ids.isNotEmpty() && ids.size <= 50)
        ids.forEach { require(!SharedMediaIdentity.isShared(it)); requireCloudItem(it) }
        cloudSource.removeFromAlbum(id, ids)
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        database.withTransaction { ids.forEach { library.removeMember(account, id, it) } }
    }

    override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) = edit { account ->
        val ids = mediaIds.distinct()
        require(ids.isNotEmpty() && ids.size <= 50)
        val items = ids.map { require(!SharedMediaIdentity.isShared(it)); requireCloudItem(it) }
        cloudSource.setCloudTrashed(ids, trashed)
        check(cloudSource.accountLabel == account) { "Account changed during edit. Refresh to check the result." }
        dao.upsertItems(items.map { it.copy(isTrashed = trashed) })
    }

    private suspend fun editableAlbum(account: String, id: String): CollectionEntity {
        require(!SharedMediaIdentity.isShared(id) && !id.startsWith("smart:") && !id.startsWith("device-folder:")) { "Media collections cannot be edited." }
        return requireNotNull(library.collection(account, id)) { "Album is no longer available. Refresh the library." }
            .also { require(!it.isFolder) { "Choose an album, not a folder." } }
    }
    private fun validAlbumName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 255) { "Use an album name between 1 and 255 characters." }
    }

    override suspend fun exportOriginal(mediaId: String, output: java.io.OutputStream, motion: Boolean) = withMediaLock(mediaId) {
        val item = requireCloudItem(mediaId)
        require(!motion || item.kind == MediaKind.LIVE_PHOTO.name) { "This photo has no motion clip." }
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
        val cache: dev.mela.engine.database.MediaCacheEntity?, val model: GalleryMedia)

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

    override suspend fun refresh(includeDeviceMedia: Boolean): RefreshSummary = refreshLock.withLock {
        val refreshedAt = now()
        val deviceRecords = if (includeDeviceMedia && deviceSource != null) {
            deviceSource.scanImages()
        } else {
            emptyList()
        }

        if (includeDeviceMedia) exclusiveCatalog { publishDeviceScan(deviceRecords, refreshedAt) }


        val account = cloudSource.accountLabel
        val scope = "icloud:$account"
        val oldItems = exclusiveCatalog {
            dao.cloudItems().map { item ->
                // Upgrade old paths even if the upcoming network refresh is offline.
                val retained = cache.retainPreview(item.mediaId, item.previewCachePath)?.absolutePath
                if (retained != null && retained != item.previewCachePath) {
                    dao.setPreviewPath(item.mediaId, retained)
                    item.copy(previewCachePath = retained)
                } else item
            }
        }
        val checkpoint = dao.checkpoint(scope)?.changeToken?.takeUnless { it.startsWith("rank:") }
        val activeCloud = try {
            if (checkpoint == null) bootstrap(account) else applyChanges(oldItems.filterNot { it.isTrashed || SharedMediaIdentity.isShared(it.mediaId) }.map(::toRemote), checkpoint, account)
        } catch (_: CatalogResetRequired) { bootstrap(account) }
        val trash = cloudSource.recentlyDeleted()
        val personalCollections = cloudSource.collections()
        var sharedAlbumsUnavailable = false
        val shared = try { cloudSource.sharedAlbums() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            check(cloudSource.accountLabel == account) { "Account changed during shared refresh" }
            sharedAlbumsUnavailable = true
            // An optional service outage must not prevent the personal library from refreshing.
            // Preserve only this account's last complete shared snapshot, including its cached bytes.
            val previous = library.observeCollections().first().filter { it.account == account && it.sharedGeneration != null }
            val collectionIds = previous.map { it.collectionId }.toSet()
            val records = oldItems.filter { it.mediaId.startsWith("shared:$account:") }.map(::toRemote)
            val recordIds = records.map { it.id }.toSet()
            val members = library.observeMembers().first().filter { it.account == account && it.collectionId in collectionIds && it.mediaId in recordIds }
            SharedCatalogSnapshot(records, CollectionSnapshot(previous.map { it.toModel() },
                members.groupBy { it.collectionId }.mapValues { (_, rows) -> rows.map { it.mediaId }.toSet() }))
        }
        val cloud = activeCloud.copy(records = (activeCloud.records + trash + shared.records).associateBy { it.id }.values.toList())
        val collections = CollectionSnapshot(personalCollections.collections + shared.collections.collections,
            personalCollections.members + shared.collections.members)
        check(cloudSource.accountLabel == account) { "Account changed during refresh" }
        val cloudScanId = UUID.randomUUID().toString()

        exclusiveCatalog {
            check(cloudSource.accountLabel == account) { "Account changed before publishing refresh" }
            // Downloads continue during network sync. Read their current paths only once
            // publication owns the catalog, so refresh cannot discard a finished download.
            val cachedById = dao.cloudItems().associateBy { it.mediaId }
            val retainedCachePaths = database.withTransaction {
                val entities = cloud.records.map { record ->
                    catalogEntity(record, cachedById[record.id]?.takeIf { it.resourceFingerprint == record.resourceFingerprint }, cloudScanId)
                }
                dao.upsertItems(entities)
                dao.deleteMissing(MediaOrigin.ICLOUD.name, cloudScanId)
                if (cloud.changeToken.isNotBlank()) dao.upsertCheckpoint(
                    CatalogCheckpointEntity(
                        scope = "icloud:${cloudSource.accountLabel}",
                        changeToken = cloud.changeToken,
                        refreshedAtEpochMillis = refreshedAt,
                    ),
                )
                library.clearMembers()
                library.clearCollections()
                library.putCollections(collections.collections.map { it.toEntity(account) })
                val ids = entities.map { it.mediaId }.toSet()
                library.putMembers(collections.members.flatMap { (id, members) -> members.filter { it in ids }.map { CollectionMemberEntity(account, id, it) } })
                buildSet(entities.size * 2) {
                    entities.forEach { item ->
                        item.previewCachePath?.let(::add)
                        item.viewerCachePath?.let(::add)
                        item.originalCachePath?.let(::add)
                        item.motionCachePath?.let(::add)
                    }
                }
            }

            val pruneResult = cache.pruneTo(retainedCachePaths)
            pruneResult.evictedPaths.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { paths ->
                dao.clearPreviewPaths(paths); dao.clearViewerPaths(paths)
            }

        }

        RefreshSummary(
            cloudItemCount = cloud.records.size,
            deviceItemCount = deviceRecords.size,
            refreshedAtEpochMillis = refreshedAt,
            sharedAlbumsUnavailable = sharedAlbumsUnavailable,
        )
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
            library.clearMembers()
            library.clearCollections()
        }
        } }
    }

    private fun toRemote(it: CatalogItemEntity) = RemoteMediaRecord(it.mediaId, it.fileName, it.capturedAtEpochMillis,
        it.width, it.height, it.sourceRevision, it.accentStartArgb, it.accentEndArgb,
        MediaKind.valueOf(it.kind), it.mimeType, it.durationMillis, it.masterRecordName, it.assetRecordName, it.resourceFingerprint, it.motionMimeType, it.addedAtEpochMillis, byteCount = it.byteCount)

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
        )

    private suspend fun bootstrap(account: String): CompleteCloudCatalog {
        val token = cloudSource.captureSyncToken()
        val full = readCompleteCloudCatalog(account)
        return if (token == null) full.copy(changeToken = "") else applyChanges(full.records, token, account)
    }

    private suspend fun applyChanges(initial: List<RemoteMediaRecord>, start: String, account: String): CompleteCloudCatalog {
        val records = initial.associateBy { it.id }.toMutableMap()
        var token = start
        val visited = mutableSetOf<String>()
        do {
            check(cloudSource.accountLabel == account) { "Account changed during sync" }
            check(visited.add(token)) { "Repeated change cursor" }
            val page = cloudSource.changes(token, records.values.toList())
            records.entries.removeAll { (_, value) -> value.assetRecordName in page.deletedRecordNames || value.masterRecordName in page.deletedRecordNames }
            page.upserts.forEach { records[it.id] = it }
            check(records.size <= MAX_CATALOG_ITEMS)
            token = page.nextToken
        } while (page.moreComing)
        return CompleteCloudCatalog(records.values.toList(), token)
    }

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

    private suspend fun readCompleteCloudCatalog(account: String): CompleteCloudCatalog {
        val records = mutableListOf<dev.mela.engine.source.RemoteMediaRecord>()
        val visitedCursors = mutableSetOf<String?>()
        var cursor: String? = null
        var changeToken = ""
        val partialScanId = UUID.randomUUID().toString()

        do {
            check(cloudSource.accountLabel == account) { "Account changed during catalog refresh" }
            check(visitedCursors.add(cursor)) { "Cloud catalog repeated cursor: $cursor" }
            val page = cloudSource.fetchPage(cursor = cursor, limit = PAGE_SIZE)
            check(records.size + page.records.size <= MAX_CATALOG_ITEMS) { "Cloud catalog exceeded safety limit" }
            // Make new photos visible during the first full scan. Never remove or
            // replace existing rows, prune files, or advance the token until it finishes.
            exclusiveCatalog {
                check(cloudSource.accountLabel == account) { "Account changed before publishing page" }
                val existing = dao.findByIds(page.records.map { it.id }).mapTo(HashSet()) { it.mediaId }
                dao.upsertItems(page.records.filterNot { it.id in existing }.map { catalogEntity(it, null, partialScanId) })
            }
            records += page.records
            check(records.size <= MAX_CATALOG_ITEMS) { "Cloud catalog exceeded v1 safety limit" }
            cursor = page.nextCursor
            changeToken = page.changeToken
        } while (cursor != null)

        return CompleteCloudCatalog(records = records, changeToken = changeToken)
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
        )
    }

    private data class CompleteCloudCatalog(
        val records: List<dev.mela.engine.source.RemoteMediaRecord>,
        val changeToken: String,
    )

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_CATALOG_ITEMS = 100_000
        const val MEDIA_LOCK_STRIPES = 64
        const val SQLITE_BIND_CHUNK_SIZE = 900
        const val DEFAULT_DEVICE_ACCENT_START = 0xFF5C6670L
        const val DEFAULT_DEVICE_ACCENT_END = 0xFF1F252BL
    }
}

private fun java.io.InputStream.readPrefix(limit: Int): ByteArray {
    require(limit >= 0)
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(buffer, offset, limit - offset)
        if (count < 0) break
        if (count == 0) continue
        offset += count
    }
    return if (offset == limit) buffer else buffer.copyOf(offset)
}

package me.lvca.alba.protocol.photos

import kotlinx.serialization.json.*
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.source.*
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.network.*
import me.lvca.alba.protocol.photos.SharedAlbumJournal.Companion.data
import me.lvca.alba.protocol.text
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared albums of both generations behind one [SharedAlbumOperations]. Every operation runs alone
 * on the signed-in session and is checked against it after each network call.
 */
internal class SharedAlbumsCatalog(
    private val transport: AppleHttpTransport,
    client: CloudKitClient,
    session: () -> AppleSessionSnapshot,
    private val account: () -> String,
    private val authorized: () -> Boolean,
    journal: UploadJournal?,
) : SharedAlbumOperations {
    /** The photos of the last snapshot, with their signed download URLs, by shared photo ID. */
    private val photos = ConcurrentHashMap<String, CloudPhotoAsset>()
    private val ids = SharedAlbumIds(account)
    private val journal = SharedAlbumJournal(journal, account)
    private val connection: SharedAlbumSession = SharedAlbumSession(client, session, authorized) {
        photos.clear(); legacy.forgetAccount()
    }
    private val legacy: LegacySharedAlbums = LegacySharedAlbums(connection, ids)
    private val modern = ModernSharedAlbums(connection, ids, transport)
    private val discussions = SharedAlbumDiscussions(::backend)
    private val transfers = SharedAlbumTransfers(transport, client, connection, ids, this.journal, account, authorized,
        ::discover, ::fresh, legacy, modern)
    private val invitations = SharedAlbumInvitations(connection, ids, this.journal, authorized, ::discover, legacy)
    private val actions = SharedAlbumActions(ids, this.journal, account, authorized, ::discover, ::backend, modern, discussions, transfers)

    private fun backend(album: SharedAlbumRecord): SharedAlbumBackend = if (album.isLegacy) legacy else modern

    private suspend fun discover(): List<SharedAlbumRecord> {
        val result = legacy.discover() + modern.discover()
        check(result.size <= 10000) { "Too many shared albums." }
        return result.distinctBy { it.collection.id }
    }

    private suspend fun fresh(id: String): SharedAlbumRecord {
        ids.requireOwnAlbum(id)
        return discover().firstOrNull { it.collection.id == id } ?: error("Shared album is no longer available.")
    }

    suspend fun snapshot(): SharedCatalogSnapshot = connection.exclusive {
        val current = connection.bound()
        val found = discover()
        val records = mutableListOf<RemoteMediaRecord>()
        val members = mutableMapOf<String, Set<String>>()
        val loaded = mutableMapOf<String, CloudPhotoAsset>()
        for (album in found) {
            val items = backend(album).readAlbum(album)
            val mapped = items.map { photo ->
                val id = ids.photo(album, photo.assetRecordName)
                loaded[id] = photo
                photo.toRemoteMediaRecord(id, SHARED_ACCENT_START, SHARED_ACCENT_END,
                    resourceFingerprint = photo.resourceFingerprint.ifBlank { "${album.ctag}:${photo.masterRecordName}" })
            }
            records += mapped
            check(records.size <= 100000) { "Shared library is too large for a single snapshot." }
            members[album.collection.id] = mapped.map { it.id }.toSet()
        }
        connection.checkSession(current)
        photos.clear(); photos.putAll(loaded)
        SharedCatalogSnapshot(records, CollectionSnapshot(found.map { it.collection }, members))
    }

    /** The photo [mediaId], read again from its album when [refresh] is set or it is not cached. */
    private suspend fun reference(mediaId: String, refresh: Boolean = false): CloudPhotoAsset = connection.exclusive {
        connection.bound()
        val id = ids.albumOfPhoto(mediaId)
        if (!refresh) photos[mediaId]?.let { return@exclusive it }
        val album = discover().firstOrNull { it.collection.id == id }
            ?: error("Access to this shared album is no longer available. Refresh the library.")
        val items = backend(album).readAlbum(album)
        photos.keys.removeAll { it.startsWith("$id:") }
        items.forEach { photos[ids.photo(album, it.assetRecordName)] = it }
        requireNotNull(photos[mediaId]) { "This shared photo is no longer available." }
    }

    suspend fun write(mediaId: String, output: OutputStream, kind: String) {
        fun url(photo: CloudPhotoAsset): String = when (kind) {
            "preview" -> photo.previewDownloadUrl
            "display" -> photo.displayDownloadUrl
            "motion" -> requireNotNull(photo.motionDownloadUrl)
            else -> photo.originalDownloadUrl
        }.also { check(it.isNotBlank()) { "No preview is available for this shared item." } }
        val current = connection.bound()
        val ref = reference(mediaId)
        try { transport.stream(url(ref), output) }
        catch (e: AppleMediaHttpException) {
            if (e.status !in EXPIRED_URL_STATUSES) throw e
            transport.stream(url(reference(mediaId, refresh = true)), output)
        }
        connection.checkSession(current)
    }

    suspend fun playback(mediaId: String, position: Long, length: Long): MediaRead {
        val current = connection.bound()
        var ref = reference(mediaId)
        val result = try { transport.openRange(ref.playbackUrl, position, length) }
        catch (e: AppleMediaHttpException) {
            if (e.status !in EXPIRED_URL_STATUSES) throw e
            ref = reference(mediaId, refresh = true)
            transport.openRange(ref.playbackUrl, position, length)
        }
        try { connection.checkSession(current) } catch (e: Exception) { result.close(); throw e }
        return result
    }

    /** A pending creation is recovered by account and name even after force-stop loses UI saved state. */
    suspend fun create(name: String, operationId: String, generation: SharedAlbumGeneration): GalleryCollection = connection.exclusive {
        require(name.isNotBlank() && name.length <= 255)
        UUID.fromString(operationId)
        check(authorized()) { "Sign in before creating a shared album." }
        val backend = if (generation == SharedAlbumGeneration.LEGACY) legacy else modern
        val key = SharedAlbumJournal.key("shared-create", "${account()}|$generation|$name")
        val old = journal.load(key)
        var data = old?.data()
        if (data == null || (old?.phase == "DONE" && data.text("operationId") != operationId)) {
            data = buildJsonObject { put("operationId", operationId); put("zoneName", backend.newZoneName()) }
            journal.save(key, "READY", data)
        }
        val pending = data
        val zoneName = requireNotNull(pending.text("zoneName"))
        val created = backend.create(name, zoneName, { phase -> journal.save(key, phase, pending) }, ::discover)
        journal.save(key, "DONE", pending)
        created.collection
    }

    suspend fun contribute(id: String, mediaIds: List<String>, operationId: String, caption: String = ""): Boolean = connection.exclusive {
        transfers.contribute(id, mediaIds, operationId, caption)
    }

    suspend fun saveLegacySharedPhoto(mediaId: String, fileName: String, source: OneShotUploadSource) = connection.exclusive {
        transfers.saveLegacyPhoto(mediaId, fileName, source)
    }

    /** Legacy shared streams have no caption on upload, so [caption] applies to modern albums only. */
    suspend fun upload(id: String, fileName: String, source: OneShotUploadSource, operationId: String, caption: String = ""): Boolean =
        connection.exclusive {
            if (ids.isLegacyAlbum(id)) {
                transfers.uploadToLegacy(id, fileName, source, operationId)
                caption.isBlank()
            } else transfers.uploadToModern(id, fileName, source, caption)
        }

    override suspend fun pendingSharedInvitations(): List<SharedInvitation> = connection.exclusive { invitations.pending() }

    override suspend fun resolveSharedInvitation(url: String): SharedInvitation = connection.exclusive { invitations.resolve(url) }

    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) = connection.exclusive {
        invitations.respond(id, accept, operationId)
    }

    override suspend fun sharedManagement(id: String): SharedAlbumManagement = connection.exclusive {
        val album = fresh(id)
        backend(album).management(album)
    }

    override suspend fun sharedActivity(id: String, rank: Int): SharedActivityPage = connection.exclusive {
        val album = fresh(id)
        require(!album.isLegacy); require(rank in 0..100000)
        modern.activity(album, rank)
    }

    override suspend fun sharedPostPhotos(id: String): List<String> = connection.exclusive {
        val album = fresh(ids.albumOfPost(id))
        val post = ids.postRecordName(album, id)
        modern.requirePost(album, post)
        modern.postPhotos(album, post, ModernSharedAlbums.MAX_POST_PHOTOS).photoIds
    }

    /** The album is discovered once for the whole batch, rather than once per post. Each post reads at most one page. */
    override suspend fun sharedPostPreviews(ids: List<String>): Map<String, SharedPostPreview> = connection.exclusive {
        require(ids.size in 1..SHARED_POST_PREVIEW_BATCH)
        val albumId = this.ids.albumOfPost(ids.first())
        require(ids.all { this.ids.albumOfPost(it) == albumId }) { "Post previews are read one album at a time." }
        val album = fresh(albumId)
        require(!album.isLegacy)
        ids.distinct().associateWith { id ->
            val post = this.ids.postRecordName(album, id)
            // One small read per post: the photos shown and a few more to count, never the whole post.
            val photos = modern.postPhotos(album, post, ModernSharedAlbums.PREVIEW_COUNT_PROBE)
            SharedPostPreview(photos.photoIds.take(SHARED_POST_PREVIEW_PHOTOS), photos.photoIds.size, photos.complete, modern.reactionCount(album, post))
        }
    }

    override suspend fun sharedPostDiscussion(id: String): SharedDiscussion = connection.exclusive {
        val album = fresh(ids.albumOfPost(id))
        val post = ids.postRecordName(album, id)
        modern.requirePost(album, post)
        discussions.discussion(album, post, post = true)
    }

    override suspend fun sharedDiscussion(mediaId: String): SharedDiscussion = connection.exclusive {
        val album = fresh(ids.albumOfPhoto(mediaId))
        discussions.discussion(album, ids.recordName(album, mediaId))
    }

    override suspend fun changeSharedAlbum(id: String, command: SharedCommand, operationId: String) = connection.exclusive {
        actions.change(id, command, operationId)
    }

    private companion object {
        const val SHARED_ACCENT_START = 0xff294e63
        const val SHARED_ACCENT_END = 0xff497187

        /** Statuses of a signed download URL that expired; the photo is read again for a fresh one. */
        val EXPIRED_URL_STATUSES = setOf(401, 403, 404, 410)
    }
}

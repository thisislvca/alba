package me.lvca.alba.protocol.fixture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.lvca.alba.engine.model.*
import me.lvca.alba.engine.source.CloudCatalogPage
import me.lvca.alba.engine.source.CloudCatalogSource
import me.lvca.alba.engine.source.RemoteMediaRecord
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class FixtureICloudCatalogSource(
    private val includeSharedAlbums: Boolean = false,
    private val openPhoto: ((Int) -> java.io.InputStream)? = null,
    private val openVideo: (() -> java.io.InputStream)? = null,
    private val uploadDirectory: File? = null,
) : CloudCatalogSource {
    override val accountLabel: String = "demo-library"

    private val fixtures = fixtureRecords()
    private val recordsById = fixtures.associateBy(FixtureRecord::recordId).toMutableMap()
    private val extraRecords = java.util.concurrent.ConcurrentHashMap<String, RemoteMediaRecord>()
    private val imported = java.util.concurrent.ConcurrentHashMap<String, DemoImportedMedia>()
    private val importLock = Mutex()
    private fun personalRecords() = fixtures.map { it.toRemoteRecord() } + extraRecords.values.filter { it.id.startsWith("fixture:") }
    private fun record(id: String) = extraRecords[id] ?: requireFixture(id).toRemoteRecord().copy(id = id)
    private fun sharedIds(id: String) = (sharedFixtures.keys + extraRecords.keys).filter { it.startsWith("$id:") }
    private fun copyMedia(from: String, to: String) {
        extraRecords[to] = record(from).copy(id = to, addedAtEpochMillis = System.currentTimeMillis())
        imported[from]?.let { imported[to] = it } ?: run { recordsById[to] = requireFixture(from) }
    }
    private val sharedAlbums = mutableListOf(
        me.lvca.alba.engine.model.GalleryCollection("shared:demo-library:legacy:owner:one", "Family archive", position = 0,
            shared = me.lvca.alba.engine.model.SharedAlbumInfo(me.lvca.alba.engine.model.SharedAlbumGeneration.LEGACY, me.lvca.alba.engine.model.SharedAlbumRole.VIEWER)),
        me.lvca.alba.engine.model.GalleryCollection("shared:demo-library:private:owner:two", "Family moments", position = 1,
            shared = me.lvca.alba.engine.model.SharedAlbumInfo(me.lvca.alba.engine.model.SharedAlbumGeneration.MODERN, me.lvca.alba.engine.model.SharedAlbumRole.OWNER, participantCount = 1)),
    ).apply {
        listOf("Weekends away", "Good company", "Little moments", "Around town", "Sunday mornings", "Our favorites")
            .forEachIndexed { index, name ->
                add(me.lvca.alba.engine.model.GalleryCollection("shared:demo-library:private:owner:album-${index + 3}", name, position = (index + 2).toLong(),
                    shared = me.lvca.alba.engine.model.SharedAlbumInfo(me.lvca.alba.engine.model.SharedAlbumGeneration.MODERN, me.lvca.alba.engine.model.SharedAlbumRole.OWNER, participantCount = 1)))
            }
    }

    // Reuse the compact bundled media while keeping stable, album-scoped identities.
    private val sharedFixtures = mutableMapOf<String, FixtureRecord>().apply {
        val photosByAlbum = listOf(
            listOf(11, 12, 13, 14), listOf(19, 7, 11, 14, 18),
            listOf(5, 8, 10, 15, 18), listOf(6, 7, 11, 12, 14, 3, 4, 5, 8, 10),
            listOf(7, 11, 12, 13, 14), listOf(8, 12, 15, 17, 18),
            listOf(9, 10, 11, 12, 14), listOf(10, 11, 13, 14, 16, 18),
        )
        sharedAlbums.forEachIndexed { albumIndex, album ->
            photosByAlbum[albumIndex].forEach { photoIndex ->
                val suffix = when {
                    albumIndex == 0 && photoIndex == 11 -> "photo"
                    albumIndex == 1 && photoIndex == 19 -> "video"
                    else -> "photo-$photoIndex"
                }
                put("${album.id}:$suffix", fixtures.first { it.index == photoIndex })
            }
        }
    }
    private val discussions = mutableMapOf<String, MutableList<me.lvca.alba.engine.model.SharedComment>>()
    private val management = mutableMapOf<String, me.lvca.alba.engine.model.SharedAlbumManagement>()
    private val sharedDone = mutableSetOf<String>()
    override suspend fun sharedActivity(id: String, rank: Int): me.lvca.alba.engine.model.SharedActivityPage {
        val album = sharedAlbums.first { it.id == id }; require(album.shared?.generation == me.lvca.alba.engine.model.SharedAlbumGeneration.MODERN)
        return me.lvca.alba.engine.model.SharedActivityPage(if (rank == 0) listOf(me.lvca.alba.engine.model.SharedPost("post:$id:demo-post", "Demo owner", true, 1790205441000, album.name)) else emptyList(), null)
    }
    override suspend fun sharedPostPhotos(id: String): List<String> = sharedIds(id.removePrefix("post:").substringBeforeLast(':')).take(4)
    override suspend fun sharedPostDiscussion(id: String): me.lvca.alba.engine.model.SharedDiscussion {
        val album = sharedAlbums.first { it.id == id.removePrefix("post:").substringBeforeLast(':') }
        return me.lvca.alba.engine.model.SharedDiscussion(discussions[id].orEmpty().toList(), album.shared!!.canComment, album.shared!!.generation)
    }
    override suspend fun sharedManagement(id: String): me.lvca.alba.engine.model.SharedAlbumManagement {
        val album = sharedAlbums.first { it.id == id }
        return (management[id] ?: me.lvca.alba.engine.model.SharedAlbumManagement(album,
            listOf(me.lvca.alba.engine.model.SharedParticipant("demo-owner", "Demo owner", me.lvca.alba.engine.model.SharedAlbumRole.OWNER, isCurrentUser = album.shared?.canManage == true)), allowContributions = true,
            requests = if (id.endsWith(":two")) listOf(SharedAccessRequest("sample-alex", "Alex (sample)"), SharedAccessRequest("sample-sam", "Sam (sample)")) else emptyList(),
            blocked = if (id.endsWith(":two")) listOf(SharedAccessRequest("sample-jamie", "Jamie (sample)")) else emptyList())).copy(album = album)
    }
    override suspend fun sharedDiscussion(mediaId: String): me.lvca.alba.engine.model.SharedDiscussion {
        require(mediaId in sharedIds(mediaId.substringBeforeLast(':')))
        val info = requireNotNull(sharedAlbums.first { it.id == mediaId.substringBeforeLast(':') }.shared)
        return me.lvca.alba.engine.model.SharedDiscussion(discussions[mediaId].orEmpty().toList(), info.canComment, info.generation, canRemovePhoto = info.canManage)
    }
    override suspend fun changeSharedAlbum(id: String, command: me.lvca.alba.engine.model.SharedCommand, operationId: String) {
        if (operationId in sharedDone) return
        val state = sharedManagement(id); val info = requireNotNull(state.album.shared)
        val comments = discussions.getOrPut(command.subject) { mutableListOf() }
        when(command.action) {
            me.lvca.alba.engine.model.SharedAction.POST_COMMENT, me.lvca.alba.engine.model.SharedAction.POST_REACTION, me.lvca.alba.engine.model.SharedAction.COMMENT, me.lvca.alba.engine.model.SharedAction.REACTION -> {
                require(info.canComment && command.subject.removePrefix("post:").substringBeforeLast(':') == id)
                val reaction = command.action in setOf(me.lvca.alba.engine.model.SharedAction.REACTION, me.lvca.alba.engine.model.SharedAction.POST_REACTION)
                if (reaction) comments.removeAll { it.isMine && it.reaction }
                if (command.value.isNotBlank()) comments += me.lvca.alba.engine.model.SharedComment(operationId, "You", command.value, System.currentTimeMillis(), true, true, reaction)
            }
            me.lvca.alba.engine.model.SharedAction.POST_DELETE_COMMENT, me.lvca.alba.engine.model.SharedAction.DELETE_COMMENT -> { require(comments.any { it.id == command.value && it.canDelete }); comments.removeAll { it.id == command.value } }
            me.lvca.alba.engine.model.SharedAction.REMOVE_MEDIA -> { require(info.canManage); require(command.subject in sharedIds(id)); sharedFixtures.remove(command.subject); extraRecords.remove(command.subject); discussions.remove(command.subject) }
            me.lvca.alba.engine.model.SharedAction.SAVE_TO_LIBRARY -> { require(command.subject in sharedIds(id)); copyMedia(command.subject, "fixture:saved:$operationId") }
            me.lvca.alba.engine.model.SharedAction.LEAVE -> { require(info.role != me.lvca.alba.engine.model.SharedAlbumRole.OWNER); sharedAlbums.removeAll { it.id == id }; sharedFixtures.keys.removeAll { it.startsWith("$id:") }; extraRecords.keys.removeAll { it.startsWith("$id:") } }
            else -> {
                require(info.canManage)
                when(command.action) {
                    me.lvca.alba.engine.model.SharedAction.CREATE_INVITE_LINK -> management[id] = state.copy(invitationLinks = state.invitationLinks + "https://example.invalid/alba-demo-invitation/$operationId")
                    me.lvca.alba.engine.model.SharedAction.REVOKE_INVITE_LINKS -> management[id] = state.copy(invitationLinks = emptyList())
                    me.lvca.alba.engine.model.SharedAction.UNBLOCK -> management[id] = state.copy(blocked = state.blocked.filterNot { it.id == command.subject })
                    SharedAction.APPROVE_REQUEST -> {
                        val person = state.requests.first { it.id == command.subject }
                        management[id] = state.copy(requests = state.requests - person,
                            participants = state.participants + SharedParticipant(person.id, person.name, SharedAlbumRole.CONTRIBUTOR))
                    }
                    me.lvca.alba.engine.model.SharedAction.DENY_REQUEST -> {
                        val person = state.requests.first { it.id == command.subject }
                        management[id] = state.copy(requests = state.requests - person, blocked = state.blocked + person)
                    }
                    SharedAction.BLOCK -> {
                        val person = state.participants.first { it.id == command.subject && it.role != SharedAlbumRole.OWNER }
                        management[id] = state.copy(participants = state.participants - person,
                            blocked = state.blocked + SharedAccessRequest(person.id, person.name))
                    }
                    me.lvca.alba.engine.model.SharedAction.RENAME -> sharedAlbums[sharedAlbums.indexOfFirst { it.id == id }] = state.album.copy(name = command.value)
                    me.lvca.alba.engine.model.SharedAction.INVITE -> management[id] = state.copy(participants = state.participants + me.lvca.alba.engine.model.SharedParticipant(operationId, command.value, me.lvca.alba.engine.model.SharedAlbumRole.CONTRIBUTOR, pending = true))
                    me.lvca.alba.engine.model.SharedAction.REMOVE_PARTICIPANT -> management[id] = state.copy(participants = state.participants.filter { it.id != command.subject })
                    me.lvca.alba.engine.model.SharedAction.ROLE -> management[id] = state.copy(participants = state.participants.map { if (it.id != command.subject) it else it.copy(role = when(command.value) { "manager" -> me.lvca.alba.engine.model.SharedAlbumRole.MANAGER; "commenter" -> me.lvca.alba.engine.model.SharedAlbumRole.COMMENTER; else -> me.lvca.alba.engine.model.SharedAlbumRole.CONTRIBUTOR }) })
                    me.lvca.alba.engine.model.SharedAction.PUBLIC_ACCESS -> management[id] = state.copy(publicAccess = command.enabled, publicUrl = if (command.enabled) "https://example.invalid/alba-demo/$id" else null)
                    me.lvca.alba.engine.model.SharedAction.CONTRIBUTIONS -> management[id] = state.copy(allowContributions = command.enabled)
                    me.lvca.alba.engine.model.SharedAction.ACCESS_REQUESTS -> management[id] = state.copy(allowAccessRequests = command.enabled)
                    me.lvca.alba.engine.model.SharedAction.TEMPORARY -> management[id] = state.copy(temporary = command.enabled)
                    me.lvca.alba.engine.model.SharedAction.DELETE_ALBUM -> { sharedAlbums.removeAll { it.id == id }; sharedFixtures.keys.removeAll { it.startsWith("$id:") }; extraRecords.keys.removeAll { it.startsWith("$id:") } }
                    me.lvca.alba.engine.model.SharedAction.COVER -> {
                        require(command.subject in sharedIds(id))
                        sharedAlbums[sharedAlbums.indexOfFirst { it.id == id }] = state.album.copy(coverMediaId = command.subject)
                    }
                    else -> error("Unsupported demo action")
                }
            }
        }
        sharedDone += operationId
    }

    override suspend fun sharedAlbums(): me.lvca.alba.engine.source.SharedCatalogSnapshot {
        val shown = if (includeSharedAlbums) sharedAlbums.toList() else emptyList()
        return me.lvca.alba.engine.source.SharedCatalogSnapshot(shown.flatMap { sharedIds(it.id) }.distinct().map(::record),
            me.lvca.alba.engine.source.CollectionSnapshot(shown, shown.associate { it.id to sharedIds(it.id).toSet() }))
    }

    private val invitations = mutableListOf(
        SharedInvitation("demo-invite-modern", "Sample weekend together", SharedAlbumGeneration.MODERN, true),
        SharedInvitation("demo-invite-legacy", "Sample family trip", SharedAlbumGeneration.LEGACY, true),
    )
    override suspend fun pendingSharedInvitations() = if (includeSharedAlbums) invitations.toList() else emptyList()
    override suspend fun resolveSharedInvitation(url: String): SharedInvitation = invitations.firstOrNull {
        url == "https://example.invalid/alba-demo/invitation/${it.id}"
    } ?: error("Use one of the sample invitations in Demo mode")
    override suspend fun respondSharedInvitation(id: String, accept: Boolean, operationId: String) {
        if (operationId in sharedDone) return
        val invitation = invitations.first { it.id == id }
        if (accept) {
            val album = GalleryCollection("shared:demo-library:invited:$id", invitation.name,
                shared = SharedAlbumInfo(invitation.generation, SharedAlbumRole.CONTRIBUTOR, "Sample friend", 2))
            sharedAlbums += album
            listOf(1, 2, 3).forEach { index -> sharedFixtures["${album.id}:photo-$index"] = fixtures.first { it.index == index } }
        }
        invitations.remove(invitation)
        sharedDone += operationId
    }

    /** Copies are session-local and cannot create upload verification or reclaim eligibility. */
    override suspend fun saveLegacySharedPhoto(mediaId: String, fileName: String, source: OneShotUploadSource) {
        require(mediaId in sharedIds(mediaId.substringBeforeLast(':')))
        importMedia(fileName, source.openOnce(), "fixture:saved:${java.util.UUID.randomUUID()}", source.byteCount, source.sha256Hex)
    }
    suspend fun importMedia(fileName: String, input: InputStream, id: String = "fixture:upload:${java.util.UUID.randomUUID()}",
        expectedBytes: Long? = null, expectedHash: String? = null): String = input.use { stream ->
        importLock.withLock {
            val media = DemoImportedMedia.copy(requireNotNull(uploadDirectory), fileName, stream, expectedBytes, expectedHash)
            imported[id] = media
            extraRecords[id] = media.record(id)
            id
        }
    }

    override suspend fun createSharedAlbum(name: String, operationId: String, generation: me.lvca.alba.engine.model.SharedAlbumGeneration): me.lvca.alba.engine.model.GalleryCollection {
        val id = "shared:demo-library:${generation.name}:owner:$operationId"
        return sharedAlbums.firstOrNull { it.id == id } ?: me.lvca.alba.engine.model.GalleryCollection(id, name,
            shared = me.lvca.alba.engine.model.SharedAlbumInfo(generation, me.lvca.alba.engine.model.SharedAlbumRole.OWNER, participantCount = 1))
            .also { sharedAlbums += it }
    }
    override suspend fun uploadSharedPhoto(albumId: String, fileName: String, source: me.lvca.alba.engine.model.OneShotUploadSource, operationId: String, caption: String) {
        require(sharedAlbums.first { it.id == albumId }.shared?.canContribute == true)
        val id = "$albumId:$operationId-${source.sha256Hex}"
        if (extraRecords.containsKey(id)) return
        importMedia(fileName, source.openOnce(), id, source.byteCount, source.sha256Hex)
    }
    override suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String, caption: String) {
        require(sharedAlbums.first { it.id == id }.shared?.canContribute == true)
        mediaIds.forEach { media -> copyMedia(media, "$id:$operationId-${media.hashCode()}") }
    }
    private val trashed = mutableSetOf<String>()
    override suspend fun captureSyncToken() = CHANGE_TOKEN
    override suspend fun changes(token: String, known: List<RemoteMediaRecord>): me.lvca.alba.engine.source.CloudChanges {
        if (token != CHANGE_TOKEN) throw me.lvca.alba.engine.source.CatalogResetRequired()
        val records = personalRecords()
        val current = records.map { it.id }.toSet()
        return me.lvca.alba.engine.source.CloudChanges(records.filterNot { it.id in trashed },
            trashed + known.map { it.id }.filterNot { it in current }, CHANGE_TOKEN, false)
    }
    private val albums = mutableListOf(
        me.lvca.alba.engine.model.GalleryCollection("demo:journeys", "Journeys", isFolder = true),
        me.lvca.alba.engine.model.GalleryCollection("demo:coast", "By the sea", "demo:journeys"),
        me.lvca.alba.engine.model.GalleryCollection("demo:days", "Everyday", position = 1))
    private val members = mutableMapOf(
        "demo:coast" to fixtures.filter { it.scene == Scene.COAST }.map { it.recordId }.toSet(),
        "demo:days" to fixtures.filter { it.scene != Scene.COAST }.map { it.recordId }.toSet(),
        me.lvca.alba.engine.model.GalleryQuery.FAVORITES to fixtures.filter { it.index % 3 == 1 }.map { it.recordId }.toSet())
    override suspend fun collections(): me.lvca.alba.engine.source.CollectionSnapshot {
        val smart = me.lvca.alba.engine.model.SmartCollection.entries
        val demoMembers = smart.associate { collection -> collection.id to fixtures.filter {
            when (collection) {
                me.lvca.alba.engine.model.SmartCollection.VIDEOS -> it.index == 19
                me.lvca.alba.engine.model.SmartCollection.LIVE_PHOTOS -> it.index == 20
                me.lvca.alba.engine.model.SmartCollection.SCREENSHOTS -> it.index == 4
                me.lvca.alba.engine.model.SmartCollection.PANORAMAS -> it.index == 10
                me.lvca.alba.engine.model.SmartCollection.BURSTS -> it.index == 6
                me.lvca.alba.engine.model.SmartCollection.SLO_MO, me.lvca.alba.engine.model.SmartCollection.TIME_LAPSE -> false
            }
        }.map { it.recordId }.toSet() + extraRecords.values.filter { record ->
            record.id.startsWith("fixture:") && when (collection) {
                SmartCollection.VIDEOS -> record.kind == MediaKind.VIDEO
                SmartCollection.LIVE_PHOTOS -> record.kind == MediaKind.LIVE_PHOTO
                else -> false
            }
        }.map { it.id } }
        return me.lvca.alba.engine.source.CollectionSnapshot(albums.toList() + smart.map { me.lvca.alba.engine.model.GalleryCollection(it.id, it.title) }, members.toMap() + demoMembers)
    }
    override suspend fun setFavorite(mediaId: String, favorite: Boolean) {
        record(mediaId)
        val id = me.lvca.alba.engine.model.GalleryQuery.FAVORITES
        members[id] = if (favorite) members[id].orEmpty() + mediaId else members[id].orEmpty() - mediaId
    }
    override suspend fun createAlbum(name: String): me.lvca.alba.engine.model.GalleryCollection {
        val album = me.lvca.alba.engine.model.GalleryCollection("demo:" + java.util.UUID.randomUUID(), name, position = System.currentTimeMillis())
        albums += album
        return album
    }
    override suspend fun deleteAlbum(id: String) {
        require(albums.any { it.id == id && !it.isFolder })
        albums.removeAll { it.id == id }
        members.remove(id)
    }
    override suspend fun renameAlbum(id: String, name: String) {
        val index = albums.indexOfFirst { it.id == id && !it.isFolder }
        require(index >= 0)
        albums[index] = albums[index].copy(name = name)
    }
    override suspend fun addToAlbum(id: String, mediaIds: List<String>) {
        require(albums.any { it.id == id && !it.isFolder })
        mediaIds.forEach(::record)
        members[id] = members[id].orEmpty() + mediaIds
    }
    override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) {
        require(albums.any { it.id == id && !it.isFolder })
        mediaIds.forEach(::record)
        members[id] = members[id].orEmpty() - mediaIds.toSet()
    }
    override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) {
        mediaIds.forEach(::record)
        if (trashed) this.trashed.addAll(mediaIds) else this.trashed.removeAll(mediaIds.toSet())
    }
    override suspend fun recentlyDeleted() = personalRecords().filter { it.id in trashed }.map { it.copy(isTrashed = true) }
    override suspend fun storageUsage() = me.lvca.alba.engine.model.CloudStorageUsage(3_200_000_000, 5_000_000_000, listOf(me.lvca.alba.engine.model.StorageCategory("Photos", 2_800_000_000),
            me.lvca.alba.engine.model.StorageCategory("Other", 400_000_000)), System.currentTimeMillis(), isDemo = true)
    override suspend fun writeMotion(mediaId: String, output: OutputStream) {
        require(requireFixture(mediaId).index == 20)
        requireNotNull(openVideo).invoke().use { it.copyTo(output) }
    }
    override suspend fun openPlayback(mediaId: String, position: Long, length: Long): me.lvca.alba.engine.source.MediaRead {
        imported[mediaId]?.let { return it.openPlayback(position, length) }
        require(requireFixture(mediaId).index in setOf(19, 20))
        val bytes = requireNotNull(openVideo).invoke().use { it.readBytes() }
        require(position in 0..bytes.size.toLong())
        val count = if (length < 0) bytes.size - position.toInt() else minOf(length, bytes.size - position).toInt()
        return object : me.lvca.alba.engine.source.MediaRead {
            override val input = java.io.ByteArrayInputStream(bytes, position.toInt(), count)
            override val length = count.toLong()
            override fun close() = input.close()
        }
    }

    override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
        require(limit in 1..100) { "Fixture page size must be between 1 and 100" }
        val offset = cursor?.toIntOrNull() ?: 0
        val all = personalRecords()
        require(offset in 0..all.size) { "Invalid fixture cursor: $cursor" }
        val page = all.drop(offset).take(limit)
        val nextOffset = offset + page.size
        return CloudCatalogPage(
            records = page.filterNot { it.id in trashed },
            nextCursor = nextOffset.takeIf { it < all.size }?.toString(),
            changeToken = CHANGE_TOKEN,
        )
    }

    override suspend fun writePreview(mediaId: String, output: OutputStream) {
        imported[mediaId]?.let { it.writePreview(output); return }
        render(
            fixture = requireFixture(mediaId),
            output = output,
            maxLongEdge = 560,
            quality = 88,
        )
    }

    override suspend fun writeOriginal(mediaId: String, output: OutputStream) {
        imported[mediaId]?.let { it.file.inputStream().use { input -> input.copyTo(output) }; return }
        if (requireFixture(mediaId).index == 19) { requireNotNull(openVideo).invoke().use { it.copyTo(output) }; return }
        render(
            fixture = requireFixture(mediaId),
            output = output,
            maxLongEdge = 1_920,
            quality = 96,
        )
    }

    private fun requireFixture(mediaId: String): FixtureRecord = requireNotNull(recordsById[mediaId] ?: sharedFixtures[mediaId]) {
        "Unknown fixture media item: $mediaId"
    }

    private fun render(
        fixture: FixtureRecord,
        output: OutputStream,
        maxLongEdge: Int,
        quality: Int,
    ) {
        // Keep bundled photos compact as WebP; encode the requested JPEG preview/original
        // so the catalog MIME type, export filename and actual bytes continue to agree.
        if (openPhoto != null) {
            val decoded = openPhoto.invoke(fixture.index).use { input ->
                requireNotNull(BitmapFactory.decodeStream(input)) { "Could not decode demo photo ${fixture.index}" }
            }
            val ratio = (maxLongEdge.toFloat() / max(decoded.width, decoded.height)).coerceAtMost(1f)
            val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(decoded,
                (decoded.width * ratio).roundToInt().coerceAtLeast(1),
                (decoded.height * ratio).roundToInt().coerceAtLeast(1), true) else decoded
            try {
                check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "Could not encode demo photo ${fixture.index}" }
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
            return
        }
        val scale = maxLongEdge.toFloat() / max(fixture.width, fixture.height)
        val width = (fixture.width * scale).roundToInt().coerceAtLeast(1)
        val height = (fixture.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        try {
            val canvas = Canvas(bitmap)
            val start = fixture.accentStart.toInt()
            val end = fixture.accentEnd.toInt()
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    start,
                    end,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

            when (fixture.scene) {
                Scene.COAST -> drawCoast(canvas, width, height, fixture.seed)
                Scene.HILLS -> drawHills(canvas, width, height, fixture.seed)
                Scene.CITY -> drawCity(canvas, width, height, fixture.seed)
                Scene.GARDEN -> drawGarden(canvas, width, height, fixture.seed)
                Scene.INTERIOR -> drawInterior(canvas, width, height, fixture.seed)
                Scene.NIGHT -> drawNight(canvas, width, height, fixture.seed)
            }
            drawTexture(canvas, width, height, fixture.seed)

            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                "Could not encode fixture ${fixture.recordId}"
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawCoast(canvas: Canvas, width: Int, height: Int, seed: Int) {
        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE7A3.toInt() }
        canvas.drawCircle(width * 0.72f, height * 0.22f, width * 0.09f, sun)
        val water = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8849A7C5.toInt() }
        canvas.drawRect(0f, height * 0.55f, width.toFloat(), height.toFloat(), water)
        val shore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99E7C998.toInt() }
        val path = Path().apply {
            moveTo(0f, height * 0.73f)
            cubicTo(
                width * 0.28f,
                height * (0.62f + seed % 3 * 0.02f),
                width * 0.58f,
                height * 0.92f,
                width.toFloat(),
                height * 0.76f,
            )
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        canvas.drawPath(path, shore)
    }

    private fun drawHills(canvas: Canvas, width: Int, height: Int, seed: Int) {
        val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88759B65.toInt() }
        val front = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC345742.toInt() }
        canvas.drawOval(
            RectF(-width * 0.35f, height * 0.44f, width * 0.75f, height * 1.18f),
            back,
        )
        canvas.drawOval(
            RectF(width * (0.15f + seed % 2 * 0.08f), height * 0.5f, width * 1.32f, height * 1.2f),
            front,
        )
    }

    private fun drawCity(canvas: Canvas, width: Int, height: Int, seed: Int) {
        val random = Random(seed)
        val building = Paint(Paint.ANTI_ALIAS_FLAG)
        var x = -width * 0.03f
        while (x < width) {
            val buildingWidth = width * random.nextDouble(0.12, 0.24).toFloat()
            val top = height * random.nextDouble(0.32, 0.66).toFloat()
            building.color = Color.argb(190, random.nextInt(35, 75), random.nextInt(38, 82), random.nextInt(52, 96))
            canvas.drawRoundRect(
                x,
                top,
                x + buildingWidth,
                height * 1.06f,
                width * 0.018f,
                width * 0.018f,
                building,
            )
            x += buildingWidth * 0.82f
        }
    }

    private fun drawGarden(canvas: Canvas, width: Int, height: Int, seed: Int) {
        val random = Random(seed)
        val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA31583F.toInt()
            strokeWidth = max(2f, width * 0.008f)
        }
        val petal = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(13) {
            val x = width * random.nextDouble(0.04, 0.96).toFloat()
            val y = height * random.nextDouble(0.25, 0.88).toFloat()
            canvas.drawLine(x, height.toFloat(), x, y, stem)
            petal.color = Color.argb(
                215,
                random.nextInt(180, 255),
                random.nextInt(92, 205),
                random.nextInt(110, 205),
            )
            canvas.drawCircle(x, y, width * random.nextDouble(0.035, 0.075).toFloat(), petal)
        }
    }

    private fun drawInterior(canvas: Canvas, width: Int, height: Int, seed: Int) {
        val window = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8EEF4F0.toInt() }
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC4B4038.toInt()
            style = Paint.Style.STROKE
            strokeWidth = width * 0.025f
        }
        val left = width * (0.18f + seed % 3 * 0.05f)
        val rect = RectF(left, height * 0.16f, left + width * 0.48f, height * 0.68f)
        canvas.drawRoundRect(rect, width * 0.02f, width * 0.02f, window)
        canvas.drawRoundRect(rect, width * 0.02f, width * 0.02f, frame)
        val table = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC6B4938.toInt() }
        canvas.drawRoundRect(
            width * 0.08f,
            height * 0.73f,
            width * 0.92f,
            height * 0.82f,
            width * 0.03f,
            width * 0.03f,
            table,
        )
    }

    private fun drawNight(canvas: Canvas, width: Int, height: Int, seed: Int) {
        val random = Random(seed)
        val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDF8E7A6.toInt() }
        repeat(28) {
            canvas.drawCircle(
                width * random.nextFloat(),
                height * random.nextFloat() * 0.72f,
                width * random.nextDouble(0.002, 0.009).toFloat(),
                star,
            )
        }
        val moon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9DFC2.toInt() }
        canvas.drawCircle(width * 0.7f, height * 0.24f, width * 0.085f, moon)
    }

    private fun drawTexture(canvas: Canvas, width: Int, height: Int, seed: Int) {
        val random = Random(seed * 31)
        val texture = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x10FFFFFF }
        repeat(120) {
            canvas.drawCircle(
                width * random.nextFloat(),
                height * random.nextFloat(),
                width * random.nextDouble(0.002, 0.012).toFloat(),
                texture,
            )
        }
    }

    private data class FixtureRecord(
        val index: Int,
        val fileName: String,
        val capturedAt: String,
        val width: Int,
        val height: Int,
        val accentStart: Long,
        val accentEnd: Long,
        val scene: Scene,
        val seed: Int,
    ) {
        val recordId: String = "fixture:icloud:${index.toString().padStart(4, '0')}"

        fun toRemoteRecord(): RemoteMediaRecord = RemoteMediaRecord(
            id = recordId,
            fileName = fileName,
            capturedAtEpochMillis = Instant.parse(capturedAt).toEpochMilli(),
            width = width,
            height = height,
            sourceRevision = "fixture-pexels-v2-$index",
            accentStartArgb = accentStart,
            accentEndArgb = accentEnd,
            kind = when (index) { 19 -> me.lvca.alba.engine.model.MediaKind.VIDEO; 20 -> me.lvca.alba.engine.model.MediaKind.LIVE_PHOTO; else -> me.lvca.alba.engine.model.MediaKind.PHOTO },
            motionMimeType = "video/mp4",
            mimeType = if (index == 19) "video/mp4" else "image/jpeg",
            durationMillis = if (index == 19) 3000 else null,
        )
    }

    private enum class Scene {
        COAST,
        HILLS,
        CITY,
        GARDEN,
        INTERIOR,
        NIGHT,
    }

    private companion object {
        const val CHANGE_TOKEN = "fixture-catalog-pexels-v5"

        fun fixtureRecords(): List<FixtureRecord> = listOf(
            FixtureRecord(20, "Live sea breeze.JPG", "2026-08-23T18:00:00Z", 540, 960, 0xFF496F82, 0xFF879B72, Scene.COAST, 20),
            FixtureRecord(19, "Sea breeze.mp4", "2026-08-22T18:00:00Z", 540, 960, 0xFF496F82, 0xFF879B72, Scene.COAST, 19),
            FixtureRecord(1, "IMG_8421.JPG", "2026-08-21T18:42:00Z", 960, 640, 0xFFE89362, 0xFF4D7D8C, Scene.COAST, 21),
            FixtureRecord(2, "IMG_8377.JPG", "2026-08-19T07:15:00Z", 960, 640, 0xFFF4C876, 0xFF607D68, Scene.HILLS, 33),
            FixtureRecord(3, "IMG_8294.JPG", "2026-08-12T20:03:00Z", 960, 638, 0xFF6E7A9E, 0xFF18243B, Scene.INTERIOR, 87),
            FixtureRecord(4, "IMG_8210.JPG", "2026-08-03T12:28:00Z", 720, 960, 0xFFC9D8D1, 0xFF687D75, Scene.INTERIOR, 54),
            FixtureRecord(5, "IMG_8108.JPG", "2026-07-27T16:12:00Z", 960, 960, 0xFFF3B4A2, 0xFF6F8F70, Scene.CITY, 12),
            FixtureRecord(6, "IMG_8033.JPG", "2026-07-20T19:48:00Z", 640, 960, 0xFFF0A762, 0xFF4F8B9A, Scene.COAST, 99),
            FixtureRecord(7, "IMG_7912.JPG", "2026-07-02T21:31:00Z", 641, 960, 0xFF8D93B4, 0xFF252B45, Scene.INTERIOR, 41),
            FixtureRecord(8, "IMG_7824.JPG", "2026-07-16T10:04:00Z", 640, 960, 0xFFE7D49B, 0xFF4F7258, Scene.CITY, 72),
            FixtureRecord(9, "IMG_7751.JPG", "2026-07-10T14:43:00Z", 640, 960, 0xFFDAB0A6, 0xFF795C79, Scene.INTERIOR, 15),
            FixtureRecord(10, "IMG_7690.JPG", "2026-07-03T17:50:00Z", 720, 960, 0xFF88B8C5, 0xFF345D72, Scene.GARDEN, 28),
            FixtureRecord(11, "IMG_7544.JPG", "2026-06-25T09:20:00Z", 720, 960, 0xFFEAD8C4, 0xFF7D695A, Scene.INTERIOR, 66),
            FixtureRecord(12, "IMG_7411.JPG", "2026-06-21T18:02:00Z", 960, 640, 0xFFB4A6C9, 0xFF3D4868, Scene.GARDEN, 17),
            FixtureRecord(13, "IMG_7304.JPG", "2026-06-16T13:36:00Z", 960, 640, 0xFFFFC986, 0xFF507F68, Scene.GARDEN, 93),
            FixtureRecord(14, "IMG_7188.JPG", "2026-06-10T06:58:00Z", 960, 641, 0xFFF2B99D, 0xFF547B72, Scene.CITY, 36),
            FixtureRecord(15, "IMG_7051.JPG", "2026-06-05T20:17:00Z", 960, 640, 0xFF66739A, 0xFF161D34, Scene.NIGHT, 82),
            FixtureRecord(16, "IMG_6920.JPG", "2026-06-01T11:40:00Z", 960, 640, 0xFFD8C9B8, 0xFF776656, Scene.INTERIOR, 45),
            FixtureRecord(17, "IMG_6812.JPG", "2026-05-29T15:09:00Z", 640, 960, 0xFF79B5C2, 0xFF31566A, Scene.COAST, 71),
            FixtureRecord(18, "IMG_6704.JPG", "2026-05-15T08:24:00Z", 960, 640, 0xFFE8C47F, 0xFF476B50, Scene.HILLS, 22),
        )
    }
}

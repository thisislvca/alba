package dev.mela.protocol.fixture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import dev.mela.engine.source.CloudCatalogPage
import dev.mela.engine.source.CloudCatalogSource
import dev.mela.engine.source.RemoteMediaRecord
import java.io.OutputStream
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class FixtureICloudCatalogSource(
    private val includeSharedAlbums: Boolean = false,
    private val openPhoto: ((Int) -> java.io.InputStream)? = null,
    private val openVideo: (() -> java.io.InputStream)? = null,
) : CloudCatalogSource {
    override val accountLabel: String = "demo-library"

    private val fixtures = fixtureRecords()
    private val recordsById = fixtures.associateBy(FixtureRecord::recordId)
    private val sharedAlbums = mutableListOf(
        dev.mela.engine.model.GalleryCollection("shared:demo-library:legacy:owner:one", "Family archive", position = 0,
            shared = dev.mela.engine.model.SharedAlbumInfo(dev.mela.engine.model.SharedAlbumGeneration.LEGACY, dev.mela.engine.model.SharedAlbumRole.VIEWER)),
        dev.mela.engine.model.GalleryCollection("shared:demo-library:private:owner:two", "Family moments", position = 1,
            shared = dev.mela.engine.model.SharedAlbumInfo(dev.mela.engine.model.SharedAlbumGeneration.MODERN, dev.mela.engine.model.SharedAlbumRole.OWNER, participantCount = 1)),
    ).apply {
        listOf("Weekends away", "Good company", "Little moments", "Around town", "Sunday mornings", "Our favorites")
            .forEachIndexed { index, name ->
                add(dev.mela.engine.model.GalleryCollection("shared:demo-library:private:owner:album-${index + 3}", name, position = (index + 2).toLong(),
                    shared = dev.mela.engine.model.SharedAlbumInfo(dev.mela.engine.model.SharedAlbumGeneration.MODERN, dev.mela.engine.model.SharedAlbumRole.OWNER, participantCount = 1)))
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
    private val discussions = mutableMapOf<String, MutableList<dev.mela.engine.model.SharedComment>>()
    private val management = mutableMapOf<String, dev.mela.engine.model.SharedAlbumManagement>()
    private val sharedDone = mutableSetOf<String>()
    override suspend fun sharedActivity(id: String, rank: Int): dev.mela.engine.model.SharedActivityPage {
        val album = sharedAlbums.first { it.id == id }; require(album.shared?.generation == dev.mela.engine.model.SharedAlbumGeneration.MODERN)
        return dev.mela.engine.model.SharedActivityPage(if (rank == 0) listOf(dev.mela.engine.model.SharedPost("post:$id:demo-post", "Demo owner", true, 1790205441000, album.name)) else emptyList(), null)
    }
    override suspend fun sharedPostPhotos(id: String): List<String> = sharedFixtures.keys.filter { it.startsWith(id.removePrefix("post:").substringBeforeLast(':') + ":") }.take(4)
    override suspend fun sharedPostDiscussion(id: String): dev.mela.engine.model.SharedDiscussion {
        val album = sharedAlbums.first { it.id == id.removePrefix("post:").substringBeforeLast(':') }
        return dev.mela.engine.model.SharedDiscussion(discussions[id].orEmpty().toList(), album.shared!!.canComment, album.shared!!.generation)
    }
    override suspend fun sharedManagement(id: String): dev.mela.engine.model.SharedAlbumManagement {
        val album = sharedAlbums.first { it.id == id }
        return (management[id] ?: dev.mela.engine.model.SharedAlbumManagement(album,
            listOf(dev.mela.engine.model.SharedParticipant("demo-owner", "Demo owner", dev.mela.engine.model.SharedAlbumRole.OWNER, isCurrentUser = album.shared?.canManage == true)), allowContributions = true)).copy(album = album)
    }
    override suspend fun sharedDiscussion(mediaId: String): dev.mela.engine.model.SharedDiscussion {
        require(sharedFixtures.containsKey(mediaId))
        val info = requireNotNull(sharedAlbums.first { it.id == mediaId.substringBeforeLast(':') }.shared)
        return dev.mela.engine.model.SharedDiscussion(discussions[mediaId].orEmpty().toList(), info.canComment, info.generation, canRemovePhoto = info.canManage)
    }
    override suspend fun changeSharedAlbum(id: String, command: dev.mela.engine.model.SharedCommand, operationId: String) {
        if (operationId in sharedDone) return
        val state = sharedManagement(id); val info = requireNotNull(state.album.shared)
        val comments = discussions.getOrPut(command.subject) { mutableListOf() }
        when(command.action) {
            dev.mela.engine.model.SharedAction.POST_COMMENT, dev.mela.engine.model.SharedAction.POST_REACTION, dev.mela.engine.model.SharedAction.COMMENT, dev.mela.engine.model.SharedAction.REACTION -> {
                require(info.canComment && command.subject.removePrefix("post:").substringBeforeLast(':') == id)
                val reaction = command.action in setOf(dev.mela.engine.model.SharedAction.REACTION, dev.mela.engine.model.SharedAction.POST_REACTION)
                if (reaction) comments.removeAll { it.isMine && it.reaction }
                if (command.value.isNotBlank()) comments += dev.mela.engine.model.SharedComment(operationId, "You", command.value, System.currentTimeMillis(), true, true, reaction)
            }
            dev.mela.engine.model.SharedAction.POST_DELETE_COMMENT, dev.mela.engine.model.SharedAction.DELETE_COMMENT -> { require(comments.any { it.id == command.value && it.canDelete }); comments.removeAll { it.id == command.value } }
            dev.mela.engine.model.SharedAction.REMOVE_MEDIA -> { require(info.canManage); sharedFixtures.remove(command.subject); discussions.remove(command.subject) }
            dev.mela.engine.model.SharedAction.SAVE_TO_LIBRARY -> Unit // Demo photos already have personal-library counterparts.
            dev.mela.engine.model.SharedAction.LEAVE -> { require(info.role != dev.mela.engine.model.SharedAlbumRole.OWNER); sharedAlbums.removeAll { it.id == id }; sharedFixtures.keys.removeAll { it.startsWith("$id:") } }
            else -> {
                require(info.canManage)
                when(command.action) {
                    dev.mela.engine.model.SharedAction.CREATE_INVITE_LINK -> management[id] = state.copy(invitationLinks = state.invitationLinks + "https://example.invalid/mela-demo-invitation/$operationId")
                    dev.mela.engine.model.SharedAction.REVOKE_INVITE_LINKS -> management[id] = state.copy(invitationLinks = emptyList())
                    dev.mela.engine.model.SharedAction.UNBLOCK -> management[id] = state.copy(blocked = state.blocked.filterNot { it.id == command.subject })
                    dev.mela.engine.model.SharedAction.DENY_REQUEST -> {
                        val person = state.requests.first { it.id == command.subject }
                        management[id] = state.copy(requests = state.requests - person, blocked = state.blocked + person)
                    }
                    dev.mela.engine.model.SharedAction.RENAME -> sharedAlbums[sharedAlbums.indexOfFirst { it.id == id }] = state.album.copy(name = command.value)
                    dev.mela.engine.model.SharedAction.INVITE -> management[id] = state.copy(participants = state.participants + dev.mela.engine.model.SharedParticipant(operationId, command.value, dev.mela.engine.model.SharedAlbumRole.CONTRIBUTOR, pending = true))
                    dev.mela.engine.model.SharedAction.REMOVE_PARTICIPANT -> management[id] = state.copy(participants = state.participants.filter { it.id != command.subject })
                    dev.mela.engine.model.SharedAction.ROLE -> management[id] = state.copy(participants = state.participants.map { if (it.id != command.subject) it else it.copy(role = when(command.value) { "manager" -> dev.mela.engine.model.SharedAlbumRole.MANAGER; "commenter" -> dev.mela.engine.model.SharedAlbumRole.COMMENTER; else -> dev.mela.engine.model.SharedAlbumRole.CONTRIBUTOR }) })
                    dev.mela.engine.model.SharedAction.PUBLIC_ACCESS -> management[id] = state.copy(publicAccess = command.enabled)
                    dev.mela.engine.model.SharedAction.CONTRIBUTIONS -> management[id] = state.copy(allowContributions = command.enabled)
                    dev.mela.engine.model.SharedAction.ACCESS_REQUESTS -> management[id] = state.copy(allowAccessRequests = command.enabled)
                    dev.mela.engine.model.SharedAction.TEMPORARY -> management[id] = state.copy(temporary = command.enabled)
                    dev.mela.engine.model.SharedAction.DELETE_ALBUM -> { sharedAlbums.removeAll { it.id == id }; sharedFixtures.keys.removeAll { it.startsWith("$id:") } }
                    dev.mela.engine.model.SharedAction.COVER -> require(sharedFixtures.containsKey(command.subject))
                    else -> error("Unsupported demo action")
                }
            }
        }
        sharedDone += operationId
    }

    override suspend fun sharedAlbums(): dev.mela.engine.source.SharedCatalogSnapshot {
        val shown = if (includeSharedAlbums) sharedAlbums.toList() else emptyList()
        return dev.mela.engine.source.SharedCatalogSnapshot(sharedFixtures.filterKeys { key -> shown.any { key.startsWith(it.id + ":") } }
            .map { (id, photo) -> photo.toRemoteRecord().copy(id = id) }, dev.mela.engine.source.CollectionSnapshot(shown,
                shown.associate { album -> album.id to sharedFixtures.keys.filter { it.startsWith(album.id + ":") }.toSet() }))
    }
    override suspend fun createSharedAlbum(name: String, operationId: String, generation: dev.mela.engine.model.SharedAlbumGeneration): dev.mela.engine.model.GalleryCollection {
        val id = "shared:demo-library:${generation.name}:owner:$operationId"
        return sharedAlbums.firstOrNull { it.id == id } ?: dev.mela.engine.model.GalleryCollection(id, name,
            shared = dev.mela.engine.model.SharedAlbumInfo(generation, dev.mela.engine.model.SharedAlbumRole.OWNER, participantCount = 1))
            .also { sharedAlbums += it }
    }
    override suspend fun uploadSharedPhoto(albumId: String, fileName: String, source: dev.mela.engine.model.OneShotUploadSource, operationId: String) {
        require(sharedAlbums.first { it.id == albumId }.shared?.canContribute == true)
        source.openOnce().use { input ->
            input.copyTo(object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            })
        }
        sharedFixtures["$albumId:$operationId-${source.sha256Hex}"] = fixtures[0]
    }
    override suspend fun contributeToSharedAlbum(id: String, mediaIds: List<String>, operationId: String) {
        require(sharedAlbums.first { it.id == id }.shared?.canContribute == true)
        mediaIds.forEach { media -> sharedFixtures["$id:$operationId-${media.hashCode()}"] = requireFixture(media) }
    }
    private val trashed = mutableSetOf<String>()
    override suspend fun captureSyncToken() = CHANGE_TOKEN
    override suspend fun changes(token: String, known: List<RemoteMediaRecord>): dev.mela.engine.source.CloudChanges {
        if (token != CHANGE_TOKEN) throw dev.mela.engine.source.CatalogResetRequired()
        return dev.mela.engine.source.CloudChanges(fixtures.filterNot { it.recordId in trashed }.map { it.toRemoteRecord() }, trashed.toSet(), CHANGE_TOKEN, false)
    }
    private val albums = mutableListOf(
        dev.mela.engine.model.GalleryCollection("demo:journeys", "Journeys", isFolder = true),
        dev.mela.engine.model.GalleryCollection("demo:coast", "By the sea", "demo:journeys"),
        dev.mela.engine.model.GalleryCollection("demo:days", "Everyday", position = 1))
    private val members = mutableMapOf(
        "demo:coast" to fixtures.filter { it.scene == Scene.COAST }.map { it.recordId }.toSet(),
        "demo:days" to fixtures.filter { it.scene != Scene.COAST }.map { it.recordId }.toSet(),
        dev.mela.engine.model.GalleryQuery.FAVORITES to fixtures.filter { it.index % 3 == 1 }.map { it.recordId }.toSet())
    override suspend fun collections(): dev.mela.engine.source.CollectionSnapshot {
        val smart = dev.mela.engine.model.SmartCollection.entries
        val demoMembers = smart.associate { collection -> collection.id to fixtures.filter {
            when (collection) {
                dev.mela.engine.model.SmartCollection.VIDEOS -> it.index == 19
                dev.mela.engine.model.SmartCollection.LIVE_PHOTOS -> it.index == 20
                dev.mela.engine.model.SmartCollection.SCREENSHOTS -> it.index == 4
                dev.mela.engine.model.SmartCollection.PANORAMAS -> it.index == 10
                dev.mela.engine.model.SmartCollection.BURSTS -> it.index == 6
                dev.mela.engine.model.SmartCollection.SLO_MO, dev.mela.engine.model.SmartCollection.TIME_LAPSE -> false
            }
        }.map { it.recordId }.toSet() }
        return dev.mela.engine.source.CollectionSnapshot(albums.toList() + smart.map { dev.mela.engine.model.GalleryCollection(it.id, it.title) }, members.toMap() + demoMembers)
    }
    override suspend fun setFavorite(mediaId: String, favorite: Boolean) {
        requireFixture(mediaId)
        val id = dev.mela.engine.model.GalleryQuery.FAVORITES
        members[id] = if (favorite) members[id].orEmpty() + mediaId else members[id].orEmpty() - mediaId
    }
    override suspend fun createAlbum(name: String): dev.mela.engine.model.GalleryCollection {
        val album = dev.mela.engine.model.GalleryCollection("demo:" + java.util.UUID.randomUUID(), name, position = System.currentTimeMillis())
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
        mediaIds.forEach(::requireFixture)
        members[id] = members[id].orEmpty() + mediaIds
    }
    override suspend fun removeFromAlbum(id: String, mediaIds: List<String>) {
        require(albums.any { it.id == id && !it.isFolder })
        mediaIds.forEach(::requireFixture)
        members[id] = members[id].orEmpty() - mediaIds.toSet()
    }
    override suspend fun setCloudTrashed(mediaIds: List<String>, trashed: Boolean) {
        mediaIds.forEach(::requireFixture)
        if (trashed) this.trashed.addAll(mediaIds) else this.trashed.removeAll(mediaIds.toSet())
    }
    override suspend fun recentlyDeleted() = fixtures.filter { it.recordId in trashed }.map { it.toRemoteRecord().copy(isTrashed = true) }
    override suspend fun storageUsage() = dev.mela.engine.model.CloudStorageUsage(
        3_200_000_000, 5_000_000_000, listOf(dev.mela.engine.model.StorageCategory("Photos", 2_800_000_000),
            dev.mela.engine.model.StorageCategory("Other", 400_000_000)), System.currentTimeMillis(), isDemo = true)
    override suspend fun writeMotion(mediaId: String, output: OutputStream) {
        require(requireFixture(mediaId).index == 20)
        requireNotNull(openVideo).invoke().use { it.copyTo(output) }
    }
    override suspend fun openPlayback(mediaId: String, position: Long, length: Long): dev.mela.engine.source.MediaRead {
        require(requireFixture(mediaId).index in setOf(19, 20))
        val bytes = requireNotNull(openVideo).invoke().use { it.readBytes() }
        require(position in 0..bytes.size.toLong())
        val count = if (length < 0) bytes.size - position.toInt() else minOf(length, bytes.size - position).toInt()
        return object : dev.mela.engine.source.MediaRead {
            override val input = java.io.ByteArrayInputStream(bytes, position.toInt(), count)
            override val length = count.toLong()
            override fun close() = input.close()
        }
    }

    override suspend fun fetchPage(cursor: String?, limit: Int): CloudCatalogPage {
        require(limit in 1..100) { "Fixture page size must be between 1 and 100" }
        val offset = cursor?.toIntOrNull() ?: 0
        require(offset in 0..fixtures.size) { "Invalid fixture cursor: $cursor" }
        val page = fixtures.drop(offset).take(limit)
        val nextOffset = offset + page.size
        return CloudCatalogPage(
            records = page.filterNot { it.recordId in trashed }.map(FixtureRecord::toRemoteRecord),
            nextCursor = nextOffset.takeIf { it < fixtures.size }?.toString(),
            changeToken = CHANGE_TOKEN,
        )
    }

    override suspend fun writePreview(mediaId: String, output: OutputStream) {
        render(
            fixture = requireFixture(mediaId),
            output = output,
            maxLongEdge = 560,
            quality = 88,
        )
    }

    override suspend fun writeOriginal(mediaId: String, output: OutputStream) {
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
            kind = when (index) { 19 -> dev.mela.engine.model.MediaKind.VIDEO; 20 -> dev.mela.engine.model.MediaKind.LIVE_PHOTO; else -> dev.mela.engine.model.MediaKind.PHOTO },
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

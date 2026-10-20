package me.lvca.alba.app.gallery

import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.GalleryDate
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.GalleryQuery
import me.lvca.alba.engine.model.GalleryQueryProjection
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.dateMillis
import me.lvca.alba.engine.model.isShared
import me.lvca.alba.protocol.account.ICloudAccountState
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class GallerySection(
    val month: YearMonth,
    val items: List<GalleryMedia>,
)

/** Everything the current account and phone access allow Alba to show, before the user's query narrows it. */
internal data class GalleryCatalog(
    val items: List<GalleryMedia>,
    val byId: Map<String, GalleryMedia>,
    val cloudCount: Int,
    val deviceCount: Int,
    /** Phone folders that hold at least one photo outside the trash, as browsable collections. */
    val deviceFolders: List<GalleryCollection>,
)

/** The catalog narrowed by a query, with its month sections. */
internal data class ProjectedGallery(
    val catalog: GalleryCatalog,
    val items: List<GalleryMedia>,
    val sections: List<GallerySection>,
    val accountState: ICloudAccountState,
)

internal data class TransferContent(
    val byMediaId: Map<String, TransferView>,
    val queue: List<TransferView>,
)

internal fun projectGalleryCatalog(
    allItems: List<GalleryMedia>,
    hasDeviceAccess: Boolean,
    accountState: ICloudAccountState,
): GalleryCatalog {
    val catalogItems = ArrayList<GalleryMedia>(allItems.size)
    val byId = HashMap<String, GalleryMedia>(allItems.size)
    val deviceFolders = LinkedHashMap<String, GalleryCollection>()
    var cloudCount = 0
    var deviceCount = 0

    allItems.forEach { media ->
        if (!media.isVisibleFor(accountState)) return@forEach
        if (!hasDeviceAccess && media.origin == MediaOrigin.DEVICE) return@forEach

        when (media.origin) {
            MediaOrigin.ICLOUD -> if (!media.isShared) cloudCount += 1
            MediaOrigin.DEVICE -> deviceCount += 1
        }
        val visibleMedia = if (hasDeviceAccess) media else media.copy(linkedDeviceId = null, linkedDeviceReference = null)
        catalogItems += visibleMedia
        byId[visibleMedia.id] = visibleMedia
        val folder = visibleMedia.deviceFolderId
        if (folder != null && !visibleMedia.isTrashed && folder !in deviceFolders) {
            deviceFolders[folder] = GalleryCollection(folder, visibleMedia.deviceFolderName ?: "")
        }
    }

    return GalleryCatalog(catalogItems, byId, cloudCount, deviceCount, deviceFolders.values.toList())
}

/** Projects the library for a query. Incremental, so keep one instance per observer and call it from one thread. */
internal class GalleryProjection {
    private val queryProjection = GalleryQueryProjection()
    private val sectionProjection = GallerySectionProjection()

    fun apply(
        allItems: List<GalleryMedia>,
        query: GalleryQuery,
        hasDeviceAccess: Boolean,
        accountState: ICloudAccountState,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ProjectedGallery {
        val catalog = projectGalleryCatalog(allItems, hasDeviceAccess, accountState)
        val matching = queryProjection.apply(catalog.items, query)
        // The unfiltered library shows a backed-up phone photo once, as its iCloud copy.
        val linked = if (query.origin == null && query.collectionId == null && hasDeviceAccess)
            matching.mapNotNull { it.linkedDeviceId }.toSet() else emptySet()
        val visible = if (linked.isEmpty()) matching else matching.filterNot { it.id in linked }
        return ProjectedGallery(catalog, visible, sectionProjection.apply(visible, query.date, zone), accountState)
    }
}

/** Albums this account may show: demo albums only in the demo, and never another account's albums. */
internal fun visibleCollections(
    collections: List<GalleryCollection>,
    accountState: ICloudAccountState,
    deviceFolders: List<GalleryCollection>,
): List<GalleryCollection> = collections.filter { collection ->
    when (accountState) {
        ICloudAccountState.Demo -> true
        is ICloudAccountState.SignedIn -> !collection.id.startsWith("shared:demo-library:")
        else -> false
    }
} + deviceFolders

internal fun projectTransfers(transfers: List<TransferView>): TransferContent {
    val byMediaId = LinkedHashMap<String, TransferView>()
    transfers.forEach { transfer ->
        val mediaId = transfer.mediaId ?: return@forEach
        if (mediaId !in byMediaId) byMediaId[mediaId] = transfer
    }
    return TransferContent(byMediaId = byMediaId, queue = transfers)
}

internal fun buildGallerySections(
    items: List<GalleryMedia>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    date: GalleryDate = GalleryDate.CAPTURED,
): List<GallerySection> {
    val groupedItems = linkedMapOf<YearMonth, MutableList<GalleryMedia>>()
    items.forEach { media ->
        val month = YearMonth.from(
            Instant.ofEpochMilli(media.dateMillis(date)).atZone(zoneId),
        )
        groupedItems.getOrPut(month, ::mutableListOf) += media
    }
    return groupedItems.map { (month, media) -> GallerySection(month, media) }
}

private fun GalleryMedia.isVisibleFor(accountState: ICloudAccountState): Boolean = when (accountState) {
    is ICloudAccountState.SignedIn -> !id.startsWith("fixture:") && !id.startsWith("shared:demo-library:")
    ICloudAccountState.Demo -> !id.startsWith("icloud:") && (!isShared || id.startsWith("shared:demo-library:"))
    else -> origin == MediaOrigin.DEVICE
}

internal class GallerySectionProjection {
    private var previous: List<GalleryMedia> = emptyList()
    private var previousDate: GalleryDate? = null
    private var previousZone: ZoneId? = null
    private var groups: List<Pair<YearMonth, List<Int>>> = emptyList()

    fun apply(items: List<GalleryMedia>, date: GalleryDate, zone: ZoneId = ZoneId.systemDefault()): List<GallerySection> {
        if (date != previousDate || zone != previousZone || items.size != previous.size ||
            items.indices.any { items[it].id != previous[it].id || items[it].dateMillis(date) != previous[it].dateMillis(date) }) {
            groups = items.indices.groupBy { YearMonth.from(Instant.ofEpochMilli(items[it].dateMillis(date)).atZone(zone)) }
                .entries.map { it.key to it.value }
        }
        previous = items
        previousDate = date
        previousZone = zone
        return groups.map { (month, indices) -> GallerySection(month, indices.map(items::get)) }
    }
}

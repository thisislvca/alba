package me.lvca.alba.app.gallery

import me.lvca.alba.engine.companion.ExistingCopyMatcher
import me.lvca.alba.engine.model.BurstIndex
import me.lvca.alba.engine.model.BurstStacks
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
import me.lvca.alba.protocol.fixture.isDemoId
import me.lvca.alba.protocol.fixture.isDemoSharedId
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
    /** Every burst in the library, so stacks keep their size and key photo in every view. */
    val bursts: BurstIndex = BurstIndex.EMPTY,
    /** Phone photos whose iCloud copy is hidden. */
    val hiddenDeviceCopies: Set<String> = emptySet(),
    /** Phone items not in the iCloud library, as [PhoneOnlyIndex] decides. Empty without an iCloud library to compare with. */
    val phoneOnly: Set<String> = emptySet(),
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
    phoneOnly: PhoneOnlyIndex = PhoneOnlyIndex(),
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
            MediaOrigin.ICLOUD -> if (!media.isShared && !media.isHidden) cloudCount += 1
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

    return GalleryCatalog(catalogItems, byId, cloudCount, deviceCount, deviceFolders.values.toList(), BurstIndex.of(catalogItems),
        catalogItems.mapNotNullTo(HashSet()) { item -> item.linkedDeviceId?.takeIf { item.isHidden } },
        phoneOnly.apply(catalogItems, accountState))
}

/**
 * Which phone items are not in the iCloud library. A phone item is in it when an iCloud item in the library or Hidden
 * is its verified backup copy, or looks like the same original by backup's own existing-copy rule
 * ([ExistingCopyMatcher.looksLikeSameOriginal]: the same byte count plus the same file name or capture time), which
 * finds photos that reached iCloud from another device. A phone item of unknown size can't be matched, so it is never
 * called phone-only. Incremental: keep one instance per observer and call it from one thread, off the main thread.
 */
internal class PhoneOnlyIndex {
    private data class CloudKey(val fileName: String, val byteCount: Long, val capturedAt: Long)
    private data class PhoneKey(val id: String, val fileName: String, val byteCount: Long, val capturedAt: Long)

    private var cloud: List<CloudKey> = emptyList()
    private var bySize: Map<Long, List<CloudKey>> = emptyMap()

    /** Each phone item's answer, valid while the iCloud library's keys stay the same. */
    private val inCloud = HashMap<PhoneKey, Boolean>()

    fun apply(items: List<GalleryMedia>, accountState: ICloudAccountState): Set<String> {
        if (accountState !is ICloudAccountState.SignedIn && accountState != ICloudAccountState.Demo) return emptySet()
        val library = items.filter { it.origin == MediaOrigin.ICLOUD && !it.isTrashed && !it.isShared }
        val keys = library.mapNotNull { item -> item.byteCount?.takeIf { it > 0 }?.let { CloudKey(item.fileName, it, item.capturedAtEpochMillis) } }
        if (keys != cloud) {
            cloud = keys
            bySize = keys.groupBy { it.byteCount }
            inCloud.clear()
        }
        val linked = library.mapNotNullTo(HashSet()) { it.linkedDeviceId }
        return items.mapNotNullTo(HashSet()) { item ->
            if (item.origin != MediaOrigin.DEVICE || item.isTrashed || item.id in linked) return@mapNotNullTo null
            val size = item.byteCount?.takeIf { it > 0 } ?: return@mapNotNullTo null
            val key = PhoneKey(item.id, item.fileName, size, item.capturedAtEpochMillis)
            val matched = inCloud.getOrPut(key) {
                bySize[size].orEmpty().any { cloud ->
                    ExistingCopyMatcher.looksLikeSameOriginal(key.fileName, size, key.capturedAt, cloud.fileName, cloud.byteCount, cloud.capturedAt)
                }
            }
            item.id.takeUnless { matched }
        }
    }
}

/** Projects the library for a query. Incremental, so keep one instance per observer and call it from one thread. */
internal class GalleryProjection {
    private val queryProjection = GalleryQueryProjection()
    private val phoneOnly = PhoneOnlyIndex()
    private val sectionProjection = GallerySectionProjection()

    fun apply(
        allItems: List<GalleryMedia>,
        query: GalleryQuery,
        hasDeviceAccess: Boolean,
        accountState: ICloudAccountState,
        zone: ZoneId = ZoneId.systemDefault(),
        expandedBursts: Set<String> = emptySet(),
    ): ProjectedGallery {
        val catalog = projectGalleryCatalog(allItems, hasDeviceAccess, accountState, phoneOnly)
        val matching = queryProjection.apply(catalog.items, query)
        // The unfiltered library shows a backed-up phone photo once, as its iCloud copy.
        val linked = if (query.origin == null && query.collectionId == null && hasDeviceAccess)
            matching.mapNotNull { it.linkedDeviceId }.toSet() else emptySet()
        // A phone photo backed up as a now hidden iCloud photo is hidden with it, everywhere.
        val hiddenCopies = if (hasDeviceAccess) catalog.hiddenDeviceCopies else emptySet()
        val unlinked = if (linked.isEmpty() && hiddenCopies.isEmpty()) matching
            else matching.filterNot { it.id in linked || it.id in hiddenCopies }
        // Like Photos, a burst shows as one photo; Recently Deleted lists each deleted frame.
        val visible = if (query.trashOnly) unlinked else BurstStacks.stack(unlinked, catalog.bursts, expandedBursts)
        // Recently Deleted is one flat grid in deletion order, as in Photos: no month or day sections.
        return ProjectedGallery(catalog, visible, sectionProjection.apply(visible, query.date, zone, flat = query.trashOnly), accountState)
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
        is ICloudAccountState.SignedIn -> !isDemoSharedId(collection.id)
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
    is ICloudAccountState.SignedIn -> !isDemoId(id)
    ICloudAccountState.Demo -> !id.startsWith("icloud:") && (!isShared || isDemoSharedId(id))
    else -> origin == MediaOrigin.DEVICE
}

internal class GallerySectionProjection {
    private var previous: List<GalleryMedia> = emptyList()
    private var previousDate: GalleryDate? = null
    private var previousZone: ZoneId? = null
    private var previousFlat = false
    private var groups: List<Pair<YearMonth, List<Int>>> = emptyList()

    /** Month sections of [items], or with [flat] a single section holding them all in their order. */
    fun apply(items: List<GalleryMedia>, date: GalleryDate, zone: ZoneId = ZoneId.systemDefault(), flat: Boolean = false): List<GallerySection> {
        val sameLayout = date == previousDate && zone == previousZone && flat == previousFlat
        val sameItems = items.size == previous.size &&
            items.indices.none { items[it].id != previous[it].id || items[it].dateMillis(date) != previous[it].dateMillis(date) }
        if (!sameLayout || !sameItems) {
            val month = { index: Int -> YearMonth.from(Instant.ofEpochMilli(items[index].dateMillis(date)).atZone(zone)) }
            groups = if (flat) listOfNotNull(items.indices.takeIf { !it.isEmpty() }?.let { month(0) to it.toList() })
                else items.indices.groupBy(month).entries.map { it.key to it.value }
        }
        previous = items
        previousDate = date
        previousZone = zone
        previousFlat = flat
        return groups.map { (month, indices) -> GallerySection(month, indices.map(items::get)) }
    }
}

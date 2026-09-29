package dev.mela.app

import dev.mela.engine.model.isShared
import dev.mela.engine.model.GalleryDate
import dev.mela.engine.model.dateMillis
import dev.mela.engine.model.GalleryMedia
import dev.mela.engine.model.MediaOrigin
import dev.mela.engine.model.TransferView
import dev.mela.protocol.account.ICloudAccountState
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class GallerySection(
    val month: YearMonth,
    val items: List<GalleryMedia>,
)

internal data class GalleryContent(
    val items: List<GalleryMedia>,
    val catalogItems: List<GalleryMedia>,
    val sections: List<GallerySection>,
    val selectedMedia: GalleryMedia?,
    val cloudCount: Int,
    val deviceCount: Int,
    val selectedFilter: GalleryFilter,
    val deviceMediaAccess: Boolean,
    val accountState: ICloudAccountState,
    val cloudSourceLabel: String,
)

internal data class TransferContent(
    val byMediaId: Map<String, TransferView>,
    val queue: List<TransferView>,
)

internal fun projectGalleryContent(
    allItems: List<GalleryMedia>,
    filter: GalleryFilter,
    selectedId: String?,
    hasDeviceAccess: Boolean,
    accountState: ICloudAccountState,
    zoneId: ZoneId = ZoneId.systemDefault(),
    includeSections: Boolean = true,
): GalleryContent {
    val visibleItems = ArrayList<GalleryMedia>(allItems.size)
    val catalogItems = ArrayList<GalleryMedia>(allItems.size)
    val groupedItems = linkedMapOf<YearMonth, MutableList<GalleryMedia>>()
    var selectedMedia: GalleryMedia? = null
    var cloudCount = 0
    var deviceCount = 0

    allItems.forEach { media ->
        if (!media.isVisibleFor(accountState)) return@forEach
        if (!hasDeviceAccess && media.origin == MediaOrigin.DEVICE) return@forEach

        when (media.origin) {
            MediaOrigin.ICLOUD -> if (!media.isShared) cloudCount += 1
            MediaOrigin.DEVICE -> deviceCount += 1
        }
        if (media.id == selectedId) selectedMedia = if (hasDeviceAccess) media else media.copy(linkedDeviceId = null, linkedDeviceReference = null)
        val visibleMedia = if (hasDeviceAccess) media else media.copy(linkedDeviceId = null, linkedDeviceReference = null)
        catalogItems += visibleMedia
        if (!filter.includes(media.origin)) return@forEach

        visibleItems += visibleMedia
        if (includeSections) {
            val month = YearMonth.from(Instant.ofEpochMilli(media.capturedAtEpochMillis).atZone(zoneId))
            groupedItems.getOrPut(month, ::mutableListOf) += media
        }
    }

    return GalleryContent(
        items = visibleItems,
        catalogItems = catalogItems,
        sections = groupedItems.map { (month, items) -> GallerySection(month, items) },
        selectedMedia = selectedMedia,
        cloudCount = cloudCount,
        deviceCount = deviceCount,
        selectedFilter = filter,
        deviceMediaAccess = hasDeviceAccess,
        accountState = accountState,
        cloudSourceLabel = if (accountState is ICloudAccountState.SignedIn) {
            "iCloud Personal Library"
        } else {
            "Demo iCloud library"
        },
    )
}

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
    ICloudAccountState.Restoring -> origin != MediaOrigin.ICLOUD
    else -> !id.startsWith("icloud:") && (!isShared || id.startsWith("shared:demo-library:"))
}

private fun GalleryFilter.includes(origin: MediaOrigin): Boolean = when (this) {
    GalleryFilter.ALL -> true
    GalleryFilter.ICLOUD -> origin == MediaOrigin.ICLOUD
    GalleryFilter.DEVICE -> origin == MediaOrigin.DEVICE
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

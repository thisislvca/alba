package me.lvca.alba.app.platform

import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaOrigin
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState

/** The most phone items Alba hands to one Android trash or restore request. */
internal const val PHONE_TRASH_LIMIT = 2_000

/**
 * The phone copies behind [items] for Android's trash request: a phone photo's own original, or the phone copy linked
 * to an iCloud photo. Null when any item has no phone copy, or when there are more than [PHONE_TRASH_LIMIT], so a
 * request never silently covers only part of what the user chose.
 */
internal fun phoneTrashUris(items: List<GalleryMedia>): List<String>? {
    val uris = items.mapNotNull { item ->
        if (item.origin == MediaOrigin.DEVICE) item.originalReference else item.linkedDeviceReference
    }.distinct()
    return uris.takeIf { it.size == items.size && it.size <= PHONE_TRASH_LIMIT }
}

/**
 * How many of [items] exist only on this phone: phone items without a verified iCloud upload. An iCloud photo's phone
 * copy is backed up by definition.
 */
internal fun notBackedUpCount(items: List<GalleryMedia>, uploadsByMediaId: Map<String, TransferView>): Int = items.count { item ->
    item.origin == MediaOrigin.DEVICE && uploadsByMediaId[item.id]?.state != TransferViewState.VERIFIED
}

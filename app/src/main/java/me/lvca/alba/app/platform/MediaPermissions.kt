package me.lvca.alba.app.platform

import android.Manifest
import android.os.Build
import me.lvca.alba.protocol.account.ICloudAccountState

/** Permissions that each let Alba read at least some phone photos. Holding any one is enough. */
internal fun devicePhotoReadPermissions(sdkInt: Int): List<String> = when {
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** The permissions Alba asks for when the user chooses to include phone photos. */
internal fun requestedDevicePhotoPermissions(sdkInt: Int): List<String> = buildList {
    add(if (sdkInt >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.READ_MEDIA_VIDEO)
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
}

internal fun hasDevicePhotoAccess(sdkInt: Int, granted: (String) -> Boolean): Boolean =
    devicePhotoReadPermissions(sdkInt).any(granted)

/** Android 14's partial access: only photos the user picked are readable. */
internal fun hasLimitedPhotoAccess(sdkInt: Int, granted: (String) -> Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !granted(Manifest.permission.READ_MEDIA_IMAGES) &&
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

/**
 * Uploads must keep photo location metadata, which Android redacts without ACCESS_MEDIA_LOCATION. The demo library
 * never uploads phone originals to Apple, so it does not ask.
 */
internal fun uploadNeedsMediaLocationPermission(accountState: ICloudAccountState, sdkInt: Int, granted: Boolean): Boolean =
    accountState != ICloudAccountState.Demo && sdkInt >= Build.VERSION_CODES.Q && !granted

/** What a gallery upload covers: the current selection, or one phone photo. */
internal sealed interface UploadTarget {
    data object Selection : UploadTarget
    data class Media(val mediaId: String) : UploadTarget
}

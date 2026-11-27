package me.lvca.alba.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.lvca.alba.app.work.canNotify

/** How much of the phone's photo library Alba can read. */
internal enum class PhotoAccess { FULL, SELECTED, NONE }

/**
 * Everything Alba needs from Android, as the Permissions page lists it. [id] names the row's test tags; [backup] marks
 * what automatic backup cannot work without.
 */
internal enum class AppPermission(val id: String, val backup: Boolean = false, val optional: Boolean = false) {
    PHOTOS("photos", backup = true),
    LOCATION("location", backup = true),
    NOTIFICATIONS("notifications"),
    BATTERY("battery"),
    BACKGROUND_DATA("background-data"),

    /** Android 12 and later: whether iCloud links open in Alba or in the browser. A convenience, so never counted as needing attention. */
    ICLOUD_LINKS("icloud-links", optional = true),
}

/** What Android allows Alba right now. Read again whenever Alba returns to the screen, since Settings can change it. */
internal data class AppPermissions(
    val photos: PhotoAccess,
    /** Photo locations are kept: without ACCESS_MEDIA_LOCATION Android removes them from every file Alba reads. */
    val location: Boolean,
    /** Null before Android 13, where Android does not ask. */
    val notifications: Boolean?,
    /** Alba is exempt from battery optimisation, so Android does not hold back its background work. */
    val batteryUnrestricted: Boolean,
    /** Data Saver keeps Alba off mobile data in the background. */
    val backgroundDataRestricted: Boolean,
    /** Every iCloud link host is set to open in Alba. Null before Android 12, where links already offer Alba. */
    val iCloudLinks: Boolean? = null,
) {
    /** The rows the page shows, in order. */
    val shown: List<AppPermission>
        get() = AppPermission.entries.filter {
            when (it) {
                AppPermission.NOTIFICATIONS -> notifications != null
                AppPermission.ICLOUD_LINKS -> iCloudLinks != null
                else -> true
            }
        }

    fun allowed(permission: AppPermission): Boolean = when (permission) {
        AppPermission.PHOTOS -> photos == PhotoAccess.FULL
        AppPermission.LOCATION -> location
        AppPermission.NOTIFICATIONS -> notifications != false
        AppPermission.BATTERY -> batteryUnrestricted
        AppPermission.BACKGROUND_DATA -> !backgroundDataRestricted
        AppPermission.ICLOUD_LINKS -> iCloudLinks != false
    }

    /** The rows that need the user, for the summary here and on the Profile and Backup pages; optional rows don't count. */
    val needingAttention: List<AppPermission> get() = shown.filterNot { it.optional || allowed(it) }

    /** Automatic backup has the access it needs: every photo, with its location (the engine's "full:unredacted"). */
    val backupAccess: Boolean get() = AppPermission.entries.filter { it.backup }.all(::allowed)
}

/** The hosts of the manifest's iCloud link filters, which Alba can't verify, so the user selects them in Settings. */
internal val ICLOUD_LINK_HOSTS = setOf(
    "photos.icloud.com", "www.icloud.com", "icloud.com",
    "photos.icloud.com.cn", "www.icloud.com.cn", "icloud.com.cn",
)

/** Whether the user has selected or Android has verified every iCloud host in [hostStates]; false when none is listed. */
internal fun iCloudLinksOn(hostStates: Map<String, Int>): Boolean {
    val hosts = hostStates.filterKeys { it in ICLOUD_LINK_HOSTS }
    return hosts.isNotEmpty() && hosts.values.all {
        it == DomainVerificationUserState.DOMAIN_STATE_SELECTED || it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
    }
}

/** Where the page learns whether iCloud links open in Alba; tests give it a fixed answer. */
internal fun interface ICloudLinksSource {
    /** True when every iCloud link host is on for Alba, false when not, null when Android can't say. Android 12 and later. */
    fun read(context: Context): Boolean?
}

/** Android's per-app link settings, the "Open by default" page. */
internal val PlatformICloudLinks = ICloudLinksSource { context ->
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@ICloudLinksSource null
    runCatching {
        val manager = context.getSystemService(DomainVerificationManager::class.java)
        manager?.getDomainVerificationUserState(context.packageName)?.hostToStateMap?.let(::iCloudLinksOn)
    }.getOrNull()
}

/** Reads what Android allows Alba now, with the same checks the rest of the app uses. */
internal fun readAppPermissions(
    anyContext: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
    links: ICloudLinksSource = PlatformICloudLinks,
): AppPermissions {
    val context = anyContext.applicationContext
    val granted = { permission: String -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    val photos = when {
        hasFullPhotoAccess(sdkInt, granted) -> PhotoAccess.FULL
        hasLimitedPhotoAccess(sdkInt, granted) -> PhotoAccess.SELECTED
        else -> PhotoAccess.NONE
    }
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return AppPermissions(
        photos = photos,
        location = granted(Manifest.permission.ACCESS_MEDIA_LOCATION),
        notifications = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) canNotify(context) else null,
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
        backgroundDataRestricted = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        iCloudLinks = if (sdkInt >= Build.VERSION_CODES.S) links.read(context) else null,
    )
}

/** What one tap on a row's action does. */
internal enum class PermissionFix {
    /** Android's own prompt. */
    REQUEST,

    /** Alba's page in Settings, where Android sends a permission it no longer asks for. */
    APP_SETTINGS,
    NOTIFICATION_SETTINGS,

    /** Settings' list of apps and their battery optimisation, not the direct request, which Play policy restricts. */
    BATTERY_SETTINGS,

    /** Alba's mobile data settings, where "Background data" and "Unrestricted data" are. */
    DATA_SETTINGS,

    /** Alba's "Open by default" page, where the user turns its web links on. */
    OPEN_BY_DEFAULT_SETTINGS,
}

/**
 * How to fix [permission]; null when it is allowed. A prompt Android no longer shows ([blocked]) sends the user to
 * Alba's settings page instead, and notifications turned off in Settings are turned on there.
 */
internal fun AppPermissions.fix(permission: AppPermission, blocked: Boolean): PermissionFix? = when {
    allowed(permission) -> null
    permission == AppPermission.BATTERY -> PermissionFix.BATTERY_SETTINGS
    permission == AppPermission.BACKGROUND_DATA -> PermissionFix.DATA_SETTINGS
    permission == AppPermission.ICLOUD_LINKS -> PermissionFix.OPEN_BY_DEFAULT_SETTINGS
    // Android 14's partial access asks again, so the user can choose "Allow all".
    permission == AppPermission.PHOTOS && photos == PhotoAccess.SELECTED -> PermissionFix.REQUEST
    permission == AppPermission.NOTIFICATIONS -> if (blocked) PermissionFix.NOTIFICATION_SETTINGS else PermissionFix.REQUEST
    blocked -> PermissionFix.APP_SETTINGS
    else -> PermissionFix.REQUEST
}

/** The Settings page for [fix], with the app's own page as the fallback where a phone lacks it. */
internal fun settingsIntents(fix: PermissionFix, packageName: String): List<Intent> {
    val appUri = Uri.fromParts("package", packageName, null)
    val specific = when (fix) {
        PermissionFix.NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        PermissionFix.BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        PermissionFix.DATA_SETTINGS -> Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, appUri)
        PermissionFix.OPEN_BY_DEFAULT_SETTINGS -> Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, appUri)
        PermissionFix.APP_SETTINGS, PermissionFix.REQUEST -> null
    }
    return listOfNotNull(specific?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), appSettingsIntent(packageName))
}

/** The runtime permission behind a row's prompt; photos go through the app's own photo request. */
internal val AppPermission.runtimePermission: String?
    get() = when (this) {
        AppPermission.LOCATION -> Manifest.permission.ACCESS_MEDIA_LOCATION
        AppPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
        else -> null
    }

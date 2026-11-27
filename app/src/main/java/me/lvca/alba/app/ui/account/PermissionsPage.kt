package me.lvca.alba.app.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.AppPermission
import me.lvca.alba.app.platform.AppPermissions
import me.lvca.alba.app.platform.PermissionFix
import me.lvca.alba.app.platform.PermissionPromptPreferences
import me.lvca.alba.app.platform.PhotoAccess
import me.lvca.alba.app.platform.fix
import me.lvca.alba.app.platform.photoPromptBlocked
import me.lvca.alba.app.platform.runtimePermission
import me.lvca.alba.app.platform.settingsIntents
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListGroup
import me.lvca.alba.app.ui.designsystem.AlbaListItem
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

/** "Everything Alba needs is allowed", or how many rows need the user. */
@Composable
internal fun permissionsSummary(permissions: AppPermissions): String {
    val count = permissions.needingAttention.size
    return if (count == 0) stringResource(R.string.permissions_summary_allowed)
    else pluralStringResource(R.plurals.permissions_summary_attention, count, count)
}

/**
 * The Permissions page: everything Alba needs from Android, each with its status, why it matters, and the one tap that
 * fixes it. Photos go through the app's photo request ([requestPhotos]); a prompt Android no longer shows opens Alba's
 * settings page instead.
 */
@Composable
internal fun PermissionsPage(requestPhotos: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permissions = rememberAppPermissions()
    val prompts = remember(context) { PermissionPromptPreferences(context) }
    // Saved, so an answer that arrives after a rotation still records whether Android stopped asking.
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppPermission.entries.firstOrNull { it.id == asking }?.let { permission ->
            val name = permission.runtimePermission ?: return@let
            prompts.setBlocked(permission.id, photoPromptBlocked(anyGranted = granted,
                showRationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, name) } ?: true))
        }
        asking = null
        permissions.refresh()
    }
    val current = permissions.value
    fun blocked(permission: AppPermission): Boolean = when (permission) {
        AppPermission.PHOTOS -> prompts.blocked(PermissionPromptPreferences.PHOTOS)
        // A permission already granted that still needs the user (notifications turned off) is only fixed in Settings.
        else -> prompts.blocked(permission.id) || permission.runtimePermission?.let { context.granted(it) } == true
    }
    fun apply(permission: AppPermission, fix: PermissionFix) {
        val runtime = permission.runtimePermission
        when {
            fix != PermissionFix.REQUEST -> openSettings(context, fix)
            permission == AppPermission.PHOTOS -> requestPhotos()
            runtime != null -> {
                asking = permission.id
                prompt.launch(runtime)
            }
            else -> openSettings(context, PermissionFix.APP_SETTINGS)
        }
    }
    Column(Modifier.fillMaxWidth().testTag("permissions-page"), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xl)) {
        PermissionsSummary(current)
        AlbaListGroup {
            current.shown.forEach { permission ->
                PermissionRow(permission, current, current.fix(permission, blocked(permission))) { apply(permission, it) }
            }
        }
    }
}

@Composable
private fun PermissionsSummary(permissions: AppPermissions) {
    val allowed = permissions.needingAttention.isEmpty()
    AlbaListGroup {
        AlbaListItem {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
                Icon(if (allowed) AlbaIcons.Check else AlbaIcons.ErrorOutline, null, Modifier.size(AlbaIconSize.standard),
                    tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(permissionsSummary(permissions), Modifier.testTag("permissions-summary"), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun PermissionRow(permission: AppPermission, permissions: AppPermissions, fix: PermissionFix?, apply: (PermissionFix) -> Unit) {
    AlbaListRow(
        title = stringResource(permission.title()),
        onClick = null,
        modifier = Modifier.testTag("permission-${permission.id}"),
        subtitle = permissionStatus(permission, permissions),
        subtitleModifier = Modifier.testTag("permission-status-${permission.id}"),
        trailing = {
            if (fix == null) {
                Icon(AlbaIcons.Check, stringResource(R.string.permission_allowed_description), tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = { apply(fix) }, modifier = Modifier.testTag("permission-action-${permission.id}")) {
                    Text(stringResource(actionLabel(permission, permissions, fix)))
                }
            }
        },
        contentPadding = PaddingValues(start = AlbaSpacing.lg, end = AlbaSpacing.md, top = AlbaSpacing.md, bottom = AlbaSpacing.md),
    )
}

private fun AppPermission.title(): Int = when (this) {
    AppPermission.PHOTOS -> R.string.permission_photos
    AppPermission.LOCATION -> R.string.permission_location
    AppPermission.NOTIFICATIONS -> R.string.permission_notifications
    AppPermission.BATTERY -> R.string.permission_battery
    AppPermission.BACKGROUND_DATA -> R.string.permission_background_data
    AppPermission.ICLOUD_LINKS -> R.string.permission_icloud_links
}

/** The row's status, with why it matters in the same short line. */
@Composable
private fun permissionStatus(permission: AppPermission, permissions: AppPermissions): String = stringResource(when (permission) {
    AppPermission.PHOTOS -> when (permissions.photos) {
        PhotoAccess.FULL -> R.string.permission_photos_full
        PhotoAccess.SELECTED -> R.string.permission_photos_selected
        PhotoAccess.NONE -> R.string.permission_photos_none
    }
    AppPermission.LOCATION -> if (permissions.location) R.string.permission_location_allowed else R.string.permission_location_denied
    AppPermission.NOTIFICATIONS -> if (permissions.notifications != false) R.string.permission_notifications_allowed
        else R.string.permission_notifications_denied
    AppPermission.BATTERY -> if (permissions.batteryUnrestricted) R.string.permission_battery_unrestricted
        else R.string.permission_battery_optimized
    AppPermission.BACKGROUND_DATA -> if (permissions.backgroundDataRestricted) R.string.permission_background_data_restricted
        else R.string.permission_background_data_allowed
    AppPermission.ICLOUD_LINKS -> if (permissions.iCloudLinks != false) R.string.permission_icloud_links_allowed
        else R.string.permission_icloud_links_off
})

private fun actionLabel(permission: AppPermission, permissions: AppPermissions, fix: PermissionFix): Int = when {
    fix != PermissionFix.REQUEST -> R.string.permission_open_settings
    permission == AppPermission.PHOTOS && permissions.photos == PhotoAccess.SELECTED -> R.string.permission_change
    else -> R.string.permission_allow
}

/** Opens the Settings page for [fix], or Alba's own page where the phone has no such page. */
private fun openSettings(context: Context, fix: PermissionFix) {
    for (intent in settingsIntents(fix, context.packageName)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, more general page.
        }
    }
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

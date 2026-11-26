package me.lvca.alba.app.ui.account

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.PhoneStorageState
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.ui.designsystem.AccountAvatar
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListGroup
import me.lvca.alba.app.ui.designsystem.AlbaListItem
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.AlbaSwitchRow
import me.lvca.alba.app.ui.designsystem.DemoBadge
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.designsystem.theme.AlbaTheme
import me.lvca.alba.app.ui.designsystem.theme.LocalWallpaperColors
import me.lvca.alba.app.ui.designsystem.theme.StorageColors
import me.lvca.alba.app.ui.localized
import me.lvca.alba.app.ui.storageCategoryLabel
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.engine.model.BackupView
import me.lvca.alba.engine.model.CloudPlanSource
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus
import java.time.Instant
import java.util.Locale

enum class ProfilePage(val title: Int) {
    OVERVIEW(R.string.profile_title),
    ACTIVATION(R.string.how_to_activate_icloud_photos),
    SIGN_IN(R.string.icloud_account),
    BACKUP(R.string.backup_title),
    BACKUP_FOLDERS(R.string.backup_device_folders),
    FREE_UP_SPACE(R.string.free_up_space_title),
    PHONE(R.string.phone_storage_title),
    PERMISSIONS(R.string.permissions_title),
    ABOUT(R.string.about_title),
    ;

    /** The page Back returns to: Backup's own pages return to Backup, the rest to the overview. */
    val parent: ProfilePage get() = when (this) {
        BACKUP_FOLDERS, FREE_UP_SPACE -> BACKUP
        else -> OVERVIEW
    }
}

@Composable
private fun ProfileRow(
    title: String, subtitle: String? = null, icon: ImageVector, color: Color,
    tag: String, enabled: Boolean = true, onClick: () -> Unit,
) {
    AlbaListRow(title, onClick, Modifier.testTag(tag), subtitle = subtitle, enabled = enabled, leadingSize = null,
        leading = {
            Box(Modifier.size(32.dp).background(color, MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = AlbaTheme.colors.profile.onCard, modifier = Modifier.size(AlbaIconSize.compact))
            }
        })
}

@Composable
internal fun ProfileOverview(
    session: SessionState, phoneStorage: PhoneStorageState, backup: BackupView,
    onNavigate: (ProfilePage) -> Unit, onCheckConnection: () -> Unit, onSignOut: () -> Unit,
) {
    val state = session.accountState
    val busy = session.accountBusy
    val signedIn = state as? ICloudAccountState.SignedIn
    val demo = state == ICloudAccountState.Demo
    val status = when (signedIn?.status) {
        SessionStatus.VERIFIED -> null
        SessionStatus.EXPIRED -> R.string.sign_in_again_saved_photos_available
        SessionStatus.OFFLINE -> R.string.offline_saved_library_available
        SessionStatus.PHOTOS_NOT_ENABLED -> R.string.icloud_photos_is_not_active
        else -> null
    }
    val context = LocalContext.current
    val profile = session.accountInfo.profile?.takeIf { signedIn != null }
    val allowance = session.accountInfo.storage?.takeIf { it.isDemo == demo }
    val profileColors = AlbaTheme.colors.profile
    // Apple Account's header on a calm grouped card: the account's avatar as the header shows it, the name, the
    // Apple Account and the plan. The dawn gradient stays for brand moments; this is an everyday screen.
    AlbaCard(Modifier.testTag("profile-identity"), contentPadding = PaddingValues(horizontal = AlbaSpacing.xl, vertical = AlbaSpacing.xxl)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xs)) {
            AccountAvatar(session.accountPhoto, session.accountName, avatarSize = 88.dp, textStyle = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = AlbaSpacing.sm).testTag("profile-avatar"))
            Text(profile?.displayName ?: session.savedAccountName?.takeIf { signedIn != null } ?: stringResource(R.string.icloud),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center)
            signedIn?.let { Text(it.appleId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center) }
            val planLabels = profile?.plan?.sources?.map { source -> stringResource(when (source) {
                CloudPlanSource.ICLOUD_PLUS -> R.string.profile_plan_icloud_plus
                CloudPlanSource.APPLE_ONE -> R.string.profile_plan_apple_one
                CloudPlanSource.FAMILY -> R.string.profile_plan_family
                CloudPlanSource.COMPLIMENTARY -> R.string.profile_plan_complimentary
                CloudPlanSource.MANAGED -> R.string.profile_plan_managed
            }) }
            if (demo || planLabels != null || allowance != null) Row(Modifier.padding(top = AlbaSpacing.xs),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.sm)) {
                if (demo) DemoBadge(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                planLabels?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("profile-plan-name")) }
                allowance?.let {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                        Text(icloudSize(it.totalBytes), Modifier.padding(horizontal = AlbaSpacing.sm, vertical = AlbaSpacing.xxs).testTag("profile-plan-size"),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center) }
            if (demo || signedIn?.status == SessionStatus.EXPIRED || signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED) {
                Button(onClick = { onNavigate(if (signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED) ProfilePage.ACTIVATION else ProfilePage.SIGN_IN) }, enabled = !busy,
                    modifier = Modifier.padding(top = AlbaSpacing.md).testTag("profile-sign-in")) {
                    Text(stringResource(if (signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED) R.string.how_to_activate_icloud_photos else R.string.sign_in_to_icloud))
                }
            } else if (signedIn != null) PlanLink()
        }
    }
    if (session.webAccessOff && signedIn != null) {
        Spacer(Modifier.height(AlbaSpacing.xl))
        WebAccessOffCard()
    }
    Spacer(Modifier.height(AlbaSpacing.xl))
    ProfileStorage(session, demo, signedIn != null, onCheckConnection)
    Spacer(Modifier.height(AlbaSpacing.xl))
    AccountDiagnostics(session, onCheckConnection)
    Spacer(Modifier.height(AlbaSpacing.xxl))
    AlbaListGroup(title = stringResource(R.string.profile_library_section)) {
        ProfileRow(stringResource(R.string.backup_title),
            stringResource(if (demo) R.string.demo_backup_preview else if (backup.enabled) R.string.profile_backup_on else R.string.profile_backup_off),
            AlbaIcons.CloudUpload, profileColors.backupTile, "profile-backup",
            onClick = { onNavigate(ProfilePage.BACKUP) })
        val phoneSummary = phoneStorage.usage?.let { phoneStorageSummary(it) } ?: stringResource(R.string.profile_device_detail)
        ProfileRow(stringResource(R.string.phone_storage_title), phoneSummary,
            AlbaIcons.DownloadForOffline, profileColors.phoneTile, "profile-phone", onClick = { onNavigate(ProfilePage.PHONE) })
        phoneStorage.usage?.let { usage ->
            // Under the row it details, aligned with its text.
            AlbaListItem(contentPadding = PaddingValues(start = 60.dp, end = AlbaSpacing.lg, top = AlbaSpacing.md, bottom = AlbaSpacing.lg)) {
                usage.byCategory().forEach { (category, bytes) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
                        Text(stringResource(category.title()), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Formatter.formatShortFileSize(context, bytes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        ProfileRow(stringResource(R.string.permissions_title), permissionsSummary(rememberAppPermissions().value),
            AlbaIcons.Lock, profileColors.permissionsTile, "profile-permissions", onClick = { onNavigate(ProfilePage.PERMISSIONS) })
    }
    AppearanceSettings()
    Spacer(Modifier.height(AlbaSpacing.xxl))
    AlbaListGroup {
        ProfileRow(stringResource(R.string.about_title), stringResource(R.string.profile_about_detail),
            AlbaIcons.Info, profileColors.aboutTile, "profile-about", onClick = { onNavigate(ProfilePage.ABOUT) })
    }
    if (signedIn != null) {
        Spacer(Modifier.height(AlbaSpacing.xxl))
        AlbaListGroup {
            AlbaListItem(contentPadding = PaddingValues(vertical = 6.dp)) {
                ConfirmSignOutButton(onSignOut = onSignOut) { onClick ->
                    TextButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("profile-sign-out")) {
                        Text(stringResource(R.string.sign_out_and_use_demo_library), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Appearance: "Match wallpaper colors", where the system has wallpaper colours to offer (Android 12 and later). */
@Composable
private fun AppearanceSettings() {
    val wallpaper = LocalWallpaperColors.current
    if (!wallpaper.available) return
    Spacer(Modifier.height(AlbaSpacing.xxl))
    AlbaListGroup(title = stringResource(R.string.profile_appearance_section)) {
        AlbaSwitchRow(stringResource(R.string.wallpaper_colors_title), wallpaper.enabled, wallpaper.onChange,
            Modifier.testTag("wallpaper-colors-switch"), subtitle = stringResource(R.string.wallpaper_colors_detail))
    }
}

@Composable
private fun PlanLink() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = {
        try { uriHandler.openUri("https://www.icloud.com/plan") }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.profile_browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("profile-manage-plan")) {
        Text(stringResource(R.string.profile_manage_plan))
    }
}

@Composable
internal fun ProfileStorage(session: SessionState, demo: Boolean, hasAccount: Boolean, check: () -> Unit,
    labelRes: Int = R.string.icloud_storage) {
    val info = session.accountInfo
    val storage = info.storage?.takeIf { it.isDemo == demo }
    val categoryColors = AlbaTheme.colors.profile.storage
    AlbaCard {
        Column(Modifier.padding(AlbaSpacing.xl).testTag("profile-storage"), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium)
            if (storage != null) {
                // iCloud figures in Apple's units, so a 2 TB plan reads "2 TB" as on an iPhone.
                val usageLabel = stringResource(R.string.storage_used, icloudSize(storage.usedBytes), icloudSize(storage.totalBytes))
                Text(usageLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val track = MaterialTheme.colorScheme.surfaceContainerHigh
                // The bar and its legend are the same segments: the account's categories, Family, then Other.
                val segments = storage.storageSegments()
                val segmentColors = segments.map { categoryColors.forSegment(it) }
                Canvas(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).semantics { contentDescription = usageLabel }) {
                    drawRect(track)
                    val scale = maxOf(storage.totalBytes, storage.usedBytes).coerceAtLeast(1).toDouble()
                    var drawn = 0L
                    segments.forEachIndexed { index, segment ->
                        drawRect(segmentColors[index], Offset((size.width * drawn / scale).toFloat(), 0f),
                            Size((size.width * segment.bytes / scale).toFloat(), size.height))
                        drawn += segment.bytes
                    }
                }
                Text(stringResource(R.string.storage_available, icloudSize((storage.totalBytes - storage.usedBytes).coerceAtLeast(0))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                segments.forEachIndexed { index, segment ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("storage-legend-${segment.key}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).background(segmentColors[index], CircleShape))
                        Text(segment.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(icloudSize(segment.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (storage.usedBytes >= storage.totalBytes) Text(stringResource(R.string.storage_is_full_uploads_may_be_refused), color = MaterialTheme.colorScheme.error)
                if (!demo) Text(stringResource(R.string.profile_account_allowance),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!demo) Text(stringResource(R.string.last_checked_at, timestampLabel(Instant.ofEpochMilli(storage.checkedAtEpochMillis))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(stringResource(if (info.checking) R.string.checking_storage else if (demo)
                R.string.profile_demo_storage else R.string.storage_usage_is_not_available_yet), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.error?.let { Text(it.localized(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (storage == null && hasAccount) TextButton(onClick = check, enabled = !info.checking && !session.accountBusy) {
                Text(stringResource(if (info.checking) R.string.checking else R.string.check_again))
            }
        }
    }
}

private val StorageSegment.key: String get() = when (this) {
    is StorageSegment.Category -> "category-$name"
    is StorageSegment.Family -> "family"
    is StorageSegment.Other -> "other"
}

@Composable
private fun StorageSegment.label(): String = when (this) {
    is StorageSegment.Category -> storageCategoryLabel(name)
    is StorageSegment.Family -> stringResource(R.string.storage_family)
    is StorageSegment.Other -> stringResource(R.string.other)
}

private fun StorageColors.forSegment(segment: StorageSegment): Color = when (segment) {
    is StorageSegment.Category -> forCategory(segment.name)
    is StorageSegment.Family -> family
    is StorageSegment.Other -> other
}

private fun StorageColors.forCategory(name: String): Color = when (name.lowercase(Locale.ROOT)) {
    "photos", "icloud photos", "photos and videos" -> photos
    "documents", "docs" -> documents
    "backups", "backup" -> backups
    "messages" -> messages
    "mail" -> mail
    "family", "family sharing" -> family
    else -> other
}

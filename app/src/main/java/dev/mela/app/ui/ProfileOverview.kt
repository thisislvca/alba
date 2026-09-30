package dev.mela.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mela.app.GalleryUiState
import dev.mela.app.R
import dev.mela.engine.model.BackupView
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import java.time.Instant

internal enum class ProfilePage(val title: Int) {
    OVERVIEW(R.string.profile_title), ACTIVATION(R.string.how_to_activate_icloud_photos), SIGN_IN(R.string.icloud_account),
    BACKUP(R.string.backup_title), PHONE(R.string.phone_storage_title),
    ABOUT(R.string.about_title),
}

@Composable
private fun ProfileRow(
    title: String, subtitle: String? = null, icon: ImageVector, color: Color,
    tag: String, enabled: Boolean = true, onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).background(color, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ProfileDivider() = HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))

@Composable
internal fun ProfileOverview(
    state: ICloudAccountState, diagnostics: GalleryUiState?, backup: BackupView, busy: Boolean,
    onNavigate: (ProfilePage) -> Unit, onCheckConnection: () -> Unit, onSignOut: () -> Unit,
) {
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
    val profile = diagnostics?.accountInfo?.profile?.takeIf { signedIn != null }
    val allowance = diagnostics?.accountInfo?.storage?.takeIf { it.isDemo == demo }
    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF082958), contentColor = Color.White,
        modifier = Modifier.fillMaxWidth().testTag("profile-identity")) {
        Box {
            ProfileArtwork(Modifier.matchParentSize())
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(width = 144.dp, height = 124.dp).profileAvatarCloud(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.padding(top = 18.dp)) { AccountAvatar(profile?.photo, avatarSize = 88.dp) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (demo) Surface(color = Color(0xFF332878).copy(alpha = .40f), contentColor = Color.White, shape = CircleShape) {
                        Text(stringResource(R.string.demo_badge), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    allowance?.let {
                        Surface(color = Color(0xFF332878).copy(alpha = .40f), contentColor = Color.White, shape = RoundedCornerShape(10.dp)) {
                            Text(Formatter.formatShortFileSize(context, it.totalBytes), Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile?.displayName ?: stringResource(R.string.icloud), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    signedIn?.let { Text(it.appleId, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE8ECFF)) }
                    profile?.plan?.let { plan ->
                        val labels = plan.sources.map { source -> stringResource(when (source) {
                            dev.mela.engine.model.CloudPlanSource.ICLOUD_PLUS -> R.string.profile_plan_icloud_plus
                            dev.mela.engine.model.CloudPlanSource.APPLE_ONE -> R.string.profile_plan_apple_one
                            dev.mela.engine.model.CloudPlanSource.FAMILY -> R.string.profile_plan_family
                            dev.mela.engine.model.CloudPlanSource.COMPLIMENTARY -> R.string.profile_plan_complimentary
                            dev.mela.engine.model.CloudPlanSource.MANAGED -> R.string.profile_plan_managed
                        }) }
                        Text(labels.joinToString(" · "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("profile-plan-name"))
                    }
                    status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Color(0xFFE8ECFF)) }
                }
                if (demo || signedIn?.status == SessionStatus.EXPIRED || signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED) {
                    Button(onClick = { onNavigate(if (signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED) ProfilePage.ACTIVATION else ProfilePage.SIGN_IN) }, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF08386F)), modifier = Modifier.testTag("profile-sign-in")) {
                        Text(stringResource(if (signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED) R.string.how_to_activate_icloud_photos else R.string.sign_in_to_icloud))
                    }
                } else if (signedIn != null) PlanLink(hero = true)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    ProfileStorage(diagnostics, demo, signedIn != null, onCheckConnection)
    diagnostics?.let {
        Spacer(Modifier.height(20.dp))
        AccountDiagnostics(it, onCheckConnection)
    }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.profile_library_section), Modifier.padding(start = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    MelaGroup {
        ProfileRow(stringResource(R.string.automatic_jpeg_backup),
            stringResource(if (demo) R.string.profile_backup_connect else if (backup.enabled) R.string.profile_backup_on else R.string.profile_backup_off),
            MelaIcons.CloudUpload, Color(0xFF268858), "profile-backup",
            onClick = { onNavigate(if (demo) ProfilePage.SIGN_IN else ProfilePage.BACKUP) })
        if (diagnostics != null) {
            ProfileDivider()
            val phoneSummary = diagnostics.phoneStorage?.let {
                stringResource(R.string.phone_storage_usage, Formatter.formatShortFileSize(context, it.totalBytes), Formatter.formatShortFileSize(context, it.availableBytes))
            } ?: stringResource(R.string.profile_device_detail)
            ProfileRow(stringResource(R.string.phone_storage_title), phoneSummary,
                MelaIcons.DownloadForOffline, Color(0xFF777C88), "profile-phone", onClick = { onNavigate(ProfilePage.PHONE) })
            diagnostics.phoneStorage?.let { usage ->
                Column(Modifier.padding(start = 60.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.saved_thumbnails to usage.previewBytes, R.string.viewing_cache to usage.viewerBytes,
                        R.string.offline_originals to usage.originalBytes).forEach { (label, bytes) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Formatter.formatShortFileSize(context, bytes), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    MelaGroup {
        ProfileRow(stringResource(R.string.about_title), stringResource(R.string.profile_about_detail),
            Icons.Outlined.Info, Color(0xFF8A5CCD), "profile-about", onClick = { onNavigate(ProfilePage.ABOUT) })
    }
    if (signedIn != null) {
        Spacer(Modifier.height(24.dp))
        MelaGroup {
            TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(stringResource(R.string.sign_out_and_use_demo_library), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PlanLink(hero: Boolean = false) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = {
        try { uriHandler.openUri("https://www.icloud.com/plan") }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, R.string.profile_browser_unavailable, android.widget.Toast.LENGTH_LONG).show()
        }
    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("profile-manage-plan")) {
        Text(stringResource(R.string.profile_manage_plan), color = if (hero) Color.White else MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = if (hero) Color.White else MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ProfileStorage(state: GalleryUiState?, demo: Boolean, hasAccount: Boolean, check: () -> Unit,
    labelRes: Int = R.string.icloud_storage) {
    val context = LocalContext.current
    val info = state?.accountInfo
    val storage = info?.storage?.takeIf { it.isDemo == demo }
    fun bytes(value: Long) = Formatter.formatShortFileSize(context, value)
    MelaGroup {
        Column(Modifier.padding(20.dp).testTag("profile-storage"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.titleMedium)
            if (storage != null) {
                val usageLabel = stringResource(R.string.storage_used, bytes(storage.usedBytes), bytes(storage.totalBytes))
                Text(usageLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val track = MaterialTheme.colorScheme.surfaceContainerHigh
                val unclassified = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).semantics { contentDescription = usageLabel }) {
                    drawRect(track)
                    val total = storage.totalBytes.coerceAtLeast(1).toDouble()
                    val used = storage.usedBytes.toDouble().coerceIn(0.0, total)
                    var drawn = 0.0
                    storage.categories.filter { it.bytes > 0 }.forEach { category ->
                        val amount = category.bytes.toDouble().coerceAtMost((used - drawn).coerceAtLeast(0.0))
                        if (amount > 0) drawRect(storageCategoryColor(category.name), Offset((size.width * drawn / total).toFloat(), 0f), Size((size.width * amount / total).toFloat(), size.height))
                        drawn += amount
                    }
                    if (drawn < used) drawRect(unclassified, Offset((size.width * drawn / total).toFloat(), 0f), Size((size.width * (used - drawn) / total).toFloat(), size.height))
                }
                Text(stringResource(R.string.storage_available, bytes((storage.totalBytes - storage.usedBytes).coerceAtLeast(0))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                storage.categories.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.forEach { category ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).background(storageCategoryColor(category.name), CircleShape))
                        Text(storageCategoryLabel(category.name), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(bytes(category.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (storage.usedBytes >= storage.totalBytes) Text(stringResource(R.string.storage_is_full_uploads_may_be_refused), color = MaterialTheme.colorScheme.error)
                if (!demo) Text(stringResource(R.string.profile_account_allowance),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!demo) Text(stringResource(R.string.last_checked_at, timestampLabel(Instant.ofEpochMilli(storage.checkedAtEpochMillis))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(stringResource(if (info?.checking == true) R.string.checking_storage else if (demo)
                R.string.profile_demo_storage else R.string.storage_usage_is_not_available_yet), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            info?.error?.let { Text(it.localized(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (storage == null && hasAccount) TextButton(onClick = check, enabled = info?.checking != true && state?.accountBusy != true) {
                Text(stringResource(if (info?.checking == true) R.string.checking else R.string.check_again))
            }
        }
    }
}

private fun storageCategoryColor(name: String): Color = when (name.lowercase(java.util.Locale.ROOT)) {
    "photos", "icloud photos" -> Color(0xFFF2B82F)
    "documents", "docs" -> Color(0xFFF18B29)
    "backups", "backup" -> Color(0xFF8272E8)
    "messages" -> Color(0xFF43AB69)
    "mail" -> Color(0xFF238BE6)
    "family", "family sharing" -> Color(0xFF18AAA8)
    else -> Color(0xFF858B96)
}

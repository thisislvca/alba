package dev.mela.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mela.app.R
import dev.mela.app.MelaApplication

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val diagnostics = (context.applicationContext as MelaApplication).diagnostics
    val diagnosticsEnabled by diagnostics.enabled.collectAsState()
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }
    var showLimitations by rememberSaveable { mutableStateOf(false) }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    MelaPageScaffold(stringResource(R.string.about_title), onBack) {
        Column(Modifier.testTag("about-screen"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.alba_logo_mark), contentDescription = null,
                    modifier = Modifier.size(72.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.about_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.about_intro))
            TextButton(
                onClick = {
                    try { uriHandler.openUri("https://github.com/thisislvca/mela") }
                    catch (_: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, R.string.profile_browser_unavailable,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.testTag("about-source"),
            ) {
                Text(stringResource(R.string.about_source))
            }
            AboutFeatureSection(R.string.about_features, listOf(
                AboutFeature(R.string.about_feature_library_title, R.string.about_feature_library_detail, MelaIcons.PhotoLibrary),
                AboutFeature(R.string.about_feature_shared_title, R.string.about_feature_shared_detail, Icons.Outlined.Share),
                AboutFeature(R.string.about_feature_upload_title, R.string.about_feature_upload_detail, MelaIcons.CloudUpload),
                AboutFeature(R.string.about_feature_offline_title, R.string.about_feature_offline_detail, MelaIcons.DownloadForOffline),
                AboutFeature(R.string.about_feature_backup_title, R.string.about_feature_backup_detail, MelaIcons.CloudDone),
            ))
            AboutFeatureSection(R.string.about_upcoming, listOf(
                AboutFeature(R.string.about_upcoming_formats_title, R.string.about_upcoming_formats_detail, Icons.Outlined.PlayArrow),
                AboutFeature(R.string.about_upcoming_folders_title, R.string.about_upcoming_folders_detail, Icons.Outlined.List),
                AboutFeature(R.string.about_upcoming_older_title, R.string.about_upcoming_older_detail, Icons.Outlined.DateRange),
            ), R.string.about_upcoming_note)
            TextButton(onClick = { showLimitations = !showLimitations }, modifier = Modifier.testTag("about-limitations")) {
                Text(stringResource(if (showLimitations) R.string.about_hide_limitations else R.string.about_limitations))
            }
            if (showLimitations) {
                val limitations = listOf(R.string.about_missing_backup, R.string.about_missing_formats,
                    R.string.about_missing_shared_library, R.string.about_missing_shared_flows,
                    R.string.about_missing_folders, R.string.about_missing_accounts,
                    R.string.about_missing_provider, R.string.about_missing_support)
                MelaGroup {
                    limitations.forEachIndexed { index, label ->
                        Text(stringResource(label), Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium)
                        if (index < limitations.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    }
                }
            }
            Text(stringResource(R.string.about_privacy))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = melaGroupColor(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_diagnostics_title)) },
                    supportingContent = {
                        Text(stringResource(if (diagnostics.available) R.string.about_diagnostics_description
                            else R.string.about_diagnostics_unavailable))
                    },
                    trailingContent = {
                        Switch(
                            checked = diagnosticsEnabled,
                            onCheckedChange = diagnostics::setEnabled,
                            enabled = diagnostics.available,
                            modifier = Modifier.testTag("diagnostics-switch"),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = melaGroupColor()),
                )
            }
            TextButton(onClick = { showNotices = !showNotices }, modifier = Modifier.testTag("about-notices")) {
                Text(stringResource(if (showNotices) R.string.about_hide_notices else R.string.about_notices))
            }
            if (showNotices) {
                val notices = remember(resources) { resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
                Text(notices, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class AboutFeature(val title: Int, val detail: Int, val icon: ImageVector)

@Composable
private fun AboutFeatureSection(title: Int, entries: List<AboutFeature>, subtitle: Int? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        MelaGroup {
            entries.forEachIndexed { index, entry ->
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top) {
                    Icon(entry.icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(entry.title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(entry.detail), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            }
        }
    }
}

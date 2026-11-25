package me.lvca.alba.app.ui.account

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.DiagnosticsConsent
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaFeatureRow
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.AlbaListGroup
import me.lvca.alba.app.ui.designsystem.AlbaListHeader
import me.lvca.alba.app.ui.designsystem.AlbaListItem
import me.lvca.alba.app.ui.designsystem.AlbaListRow
import me.lvca.alba.app.ui.designsystem.AlbaPageScaffold
import me.lvca.alba.app.ui.designsystem.AlbaSwitchRow
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

@Composable
internal fun AboutScreen(diagnostics: DiagnosticsConsent, onDiagnosticsEnabledChange: (Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    fun open(url: String) {
        try { uriHandler.openUri(url) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.browser_unavailable, Toast.LENGTH_LONG).show() }
    }
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }
    var showLimitations by rememberSaveable { mutableStateOf(false) }
    var showNotices by rememberSaveable { mutableStateOf(false) }
    AlbaPageScaffold(stringResource(R.string.about_title), onBack) {
        Column(Modifier.testTag("about-screen"), verticalArrangement = Arrangement.spacedBy(AlbaSpacing.xl)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AlbaSpacing.md)) {
                Image(painterResource(R.drawable.alba_logo_mark), contentDescription = null,
                    modifier = Modifier.size(72.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.about_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.about_intro))
            AboutFeatureSection(R.string.about_features, listOf(
                AboutFeature(R.string.about_feature_library_title, R.string.about_feature_library_detail, AlbaIcons.PhotoLibrary),
                AboutFeature(R.string.about_feature_shared_title, R.string.about_feature_shared_detail, Icons.Rounded.Share),
                AboutFeature(R.string.about_feature_upload_title, R.string.about_feature_upload_detail, AlbaIcons.CloudUpload),
                AboutFeature(R.string.about_feature_offline_title, R.string.about_feature_offline_detail, AlbaIcons.DownloadForOffline),
                AboutFeature(R.string.about_feature_backup_title, R.string.about_feature_backup_detail, AlbaIcons.CloudDone),
            ))
            AboutFeatureSection(R.string.about_upcoming, listOf(
                AboutFeature(R.string.about_upcoming_free_up_title, R.string.about_upcoming_free_up_detail, AlbaIcons.PhoneAndroid),
            ), R.string.about_upcoming_note)
            TextButton(onClick = { showLimitations = !showLimitations }, modifier = Modifier.testTag("about-limitations")) {
                Text(stringResource(if (showLimitations) R.string.about_hide_limitations else R.string.about_limitations))
            }
            if (showLimitations) {
                val limitations = listOf(R.string.about_missing_formats,
                    R.string.about_missing_shared_library, R.string.about_missing_shared_flows,
                    R.string.about_missing_folders, R.string.about_missing_accounts,
                    R.string.about_missing_provider, R.string.about_missing_support)
                AlbaListGroup {
                    limitations.forEach { label ->
                        AlbaListItem { Text(stringResource(label), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            Text(stringResource(R.string.about_privacy))
            AlbaListGroup {
                AlbaListRow(stringResource(R.string.about_privacy_policy), { open(PRIVACY_POLICY_URL) },
                    Modifier.testTag("about-privacy-policy"), leading = { Icon(Icons.Rounded.Lock, null) })
                AlbaListRow(stringResource(R.string.about_feedback), { open(FEEDBACK_URL) },
                    Modifier.testTag("about-feedback"), subtitle = stringResource(R.string.about_feedback_detail),
                    leading = { Icon(Icons.Rounded.Email, null) })
                AlbaListRow(stringResource(R.string.about_source), { open(SOURCE_URL) },
                    Modifier.testTag("about-source"), subtitle = stringResource(R.string.about_source_detail),
                    leading = { Icon(Icons.Rounded.Info, null) })
            }
            AlbaListGroup {
                AlbaSwitchRow(stringResource(R.string.about_diagnostics_title), diagnostics.enabled, onDiagnosticsEnabledChange,
                    Modifier.testTag("diagnostics-switch"), enabled = diagnostics.available,
                    subtitle = stringResource(if (diagnostics.available) R.string.about_diagnostics_description
                        else R.string.about_diagnostics_unavailable))
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

private const val PRIVACY_POLICY_URL = "https://github.com/thisislvca/alba/blob/main/PRIVACY.md"
private const val FEEDBACK_URL = "https://github.com/thisislvca/alba/issues/new"
private const val SOURCE_URL = "https://github.com/thisislvca/alba"

private data class AboutFeature(val title: Int, val detail: Int, val icon: ImageVector)

@Composable
private fun AboutFeatureSection(title: Int, entries: List<AboutFeature>, subtitle: Int? = null) {
    Column {
        AlbaListHeader(stringResource(title))
        subtitle?.let { Text(stringResource(it), Modifier.padding(start = AlbaSpacing.lg, end = AlbaSpacing.lg, bottom = AlbaSpacing.sm),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        AlbaListGroup {
            entries.forEach { entry -> AlbaFeatureRow(entry.icon, stringResource(entry.title), stringResource(entry.detail)) }
        }
    }
}

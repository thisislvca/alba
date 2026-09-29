package dev.mela.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mela.app.GalleryUiState
import dev.mela.app.R
import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus

/** Useful connection facts live on the overview rather than behind another destination. */
@Composable
internal fun AccountDiagnostics(state: GalleryUiState, check: () -> Unit) {
    val info = state.accountInfo
    val signedIn = state.accountState as? ICloudAccountState.SignedIn
    val status = when {
        state.accountState == ICloudAccountState.Demo -> R.string.no_apple_account_connected
        signedIn?.status == SessionStatus.EXPIRED -> R.string.sign_in_expired_saved_copies_available
        signedIn?.status == SessionStatus.PHOTOS_NOT_ENABLED -> R.string.icloud_photos_is_not_active
        signedIn?.status == SessionStatus.OFFLINE -> R.string.could_not_verify_session_saved_copies_available
        signedIn?.status == SessionStatus.VERIFIED -> R.string.apple_session_verified
        else -> R.string.restoring_your_session
    }
    MelaGroup(Modifier.testTag("profile-connection")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.connection), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box(Modifier.size(8.dp).background(if (signedIn?.status == SessionStatus.VERIFIED && state.network?.online == true)
                    Color(0xFF268858) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            ConnectionFact(stringResource(R.string.profile_network), stringResource(when {
                state.network == null -> R.string.checking_network
                !state.network.online -> R.string.no_internet_connection
                state.network.metered -> R.string.metered_network_automatic_backup_waits_for_unmetered_internet
                else -> R.string.unmetered_internet_automatic_backup_can_run_when_enabled
            }))
            if (signedIn != null) ConnectionFact(stringResource(R.string.photos), stringResource(when (info.photosAvailable) {
                true -> R.string.photos_service_is_available_for_this_account
                false -> R.string.apple_did_not_provide_a_photos_service_for_this_account
                null -> R.string.photos_service_has_not_been_checked
            }))
            Text(state.lastRefreshedAt?.let { stringResource(R.string.library_refreshed_at, timestampLabel(it)) }
                ?: stringResource(R.string.library_has_not_refreshed_in_this_session),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = check, enabled = !info.checking && !state.accountBusy,
                contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.testTag("profile-check-connection")) {
                Text(stringResource(if (info.checking) R.string.checking else R.string.check_connection_and_storage))
            }
        }
    }
}

@Composable
private fun ConnectionFact(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

package me.lvca.alba.app.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.account.SessionState
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.account.BackupStatus
import me.lvca.alba.app.ui.account.backupStatus
import me.lvca.alba.app.ui.account.headline
import me.lvca.alba.app.ui.designsystem.AccountAvatar
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.viewer.reduceGalleryMotion
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.account.SessionStatus

/** What the account and the library are doing, when it is worth a mark on the avatar. */
internal enum class AccountStatusKind { SYNCING, PAUSED, ERROR, OFFLINE }

/** A state for the avatar's mark: what it is, the words that say it, and how far a first load is when known. */
internal data class AccountStatus(val kind: AccountStatusKind, val description: String, val progress: Float? = null)

/**
 * The account and sync state the header once spelled out under the logo, now a quiet mark on the avatar. A verified,
 * idle account, a refresh of a library Alba already shows, the demo and a signed-out app have none.
 */
@Composable
internal fun accountStatus(session: SessionState, transfers: TransferState, libraryLoad: LibraryLoad): AccountStatus? {
    val signedIn = session.accountState as? ICloudAccountState.SignedIn
    val backup = backupStatus(transfers, session.accountState, session.network, session.charging)
    return when {
        session.accountState == ICloudAccountState.Demo -> null
        session.accountState == ICloudAccountState.Restoring ->
            AccountStatus(AccountStatusKind.SYNCING, stringResource(R.string.restoring_icloud))
        signedIn == null -> null
        signedIn.status == SessionStatus.EXPIRED ->
            AccountStatus(AccountStatusKind.ERROR, stringResource(R.string.sign_in_again_saved_photos_available))
        signedIn.status == SessionStatus.PHOTOS_NOT_ENABLED ->
            AccountStatus(AccountStatusKind.ERROR, stringResource(R.string.icloud_photos_is_not_active))
        session.network?.online == false || signedIn.status == SessionStatus.OFFLINE ->
            AccountStatus(AccountStatusKind.OFFLINE, stringResource(R.string.offline_saved_photos_available))
        // Apple is still indexing: say so for as long as it lasts, through the quiet checks Alba makes meanwhile.
        session.libraryPreparing != null ->
            AccountStatus(AccountStatusKind.SYNCING, libraryPreparingLabel(session.libraryPreparing), session.libraryPreparing.progress?.let { it / 100f })
        // Only a first load: a refresh of a library Alba already shows says nothing, as iCloud.com does.
        libraryLoad == LibraryLoad.INITIAL_LOAD -> AccountStatus(AccountStatusKind.SYNCING,
            session.catalogScan?.let { catalogScanLabel(it) } ?: stringResource(R.string.updating_library), session.catalogScan?.fraction())
        backup == BackupStatus.Paused || backup is BackupStatus.PausedForICloudChange ->
            AccountStatus(AccountStatusKind.PAUSED, backup.headline())
        else -> null
    }
}

/**
 * The account's avatar as the header's account button, with [status] marked on it: a thin ring around it while the
 * account or library syncs (filling when the progress is known), and a small badge when sync is paused, the phone is
 * offline or the account needs attention. The mark's content description says the state; tapping opens the account.
 */
@Composable
internal fun AccountButton(appleId: String?, status: AccountStatus?, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A 48dp button, so the ring and badge fit inside its round clip.
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            // The same avatar as the profile card's.
            AccountAvatar(photo = null, name = appleId, avatarSize = 32.dp)
            if (status != null) AccountStatusMark(status)
        }
    }
}

@Composable
private fun AccountStatusMark(status: AccountStatus) {
    val mark = Modifier.testTag("account-status-indicator").semantics { contentDescription = status.description }
    val colors = MaterialTheme.colorScheme
    when (status.kind) {
        AccountStatusKind.SYNCING -> {
            val ring = mark.size(40.dp)
            val progress = status.progress
            // Reduced motion keeps the ring still: full, or as far as the load has come.
            if (progress != null || reduceGalleryMotion()) {
                CircularProgressIndicator(progress = { progress ?: 1f }, ring, strokeWidth = 2.dp, trackColor = colors.surfaceContainerHighest)
            } else CircularProgressIndicator(ring, strokeWidth = 2.dp, trackColor = colors.surfaceContainerHighest)
        }
        AccountStatusKind.ERROR -> Badge(mark.offset(10.dp, 10.dp), containerColor = colors.error, contentColor = colors.onError) {
            Text("!")
        }
        AccountStatusKind.OFFLINE -> StatusBadge(AlbaIcons.CloudOff, mark)
        AccountStatusKind.PAUSED -> StatusBadge(AlbaIcons.Pause, mark)
    }
}

@Composable
private fun StatusBadge(icon: ImageVector, modifier: Modifier) {
    // Centred on the avatar's edge, down and to the end, inside the button's round clip.
    Badge(modifier.offset(10.dp, 10.dp), containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Icon(icon, contentDescription = null, Modifier.size(10.dp))
    }
}

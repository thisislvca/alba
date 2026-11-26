package me.lvca.alba.app.ui.transfers

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.localizedStoredMessage
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.transfers.TransferState
import me.lvca.alba.app.ui.QueueActions
import me.lvca.alba.app.ui.account.FailedTransferStates
import me.lvca.alba.app.ui.designsystem.AlbaListGroup
import me.lvca.alba.app.ui.designsystem.AlbaListItem
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing
import me.lvca.alba.app.ui.localized
import me.lvca.alba.engine.model.LARGE_ITEM_BYTES
import me.lvca.alba.engine.model.TransferFailureReason
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.TransferViewState

/** Items that have not gone through for the same [reason], which one action fixes. */
internal data class FailureGroup(val reason: TransferFailureReason, val items: List<TransferView>)

/** The tag of [reason]'s group: `backup-failure-group-waiting-to-retry` and so on. */
internal val TransferFailureReason.tag: String get() = name.lowercase().replace('_', '-')

/**
 * The items that need the user, or wait on something the user can fix, grouped by why, in [TransferFailureReason] order.
 * A queued automatic item over [LARGE_ITEM_BYTES] needs Wi-Fi while the phone is on mobile data or offline; every queued
 * item waits for space while iCloud is full. The demo's sample rows have no reasons.
 */
internal fun failureGroups(transfers: TransferState, network: NetworkStatus?, demo: Boolean): List<FailureGroup> {
    if (demo) return emptyList()
    val storageFull = transfers.backup.storageFullSinceEpochMillis != null
    val onMobileOrOffline = network != null && (!network.online || network.metered)
    return transfers.queue.mapNotNull { item ->
        val waiting = item.state == TransferViewState.WAITING || item.state == TransferViewState.UPLOADING
        val reason = when {
            item.failureReason == TransferFailureReason.WAITING_TO_RETRY -> item.failureReason
            waiting && storageFull -> TransferFailureReason.STORAGE_FULL
            item.state == TransferViewState.WAITING && item.automatic && item.byteCount > LARGE_ITEM_BYTES && onMobileOrOffline ->
                TransferFailureReason.NEEDS_UNMETERED
            item.failureReason != null -> item.failureReason
            item.state in FailedTransferStates -> fallbackReason(item.state)
            else -> null
        }
        reason?.let { it to item }
    }.groupBy({ it.first }, { it.second }).toSortedMap().map { (reason, items) -> FailureGroup(reason, items) }
}

/** A failed item from before reasons were kept. */
private fun fallbackReason(state: TransferViewState): TransferFailureReason = when (state) {
    TransferViewState.UNRESOLVED -> TransferFailureReason.CHECKING_ICLOUD
    TransferViewState.UNSUPPORTED -> TransferFailureReason.FORMAT_NOT_SUPPORTED
    else -> TransferFailureReason.STOPPED
}

/**
 * Each group as a stock grouped list: its reason, what it means, its items with their reasons and next try times when
 * [showItems], and the one action that fixes it. The Activity hero shows the groups without their items.
 */
@Composable
internal fun FailureGroups(
    groups: List<FailureGroup>,
    actions: QueueActions,
    enableAutomaticBackup: () -> Unit,
    showItems: Boolean,
    now: Long = System.currentTimeMillis(),
    itemLimit: Int = Int.MAX_VALUE,
) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AlbaSpacing.lg)) {
        groups.forEach { group ->
            AlbaListGroup(Modifier.testTag("backup-failure-group-${group.reason.tag}"), title = group.reason.title()) {
                AlbaListItem {
                    Text(pluralStringResource(R.plurals.backup_failure_count, group.items.size, group.items.size),
                        style = MaterialTheme.typography.titleSmall)
                    Text(group.reason.explanation(), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FixButton(group, actions, enableAutomaticBackup)
                }
                if (showItems) group.items.take(itemLimit).forEach { FailureRow(it, actions, now) }
            }
        }
    }
}

@Composable
private fun FailureRow(item: TransferView, actions: QueueActions, now: Long) {
    AlbaListItem {
        Text(item.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        val detail = item.nextTryAtEpochMillis?.let { nextTryLabel(it, now) }
            ?: item.message?.let { localizedStoredMessage(it).localized() }
            ?: item.state.label()
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("backup-failure-detail-${item.id.value}"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.retryable) {
                TextButton(onClick = { actions.retryTransfer(item.id) }, modifier = Modifier.testTag("retry-transfer-${item.id.value}")) {
                    Text(stringResource(R.string.retry))
                }
            }
            if (item.state == TransferViewState.UNRESOLVED) {
                // Never a blind retry: the upload may have reached iCloud, so skipping is the only choice offered.
                TextButton(onClick = { actions.continuePastUnresolved(item.id) }, modifier = Modifier.testTag("skip-unresolved-${item.id.value}")) {
                    Text(stringResource(R.string.skip_unresolved_item))
                }
            }
        }
    }
}

/** "Next try: 3:15 PM", with the date when it is a day away, or "Trying again soon" once that time has come. */
@Composable
internal fun nextTryLabel(nextTry: Long, now: Long): String =
    if (nextTry <= now) stringResource(R.string.backup_next_try_soon)
    else stringResource(R.string.backup_next_try, nextTryTime(LocalContext.current, nextTry, now))

/** The time of [nextTry] in the phone's format, with the date when it is more than [DATE_SHOWN_AFTER_MILLIS] away. */
internal fun nextTryTime(context: android.content.Context, nextTry: Long, now: Long): String {
    val date = if (nextTry - now > DATE_SHOWN_AFTER_MILLIS) DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH else 0
    return DateUtils.formatDateTime(context, nextTry, DateUtils.FORMAT_SHOW_TIME or date)
}

private const val DATE_SHOWN_AFTER_MILLIS = 20 * 60 * 60 * 1_000L

@Composable
private fun FixButton(group: FailureGroup, actions: QueueActions, enableAutomaticBackup: () -> Unit) {
    val context = LocalContext.current
    val uris = LocalUriHandler.current
    val fix: () -> Unit = when (group.reason) {
        TransferFailureReason.WAITING_TO_RETRY -> actions::retryWaitingNow
        TransferFailureReason.NEEDS_UNMETERED -> {
            {
                try { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: ActivityNotFoundException) { }
            }
        }
        TransferFailureReason.STORAGE_FULL -> {
            { try { uris.openUri(ICLOUD_STORAGE_URL) } catch (_: IllegalArgumentException) { } }
        }
        TransferFailureReason.PHOTO_ACCESS -> enableAutomaticBackup
        TransferFailureReason.FORMAT_NOT_SUPPORTED, TransferFailureReason.STOPPED, TransferFailureReason.LIVE_PHOTO_NOT_ATTACHED ->
            actions::retryAllTransfers
        TransferFailureReason.FILE_GONE -> { { actions.dismissFailedTransfers(group.items.filter { it.retryable }.map { it.id }) } }
        TransferFailureReason.CHECKING_ICLOUD -> actions::checkICloudNow
    }
    FilledTonalButton(onClick = fix, modifier = Modifier.fillMaxWidth().testTag("backup-failure-action-${group.reason.tag}")) {
        Text(group.reason.fixLabel())
    }
}

/** Where iCloud storage is managed: Apple's plan page on iCloud.com. */
private const val ICLOUD_STORAGE_URL = "https://www.icloud.com/plan"

@Composable
internal fun TransferFailureReason.title(): String = stringResource(when (this) {
    TransferFailureReason.WAITING_TO_RETRY -> R.string.backup_failure_waiting_to_retry
    TransferFailureReason.NEEDS_UNMETERED -> R.string.backup_failure_needs_unmetered
    TransferFailureReason.STORAGE_FULL -> R.string.backup_failure_storage_full
    TransferFailureReason.PHOTO_ACCESS -> R.string.backup_failure_photo_access
    TransferFailureReason.FORMAT_NOT_SUPPORTED -> R.string.backup_failure_format_not_supported
    TransferFailureReason.FILE_GONE -> R.string.backup_failure_file_gone
    TransferFailureReason.CHECKING_ICLOUD -> R.string.backup_failure_checking_icloud
    TransferFailureReason.STOPPED -> R.string.backup_failure_stopped
    TransferFailureReason.LIVE_PHOTO_NOT_ATTACHED -> R.string.backup_failure_live_photo
})

@Composable
private fun TransferFailureReason.explanation(): String = stringResource(when (this) {
    TransferFailureReason.WAITING_TO_RETRY -> R.string.backup_failure_waiting_to_retry_detail
    TransferFailureReason.NEEDS_UNMETERED -> R.string.backup_failure_needs_unmetered_detail
    TransferFailureReason.STORAGE_FULL -> R.string.backup_failure_storage_full_detail
    TransferFailureReason.PHOTO_ACCESS -> R.string.backup_failure_photo_access_detail
    TransferFailureReason.FORMAT_NOT_SUPPORTED -> R.string.backup_failure_format_not_supported_detail
    TransferFailureReason.FILE_GONE -> R.string.backup_failure_file_gone_detail
    TransferFailureReason.CHECKING_ICLOUD -> R.string.backup_failure_checking_icloud_detail
    TransferFailureReason.STOPPED -> R.string.backup_failure_stopped_detail
    TransferFailureReason.LIVE_PHOTO_NOT_ATTACHED -> R.string.backup_failure_live_photo_detail
})

@Composable
private fun TransferFailureReason.fixLabel(): String = stringResource(when (this) {
    TransferFailureReason.WAITING_TO_RETRY -> R.string.backup_fix_retry_now
    TransferFailureReason.NEEDS_UNMETERED -> R.string.backup_fix_wifi_settings
    TransferFailureReason.STORAGE_FULL -> R.string.backup_fix_open_storage
    TransferFailureReason.PHOTO_ACCESS -> R.string.backup_fix_grant_access
    TransferFailureReason.FORMAT_NOT_SUPPORTED -> R.string.backup_fix_try_again
    TransferFailureReason.FILE_GONE -> R.string.backup_fix_remove
    TransferFailureReason.CHECKING_ICLOUD -> R.string.backup_fix_check_now
    TransferFailureReason.STOPPED, TransferFailureReason.LIVE_PHOTO_NOT_ATTACHED -> R.string.retry_all
})

package me.lvca.alba.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.platform.MobileDataChoice
import me.lvca.alba.app.platform.NetworkStatus
import me.lvca.alba.app.platform.UploadTarget
import me.lvca.alba.app.transfers.FreeUpSpaceState
import me.lvca.alba.engine.companion.BatchAction

/** A manual upload or download the user started, held while Alba asks about notifications or mobile data first. */
internal sealed interface PendingTransfer {
    data object PickUpload : PendingTransfer
    data class Upload(val target: UploadTarget) : PendingTransfer
    data class Batch(val action: BatchAction) : PendingTransfer
    data class KeepOffline(val mediaId: String) : PendingTransfer
    data class Retry(val transferId: String) : PendingTransfer
    data object RetryAll : PendingTransfer

    /** "Free up space" downloads each original from iCloud to check it, so it asks about mobile data too. */
    data class FreeUp(val mediaIds: List<String>) : PendingTransfer

    companion object {
        val Saver = Saver<PendingTransfer?, List<String>>(
            save = { pending ->
                when (pending) {
                    PickUpload -> listOf("pick")
                    is Upload -> when (val target = pending.target) {
                        UploadTarget.Selection -> listOf("upload-selection")
                        is UploadTarget.Media -> listOf("upload-media", target.mediaId)
                    }
                    is Batch -> listOf("batch", pending.action.name)
                    is KeepOffline -> listOf("offline", pending.mediaId)
                    is Retry -> listOf("retry", pending.transferId)
                    RetryAll -> listOf("retry-all")
                    is FreeUp -> listOf("free-up") + pending.mediaIds
                    null -> null
                }
            },
            restore = { saved ->
                when (saved[0]) {
                    "pick" -> PickUpload
                    "upload-selection" -> Upload(UploadTarget.Selection)
                    "upload-media" -> Upload(UploadTarget.Media(saved[1]))
                    "batch" -> Batch(BatchAction.valueOf(saved[1]))
                    "offline" -> KeepOffline(saved[1])
                    "retry" -> Retry(saved[1])
                    "retry-all" -> RetryAll
                    "free-up" -> FreeUp(saved.drop(1))
                    else -> null
                }
            },
        )
    }
}

/** What Alba asks before a manual transfer, in order. */
internal enum class TransferPrompt { NOTIFICATIONS, MOBILE_DATA }

/**
 * The next question before a manual transfer: notifications once, the first time the user transfers anything, then
 * mobile data when the phone is on it and the user has not remembered an answer. Null means go ahead.
 */
internal fun nextTransferPrompt(
    askNotifications: Boolean,
    network: NetworkStatus?,
    choice: MobileDataChoice,
    after: TransferPrompt?,
): TransferPrompt? {
    val onMobileData = network?.online == true && network.metered
    return when {
        after == null && askNotifications -> TransferPrompt.NOTIFICATIONS
        after != TransferPrompt.MOBILE_DATA && onMobileData && choice == MobileDataChoice.ASK -> TransferPrompt.MOBILE_DATA
        else -> null
    }
}

/** One line on why notifications help, before Android's own prompt. */
@Composable
internal fun NotificationsRationale(allow: () -> Unit, notNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = notNow,
        title = { Text(stringResource(R.string.notifications_rationale_title)) },
        text = { Text(stringResource(R.string.notifications_rationale_body)) },
        confirmButton = {
            TextButton(onClick = allow, modifier = Modifier.testTag("notifications-allow")) { Text(stringResource(R.string.notifications_allow)) }
        },
        dismissButton = { TextButton(onClick = notNow) { Text(stringResource(R.string.not_now)) } },
        modifier = Modifier.testTag("notifications-rationale"),
    )
}

/** "Free up space" checking its items, with Cancel. Nothing leaves the phone before Android's own request. */
@Composable
internal fun FreeUpSpaceProgress(run: FreeUpSpaceState, cancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.free_up_space_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.free_up_space_checking, run.checked, run.total))
                LinearProgressIndicator(
                    progress = { if (run.total == 0) 0f else run.checked.toFloat() / run.total },
                    modifier = Modifier.fillMaxWidth().testTag("free-up-space-progress"),
                )
            }
        },
        confirmButton = { TextButton(onClick = cancel, modifier = Modifier.testTag("free-up-space-cancel")) { Text(stringResource(R.string.cancel)) } },
        modifier = Modifier.testTag("free-up-space-running"),
    )
}

/** A "Free up space" run left from before a restart: continue it, or leave the items on the phone. */
@Composable
internal fun ContinueFreeUpSpace(leftover: Int, resume: () -> Unit, discard: () -> Unit) {
    AlertDialog(
        onDismissRequest = discard,
        title = { Text(stringResource(R.string.free_up_space_continue_title)) },
        text = { Text(pluralStringResource(R.plurals.free_up_space_continue_body, leftover, leftover)) },
        confirmButton = {
            TextButton(onClick = resume, modifier = Modifier.testTag("free-up-space-continue")) {
                Text(stringResource(R.string.welcome_continue))
            }
        },
        dismissButton = { TextButton(onClick = discard) { Text(stringResource(R.string.not_now)) } },
        modifier = Modifier.testTag("free-up-space-leftover"),
    )
}

/** "Use mobile data?" with an option to remember the answer. Dismissing it starts nothing. */
@Composable
internal fun MobileDataPrompt(answer: (useMobileData: Boolean, remember: Boolean) -> Unit, dismiss: () -> Unit) {
    var remember by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.mobile_data_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mobile_data_prompt_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, { remember = it }, Modifier.testTag("mobile-data-remember"))
                    Text(stringResource(R.string.mobile_data_prompt_remember))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { answer(true, remember) }, modifier = Modifier.testTag("mobile-data-use")) {
                Text(stringResource(R.string.mobile_data_prompt_use))
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(false, remember) }, modifier = Modifier.testTag("mobile-data-wait")) {
                Text(stringResource(R.string.mobile_data_prompt_wait))
            }
        },
        modifier = Modifier.testTag("mobile-data-prompt"),
    )
}

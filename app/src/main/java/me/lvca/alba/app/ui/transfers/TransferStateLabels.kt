package me.lvca.alba.app.ui.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaIcons

internal fun TransferViewState.icon(): ImageVector = when (this) {
    TransferViewState.WAITING -> AlbaIcons.CloudUpload
    TransferViewState.UPLOADING -> AlbaIcons.CloudUpload
    TransferViewState.CHECKING_ICLOUD -> AlbaIcons.CloudQueue
    TransferViewState.VERIFIED -> AlbaIcons.CloudDone
    TransferViewState.UNRESOLVED -> AlbaIcons.ErrorOutline
    TransferViewState.SKIPPED -> AlbaIcons.ErrorOutline
    TransferViewState.NEEDS_SIGN_IN -> Icons.Outlined.Lock
    TransferViewState.NEEDS_ATTENTION -> AlbaIcons.ErrorOutline
    TransferViewState.UNSUPPORTED -> AlbaIcons.ErrorOutline
}

@Composable
internal fun TransferViewState.label(): String = when (this) {
    TransferViewState.WAITING -> stringResource(R.string.waiting_to_upload)
    TransferViewState.UPLOADING -> stringResource(R.string.uploading)
    TransferViewState.CHECKING_ICLOUD -> stringResource(R.string.checking_icloud)
    TransferViewState.VERIFIED -> stringResource(R.string.verified_in_icloud)
    TransferViewState.UNRESOLVED -> stringResource(R.string.upload_result_unresolved)
    TransferViewState.SKIPPED -> stringResource(R.string.uncertain_item_kept_blocked)
    TransferViewState.NEEDS_SIGN_IN -> stringResource(R.string.needs_icloud_sign_in)
    TransferViewState.NEEDS_ATTENTION -> stringResource(R.string.needs_attention)
    TransferViewState.UNSUPPORTED -> stringResource(R.string.unsupported_original)
}

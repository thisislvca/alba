package me.lvca.alba.app.ui.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.label
import me.lvca.alba.engine.model.*
import me.lvca.alba.protocol.account.ICloudAccountState

/** Where the photo is kept and whether its original is on the phone. Only claims the local catalog establishes. */
@Composable
internal fun PhotoSourceCard(media: GalleryMedia, account: ICloudAccountState, transfer: TransferView?) {
    val colors = viewerColors()
    val verified = transfer?.state == TransferViewState.VERIFIED
    val source = when {
        media.isShared -> stringResource(R.string.shared_album_copy)
        media.linkedDeviceReference != null -> stringResource(R.string.on_phone_and_icloud)
        verified && transfer?.alreadyInICloud == true -> stringResource(R.string.already_in_icloud)
        verified -> stringResource(R.string.verified_in_icloud)
        media.origin == MediaOrigin.DEVICE -> stringResource(R.string.this_phone)
        account == ICloudAccountState.Demo -> stringResource(R.string.library)
        else -> stringResource(R.string.personal_icloud_library)
    }
    AlbaCard(Modifier.testTag("photo-source"), color = colors.card, contentColor = colors.foreground) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(when {
                verified -> AlbaIcons.CloudDone
                media.origin == MediaOrigin.DEVICE -> AlbaIcons.PhoneAndroid
                media.availability == MediaAvailability.ORIGINAL_CACHED -> AlbaIcons.DownloadForOffline
                else -> AlbaIcons.CloudQueue
            }, null, Modifier.size(28.dp), tint = colors.secondary)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source, style = MaterialTheme.typography.titleMedium)
                Text((if (media.linkedDeviceReference != null) MediaAvailability.DEVICE_ORIGINAL else media.availability).label(),
                    style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
            }
        }
    }
}

@Composable
internal fun MetadataChip(label: String) {
    val colors = viewerColors()
    Surface(shape = MaterialTheme.shapes.extraSmall, color = colors.chip, contentColor = colors.secondary) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.bodySmall)
    }
}

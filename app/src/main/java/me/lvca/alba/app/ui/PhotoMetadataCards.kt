package me.lvca.alba.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lvca.alba.engine.model.*
import me.lvca.alba.protocol.account.ICloudAccountState
import java.text.NumberFormat
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import me.lvca.alba.app.R

/** Only show metadata and backup claims that the local catalog actually establishes. */
@Composable
internal fun PhotoMetadataCards(media: GalleryMedia, account: ICloudAccountState, transfer: TransferView?) {
    val colors = viewerColors()
    val verified = transfer?.state == TransferViewState.VERIFIED
    val source = when {
        media.isShared -> stringResource(R.string.shared_album_copy)
        media.linkedDeviceReference != null -> stringResource(R.string.on_phone_and_icloud)
        verified -> stringResource(R.string.verified_in_icloud)
        media.origin == MediaOrigin.DEVICE -> stringResource(R.string.this_phone)
        account == ICloudAccountState.Demo -> stringResource(R.string.library)
        else -> stringResource(R.string.personal_icloud_library)
    }
    val number = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 1 }
    AlbaCard(color = colors.card, contentColor = colors.foreground) {
        Column {
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
                    Text((if (media.linkedDeviceReference != null) MediaAvailability.DEVICE_ORIGINAL else media.availability).label(), style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
                }
            }
            HorizontalDivider(Modifier.padding(start = 66.dp), color = colors.canvas)
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.icloud_library), null, Modifier.padding(top = 4.dp).size(28.dp), tint = colors.secondary)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(media.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (media.width > 0 && media.height > 0) {
                            MetadataChip(stringResource(R.string.megapixels, number.format(media.width.toLong() * media.height / 1_000_000.0)))
                            MetadataChip(stringResource(R.string.pixel_dimensions, media.width, media.height))
                        }
                        media.byteCount?.let { MetadataChip(Formatter.formatShortFileSize(LocalContext.current, it)) }
                        MetadataChip(media.mimeType.substringAfter('/').uppercase(Locale.ROOT))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataChip(label: String) {
    val colors = viewerColors()
    Surface(shape = MaterialTheme.shapes.extraSmall, color = colors.chip, contentColor = colors.secondary) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.bodySmall)
    }
}

package me.lvca.alba.app.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.currentLocale
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaFact
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaIconSize
import me.lvca.alba.app.ui.gallery.mediaDuration
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaDetails
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.TransferView
import me.lvca.alba.engine.model.mediaFormatLabel
import me.lvca.alba.protocol.account.ICloudAccountState
import java.io.File
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The Info title, as in Apple Photos: when the photo was taken, in its own time zone. It is the only place the capture
 * date appears, and the sheet pins it above the scrolling facts so it is always in reach.
 */
@Composable
internal fun PhotoInfoHeader(media: GalleryMedia, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (time, zone) = captureTimeParts(media.capturedAtEpochMillis, media.details.timeZoneOffsetSeconds, currentLocale(),
        android.text.format.DateFormat.is24HourFormat(context))
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("photo-info-title"), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.time_with_zone, time, zone), Modifier.weight(1f).testTag("photo-taken"),
            style = MaterialTheme.typography.titleMedium)
        onClose?.let { IconButton(onClick = it) { Icon(AlbaIcons.Close, stringResource(R.string.close_details)) } }
    }
}

/**
 * Everything Info says about a photo below its date, in Apple Photos' order: the caption, the camera card (device, lens,
 * file name and format, size, dimensions and megapixels, exposure), the place, then where the photo is kept. Shared by
 * the viewer's sheet and any other surface that shows Info, so they never drift apart.
 */
@Composable
internal fun PhotoInfoContent(media: GalleryMedia, account: ICloudAccountState, transfer: TransferView?, modifier: Modifier = Modifier) {
    val labelColor = LocalContentColor.current
    val factModifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    val reading = rememberFileReading(media)
    Column(modifier.fillMaxWidth().testTag("photo-info")) {
        PhotoCaption(media)
        PhotoCameraCard(media, reading.camera)
        if (media.details.hasLocation) PhotoLocation(media.details, factModifier)
        // iCloud's own location wins; the file's GPS tags are only a fallback.
        else reading.coordinates?.let { (latitude, longitude) ->
            AlbaFact(stringResource(R.string.photo_location), coordinatesLabel(latitude, longitude),
                factModifier.testTag("photo-location"), labelColor = labelColor)
        }
        if (media.kind == MediaKind.VIDEO) mediaDuration(media.durationMillis)?.let {
            AlbaFact(stringResource(R.string.photo_duration), it, factModifier, labelColor = labelColor)
        }
        media.deviceFolderName?.let { AlbaFact(stringResource(R.string.photo_folder), it, factModifier, labelColor = labelColor) }
        media.addedAtEpochMillis?.takeIf { addedLongAfterCapture(media.capturedAtEpochMillis, it) }?.let { added ->
            AlbaFact(stringResource(R.string.photo_added), timestampLabel(added), factModifier.testTag("photo-added"), labelColor = labelColor)
        }
        Spacer(Modifier.height(16.dp))
        PhotoSourceCard(media, account, transfer)
    }
}

/** The title and caption written in Photos, shown above the photo's details. */
@Composable
internal fun PhotoCaption(media: GalleryMedia) {
    val caption = media.details.caption
    val description = media.details.description
    if (caption == null && description == null) return
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("photo-caption"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        caption?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * Apple's camera card: the device, the lens ("Main Camera — 24 mm ƒ1.78"), the file name with its format, its size,
 * dimensions and megapixels, and the exposure. Lines the file doesn't record are left out; the file line always shows.
 */
@Composable
private fun PhotoCameraCard(media: GalleryMedia, camera: CameraExif?) {
    val colors = viewerColors()
    val locale = currentLocale()
    val device = camera?.let(CameraFormat::device)
    val lens = camera?.let { CameraFormat.lensLine(it, CameraFormat.lensRole(it)?.let { role -> lensRoleName(role) }, locale) }
    val exposure = camera?.let { CameraFormat.exposure(it, locale) }
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    val context = LocalContext.current
    val sized = media.width > 0 && media.height > 0
    val megapixels = number.format(media.width.toLong() * media.height / 1_000_000.0)
    val fileFacts = listOfNotNull(
        media.byteCount?.let { Formatter.formatShortFileSize(context, it) },
        if (sized) stringResource(R.string.pixel_dimensions, media.width, media.height) else null,
        if (sized) stringResource(R.string.megapixels, megapixels) else null,
    ).joinToString(" · ")
    AlbaCard(Modifier.testTag("photo-camera-card"), color = colors.card, contentColor = colors.foreground) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            device?.let { Text(it, Modifier.testTag("photo-device"), style = MaterialTheme.typography.titleMedium) }
            lens?.let { Text(it, Modifier.testTag("photo-lens"), style = MaterialTheme.typography.bodyMedium, color = colors.secondary) }
            Row(Modifier.fillMaxWidth().padding(top = if (device != null || lens != null) 8.dp else 0.dp).testTag("photo-file"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(media.fileName, Modifier.weight(1f, fill = false), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = if (device == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                MetadataChip(mediaFormatLabel(media.mimeType))
            }
            if (fileFacts.isNotEmpty()) Text(fileFacts, Modifier.testTag("photo-file-facts"),
                style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
            exposure?.let { Text(it, Modifier.padding(top = 8.dp).testTag("photo-exposure"), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun lensRoleName(role: LensRole): String = stringResource(when (role) {
    LensRole.MAIN -> R.string.lens_main_camera
    LensRole.ULTRA_WIDE -> R.string.lens_ultra_wide_camera
    LensRole.TELEPHOTO -> R.string.lens_telephoto_camera
    LensRole.FRONT -> R.string.lens_front_camera
})

/** What the photo's own file records: its camera facts and GPS position, read off the main thread. */
private data class FileReading(val camera: CameraExif? = null, val coordinates: Pair<Double, Double>? = null)

@Composable
private fun rememberFileReading(media: GalleryMedia): FileReading {
    val context = LocalContext.current
    val reference = media.linkedDeviceReference ?: media.originalReference ?: media.viewerReference
    val reading by produceState(FileReading(), media.id, reference, media.sourceRevision) {
        if (reference != null) value = withContext(Dispatchers.IO) { readFile(context, reference) } ?: FileReading()
    }
    return reading
}

private fun readFile(context: Context, reference: String): FileReading? = runCatching {
    val stream = if (reference.startsWith("content://")) context.contentResolver.openInputStream(reference.toUri()) else File(reference).inputStream()
    stream?.use {
        val exif = ExifInterface(it)
        FileReading(CameraExif.from(exif), exif.latLong?.let { coordinates -> coordinates[0] to coordinates[1] })
    }
}.getOrNull()

/** Whether "Added to iCloud" tells the user something the title doesn't: only when it came a day or more after the capture. */
internal fun addedLongAfterCapture(capturedAtEpochMillis: Long, addedAtEpochMillis: Long): Boolean =
    abs(addedAtEpochMillis - capturedAtEpochMillis) >= Duration.ofDays(1).toMillis()

/**
 * Where the photo was taken, as coordinates, with a button that hands them to the user's maps app. Alba draws no map
 * and looks up no place names, so no photo data goes to a third party unless the user opens it in Maps.
 */
@Composable
private fun PhotoLocation(details: MediaDetails, modifier: Modifier) {
    val context = LocalContext.current
    val latitude = details.latitude ?: return
    val longitude = details.longitude ?: return
    val noMaps = stringResource(R.string.no_maps_app)
    val number = NumberFormat.getNumberInstance(currentLocale()).apply { maximumFractionDigits = 0 }
    Column(modifier.testTag("photo-location"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.photo_location), style = MaterialTheme.typography.labelMedium)
        Text(coordinatesLabel(latitude, longitude), style = MaterialTheme.typography.bodyMedium)
        details.altitude?.let { Text(stringResource(R.string.photo_altitude, number.format(it)), style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, geoUri(latitude, longitude).toUri())) }
            catch (_: ActivityNotFoundException) { Toast.makeText(context, noMaps, Toast.LENGTH_SHORT).show() }
        }, modifier = Modifier.padding(top = 4.dp).testTag("open-in-maps")) {
            Icon(AlbaIcons.Place, null, Modifier.size(AlbaIconSize.inline))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.open_in_maps))
        }
    }
}

/** Coordinates to six decimals (about 10 cm), always with a dot so maps apps and people read them alike. */
internal fun coordinatesLabel(latitude: Double, longitude: Double): String =
    String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)

/** A `geo:` URI that drops a pin at the coordinates, without a label: nothing but the place leaves Alba. */
internal fun geoUri(latitude: Double, longitude: Double): String {
    val point = String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)
    return "geo:$point?q=$point"
}

/**
 * The capture date and time as they were where the photo was taken, when iCloud recorded that time zone, and the
 * zone itself: ["Jul 20, 2026, 21:48", "GMT+02:00"]. Without a recorded zone, the phone's own zone is used.
 */
internal fun captureTimeParts(epochMillis: Long, offsetSeconds: Int?, locale: Locale, is24Hour: Boolean,
    deviceZone: ZoneId = ZoneId.systemDefault()): Pair<String, String> {
    val zone: ZoneId = offsetSeconds?.let { ZoneOffset.ofTotalSeconds(it) } ?: deviceZone
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val date = time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val clock = time.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale))
    val offset = time.offset
    return "$date, $clock" to if (offset == ZoneOffset.UTC) "GMT" else "GMT${offset.id}"
}

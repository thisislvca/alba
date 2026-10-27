package me.lvca.alba.app.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.currentLocale
import me.lvca.alba.app.ui.designsystem.AlbaFact
import me.lvca.alba.app.ui.gallery.mediaDuration
import me.lvca.alba.app.ui.timestampLabel
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaDetails
import me.lvca.alba.engine.model.MediaKind
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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

/** When the photo was taken, in its own time zone, when it was added to iCloud, how long a video runs, and where. */
@Composable
internal fun PhotoDates(media: GalleryMedia) {
    val context = LocalContext.current
    val labelColor = LocalContentColor.current
    val factModifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    val (time, zone) = captureTimeParts(media.capturedAtEpochMillis, media.details.timeZoneOffsetSeconds, currentLocale(),
        android.text.format.DateFormat.is24HourFormat(context))
    AlbaFact(stringResource(R.string.photo_taken), stringResource(R.string.time_with_zone, time, zone),
        factModifier.testTag("photo-taken"), labelColor = labelColor)
    media.addedAtEpochMillis?.let { added ->
        AlbaFact(stringResource(R.string.photo_added), timestampLabel(added), factModifier, labelColor = labelColor)
    }
    if (media.kind == MediaKind.VIDEO) mediaDuration(media.durationMillis)?.let {
        AlbaFact(stringResource(R.string.photo_duration), it, factModifier, labelColor = labelColor)
    }
    if (media.details.hasLocation) PhotoLocation(media.details, factModifier)
}

@Composable
internal fun PhotoExtraMetadata(media: GalleryMedia) {
    val context = LocalContext.current
    val labelColor = LocalContentColor.current
    val factModifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    PhotoDates(media)
    val reference = media.linkedDeviceReference ?: media.originalReference ?: media.viewerReference
    val entries by produceState<List<Pair<Int, String>>>(emptyList(), media.id, reference, media.sourceRevision) {
        value = withContext(Dispatchers.IO) {
            buildList {
                media.deviceFolderName?.let { add(R.string.photo_folder to it) }
                if (reference == null) return@buildList
                runCatching {
                    val stream = if (reference.startsWith("content://")) context.contentResolver.openInputStream(reference.toUri()) else File(reference).inputStream()
                    stream?.use {
                        val exif = ExifInterface(it)
                        listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
                            .distinct().joinToString(" ").takeIf(String::isNotBlank)?.let { add(R.string.photo_camera to it) }
                        exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.takeIf(String::isNotBlank)?.let { add(R.string.photo_lens to it) }
                        val exposure = listOfNotNull(exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "$it s" }, exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" })
                        if (exposure.isNotEmpty()) add(R.string.photo_exposure to exposure.joinToString(" · "))
                        // iCloud's own location is shown above; the file's GPS tags are only a fallback.
                        if (!media.details.hasLocation) exif.latLong?.let { coordinates ->
                            add(R.string.photo_location to coordinatesLabel(coordinates[0], coordinates[1]))
                        }
                    }
                }
            }
        }
    }
    entries.forEach { (label, value) ->
        AlbaFact(stringResource(label), value, factModifier, labelColor = labelColor)
    }
}

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
            Icon(Icons.Outlined.Place, null, Modifier.size(18.dp))
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

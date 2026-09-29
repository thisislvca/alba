package dev.mela.app.ui

import androidx.exifinterface.media.ExifInterface
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mela.app.R
import dev.mela.engine.model.GalleryMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PhotoExtraMetadata(media: GalleryMedia) {
    val context = LocalContext.current
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
                        val exposure = listOfNotNull(exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "$it s" }, exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" })
                        if (exposure.isNotEmpty()) add(R.string.photo_exposure to exposure.joinToString(" · "))
                        exif.latLong?.let { coordinates -> add(R.string.photo_location to "${coordinates[0]}, ${coordinates[1]}") }
                    }
                }
            }
        }
    }
    entries.forEach { (label, value) ->
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

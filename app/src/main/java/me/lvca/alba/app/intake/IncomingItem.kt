package me.lvca.alba.app.intake

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import me.lvca.alba.app.platform.displayName
import me.lvca.alba.engine.model.GalleryCollection
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.model.SharedAlbumGeneration
import me.lvca.alba.engine.model.supportsLegacySharedUpload
import me.lvca.alba.engine.source.UploadMediaFormat
import java.io.FileNotFoundException

internal data class IncomingItem(val uri: Uri, val name: String, val mime: String?, val failure: Int? = null)
internal suspend fun inspectIncoming(context: Context, value: String): IncomingItem = withContext(Dispatchers.IO) {
    val uri = value.toUri()
    try {
    val mime = context.contentResolver.getType(uri)
    val name = context.contentResolver.displayName(uri)?.take(255) ?: context.getString(R.string.photos)
    if (mime == null) context.contentResolver.openInputStream(uri)?.use { /* distinguish lost access from an unsupported type */ }
    IncomingItem(uri, name, mime)
    } catch (e: CancellationException) { throw e }
    catch (_: SecurityException) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_access_lost) }
    catch (_: FileNotFoundException) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_file_missing) }
    catch (_: Exception) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_unsupported) }
}

/** Where adding incoming items to a shared album stands. */
internal enum class IncomingSharedResult { SENDING, ADDED, FAILED }

/**
 * The incoming items [album] accepts, judged by the album type rather than by what the library takes: legacy albums take
 * single JPEG, PNG, HEIC, MP4 or MOV files; new albums take what a shared upload can check, including still WebP.
 */
internal fun incomingForSharedAlbum(items: List<IncomingItem>, album: GalleryCollection): List<IncomingItem> = items.filter { item ->
    val mime = item.mime?.let { if (it == "image/jpg") "image/jpeg" else it } ?: return@filter false
    when {
        item.failure != null -> false
        album.shared?.generation == SharedAlbumGeneration.LEGACY ->
            supportsLegacySharedUpload(if (mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.PHOTO, mime)
        else -> UploadMediaFormat.isCandidate(mime)
    }
}

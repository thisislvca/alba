package me.lvca.alba.app.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.lvca.alba.engine.model.StoredMessage

/** One new gallery file: the MediaStore collection and columns it gets, and how to write its bytes. */
internal class MediaStoreCopy(
    val collection: Uri,
    val values: ContentValues,
    val write: suspend (OutputStream) -> Unit,
)

/**
 * Publishes [copies] as new gallery files. Each one is inserted as a pending row and written in order; only then are
 * they all made visible. If anything fails, cancellation included, every row inserted so far is deleted, so other apps
 * never see a partial copy. Returns the published URIs in the order of [copies].
 */
internal suspend fun publishToMediaStore(resolver: ContentResolver, copies: List<MediaStoreCopy>): List<Uri> {
    val pending = mutableListOf<Uri>()
    try {
        for (copy in copies) {
            val values = ContentValues(copy.values).apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
            val uri = checkNotNull(resolver.insert(copy.collection, values)) { StoredMessage.ANDROID_COULD_NOT_CREATE_A_GALLERY_COPY.code }
            pending += uri
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> copy.write(output) }
        }
        currentCoroutineContext().ensureActive()
        pending.forEach { uri ->
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                StoredMessage.ANDROID_COULD_NOT_PUBLISH_THE_GALLERY_COPY.code
            }
        }
        return pending
    } catch (error: Throwable) {
        withContext(NonCancellable) { pending.forEach { uri -> runCatching { resolver.delete(uri, null, null) } } }
        throw error
    }
}

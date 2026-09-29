package dev.mela.app

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Observe only while the gallery is started; bulk imports coalesce into one local scan. */
internal fun deviceLibraryChanges(resolver: ContentResolver) = callbackFlow {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
    }
    resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    awaitClose { resolver.unregisterContentObserver(observer) }
}.conflate()

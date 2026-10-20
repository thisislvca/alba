package me.lvca.alba.app.testing

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.OutputStream

internal fun grantTestMediaPermissions(context: Context) {
    val permissions = if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    permissions.forEach { automation.grantRuntimePermission(context.packageName, it) }
}

/** Phone photos and videos that tests add to MediaStore, and queries for the copies Alba publishes there. */
internal object MediaStoreFixtures {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    /**
     * Adds a phone photo or video, as a camera app does: a pending row that becomes visible once [write] has written
     * it. A row whose write fails is removed. Tests delete the returned row when they finish.
     */
    fun insert(name: String, mimeType: String, relativePath: String, write: (OutputStream) -> Unit): Uri {
        val collection = if (mimeType.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(uri)!!.use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return uri
    }

    /** Adds a solid red 80 × 60 JPEG phone photo. */
    fun insertJpeg(name: String, relativePath: String = "Pictures/AlbaTest"): Uri = insert(name, "image/jpeg", relativePath) { output ->
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) } finally { bitmap.recycle() }
    }

    /** Adds the file [asset] from [assets] as a phone photo or video. */
    fun insertAsset(assets: AssetManager, asset: String, name: String, mimeType: String, relativePath: String): Uri =
        insert(name, mimeType, relativePath) { output -> assets.open(asset).use { it.copyTo(output) } }

    /** The rows of [collection] that match [selection]. */
    fun query(collection: Uri, selection: String, vararg arguments: String): Set<Uri> =
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, arguments, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        } ?: emptySet()
}

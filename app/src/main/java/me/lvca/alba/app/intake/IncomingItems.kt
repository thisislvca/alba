package me.lvca.alba.app.intake

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lvca.alba.app.R
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

internal data class IncomingItem(val uri: Uri, val name: String, val mime: String?, val failure: Int? = null)
internal suspend fun inspectIncoming(context: Context, value: String): IncomingItem = withContext(Dispatchers.IO) {
    val uri = value.toUri()
    try {
    val mime = context.contentResolver.getType(uri)
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.take(255) else null
    } ?: context.getString(R.string.photos)
    if (mime == null) context.contentResolver.openInputStream(uri)?.use { /* distinguish lost access from an unsupported type */ }
    IncomingItem(uri, name, mime)
    } catch (e: CancellationException) { throw e }
    catch (_: SecurityException) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_access_lost) }
    catch (_: FileNotFoundException) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_file_missing) }
    catch (_: Exception) { IncomingItem(uri, context.getString(R.string.photos), null, R.string.incoming_unsupported) }
}

package me.lvca.alba.app.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** The file name the providing app reports for [uri], or null when it reports none. */
internal fun ContentResolver.displayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

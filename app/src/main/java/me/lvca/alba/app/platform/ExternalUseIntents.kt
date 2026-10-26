package me.lvca.alba.app.platform

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import me.lvca.alba.app.ui.ExternalUse

/**
 * The intent that hands a prepared copy at [uri] to another app for [use], or null for printing, which Alba does
 * itself. The grant travels as clip data too, so it survives the system chooser.
 */
internal fun externalUseIntent(use: ExternalUse, uri: Uri, mimeType: String): Intent? {
    val action = when (use) {
        ExternalUse.USE_AS -> Intent.ACTION_ATTACH_DATA
        ExternalUse.OPEN_WITH -> Intent.ACTION_VIEW
        ExternalUse.EDIT_IN -> Intent.ACTION_EDIT
        ExternalUse.PRINT -> return null
    }
    return Intent(action).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // An editor may save over the copy; it is Alba's own temporary file, never the original.
        if (use == ExternalUse.EDIT_IN) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // Wallpaper and contact pickers read the type from this extra.
        if (use == ExternalUse.USE_AS) putExtra("mimeType", mimeType)
    }
}

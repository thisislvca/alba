package me.lvca.alba.app.demo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import me.lvca.alba.protocol.account.ICloudAccountManager
import me.lvca.alba.protocol.account.ICloudAccountState
import me.lvca.alba.protocol.fixture.FixtureICloudCatalogSource

/** Copies phone media, or the bundled sample, into the demo library. It never reaches Apple. */
class DemoMediaImporter(
    context: Context,
    private val accountManager: ICloudAccountManager,
    private val demoCatalog: FixtureICloudCatalogSource,
) {
    private val appContext = context.applicationContext

    /** Imports [uri], or the bundled sample photo when it is null. */
    suspend fun import(uri: Uri?) {
        check(accountManager.state.value == ICloudAccountState.Demo)
        val resolver = appContext.contentResolver
        val name = if (uri == null) SAMPLE_NAME else resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: SELECTED_NAME
        val input = if (uri == null) appContext.assets.open(SAMPLE_ASSET) else requireNotNull(resolver.openInputStream(uri))
        demoCatalog.importMedia(name, input)
    }

    private companion object {
        const val SAMPLE_NAME = "Sample upload.webp"
        const val SELECTED_NAME = "Selected media"
        const val SAMPLE_ASSET = "demo/01.webp"
    }
}

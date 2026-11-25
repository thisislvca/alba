package me.lvca.alba.app.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.engine.model.OfflinePreviewLimit

/** How much room the grid previews Alba keeps for offline browsing may take. Stored by name; keep the names. */
class OfflinePreviewPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())

    val limit: StateFlow<OfflinePreviewLimit> = current.asStateFlow()

    fun setLimit(limit: OfflinePreviewLimit) {
        preferences.edit { putString(LIMIT, limit.name) }
        current.value = limit
    }

    private fun read(): OfflinePreviewLimit = preferences.getString(LIMIT, null)
        ?.let { saved -> OfflinePreviewLimit.entries.firstOrNull { it.name == saved } } ?: OfflinePreviewLimit.DEFAULT

    private companion object {
        const val FILE = "alba-offline-previews"
        const val LIMIT = "limit"
    }
}

package me.lvca.alba.app.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.engine.model.CacheLimit
import me.lvca.alba.engine.model.ThumbnailBudget

/**
 * The room Alba's two stores of media may take on the phone: the thumbnail budget for offline photos, and the limit of
 * the cache for full-size images. Also whether the background fill stopped for low storage, for the settings to say
 * so. Stored by name; keep the names.
 */
class StorageLimitPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val budget = MutableStateFlow(read(BUDGET, ThumbnailBudget.entries, ThumbnailBudget.DEFAULT))
    private val cache = MutableStateFlow(read(CACHE, CacheLimit.entries, CacheLimit.DEFAULT))
    private val pausedForStorage = MutableStateFlow(preferences.getBoolean(STORAGE_LOW, false))

    val thumbnailBudget: StateFlow<ThumbnailBudget> = budget.asStateFlow()
    val cacheLimit: StateFlow<CacheLimit> = cache.asStateFlow()

    /** The background fill stopped short of the low-storage reserve and waits for space. */
    val storageLow: StateFlow<Boolean> = pausedForStorage.asStateFlow()

    fun setThumbnailBudget(value: ThumbnailBudget) {
        preferences.edit { putString(BUDGET, value.name) }
        budget.value = value
    }

    fun setCacheLimit(value: CacheLimit) {
        preferences.edit { putString(CACHE, value.name) }
        cache.value = value
    }

    fun setStorageLow(value: Boolean) {
        if (pausedForStorage.value == value) return
        preferences.edit { putBoolean(STORAGE_LOW, value) }
        pausedForStorage.value = value
    }

    private fun <T : Enum<T>> read(key: String, entries: List<T>, default: T): T =
        preferences.getString(key, null)?.let { saved -> entries.firstOrNull { it.name == saved } } ?: default

    private companion object {
        const val FILE = "alba-storage-limits"
        const val BUDGET = "thumbnailBudget"
        const val CACHE = "cacheLimit"
        const val STORAGE_LOW = "storageLow"
    }
}

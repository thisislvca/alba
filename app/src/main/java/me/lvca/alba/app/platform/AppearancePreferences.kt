package me.lvca.alba.app.platform

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How Alba looks, independent of accounts. Off by default: Alba wears iCloud blue unless the user asks otherwise. */
internal class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val wallpaper = MutableStateFlow(preferences.getBoolean(WALLPAPER_COLORS, false))

    /** Whether Alba takes its colours from the wallpaper on Android 12 and later ("Match wallpaper colors"). */
    val wallpaperColors: StateFlow<Boolean> = wallpaper.asStateFlow()

    fun setWallpaperColors(enabled: Boolean) {
        preferences.edit { putBoolean(WALLPAPER_COLORS, enabled) }
        wallpaper.value = enabled
    }

    /** Stored on devices; keep the names. */
    companion object {
        const val FILE = "alba-appearance"
        const val WALLPAPER_COLORS = "wallpaper-colors"
    }
}

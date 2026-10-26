package me.lvca.alba.app.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.lvca.alba.engine.model.BackupSettings

/** How manual uploads and downloads treat mobile data. Stored by name; keep the names. */
enum class MobileDataChoice {
    /** Ask before each run on mobile data. */
    ASK,

    /** Use mobile data without asking. */
    USE,

    /** Wait for Wi-Fi without asking. */
    WAIT,
}

/**
 * Backup settings and the small facts about prompts and notifications that must outlive the process. Everything that
 * spends mobile data is off until the user turns it on.
 */
class BackupPreferences(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())

    val settings: StateFlow<BackupSettings> = current.asStateFlow()

    fun update(change: (BackupSettings) -> BackupSettings) {
        val next = change(current.value)
        preferences.edit {
            putBoolean(PHOTOS_ON_MOBILE, next.photosOnMobileData)
            putBoolean(VIDEOS_ON_MOBILE, next.videosOnMobileData)
            putBoolean(WHILE_ROAMING, next.whileRoaming)
            putStringSet(DEVICE_FOLDERS, next.deviceFolders)
            putBoolean(PAUSED, next.paused)
        }
        current.value = next
    }

    /** What the user chose for manual transfers on mobile data. */
    var mobileDataChoice: MobileDataChoice
        get() = preferences.getString(MOBILE_DATA_CHOICE, null)
            ?.let { saved -> MobileDataChoice.entries.firstOrNull { it.name == saved } } ?: MobileDataChoice.ASK
        set(value) = preferences.edit { putString(MOBILE_DATA_CHOICE, value.name) }

    /** Whether the current manual run waits for Wi-Fi: the last answer, remembered or not. */
    var manualTransfersWaitForWifi: Boolean
        get() = preferences.getBoolean(WAIT_FOR_WIFI, false)
        set(value) = preferences.edit { putBoolean(WAIT_FOR_WIFI, value) }

    /** Alba asked for notifications once, in context; it does not ask again on its own. */
    var notificationsAsked: Boolean
        get() = preferences.getBoolean(NOTIFICATIONS_ASKED, false)
        set(value) = preferences.edit { putBoolean(NOTIFICATIONS_ASKED, value) }

    /** The newest failure already reported in a notification, so the same failures are not reported twice. */
    var failuresNotifiedThrough: Long
        get() = preferences.getLong(FAILURES_NOTIFIED_THROUGH, 0L)
        set(value) = preferences.edit { putLong(FAILURES_NOTIFIED_THROUGH, value) }

    /** The pause already reported in a notification, so a long pause is reported once. */
    var pauseNotified: String?
        get() = preferences.getString(PAUSE_NOTIFIED, null)
        set(value) = preferences.edit { putString(PAUSE_NOTIFIED, value) }

    private fun read() = BackupSettings(
        photosOnMobileData = preferences.getBoolean(PHOTOS_ON_MOBILE, false),
        videosOnMobileData = preferences.getBoolean(VIDEOS_ON_MOBILE, false),
        whileRoaming = preferences.getBoolean(WHILE_ROAMING, false),
        deviceFolders = preferences.getStringSet(DEVICE_FOLDERS, emptySet()).orEmpty().toSet(),
        paused = preferences.getBoolean(PAUSED, false),
    )

    private companion object {
        const val FILE = "alba-backup"
        const val PHOTOS_ON_MOBILE = "photos-on-mobile-data"
        const val VIDEOS_ON_MOBILE = "videos-on-mobile-data"
        const val WHILE_ROAMING = "while-roaming"
        const val DEVICE_FOLDERS = "device-folders"
        const val PAUSED = "paused"
        const val MOBILE_DATA_CHOICE = "manual-mobile-data"
        const val WAIT_FOR_WIFI = "manual-wait-for-wifi"
        const val NOTIFICATIONS_ASKED = "notifications-asked"
        const val FAILURES_NOTIFIED_THROUGH = "failures-notified-through"
        const val PAUSE_NOTIFIED = "pause-notified"
    }
}

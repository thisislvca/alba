package me.lvca.alba.app.sharing

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The [SharedActivityLedger], kept on the phone so the app and the background check agree on what is new. It holds
 * album and invitation IDs only: no names, photos or comments.
 */
class SharedActivityStore(private val preferences: SharedPreferences, private val schedule: (Boolean) -> Unit = {}) {
    /** [schedule] starts or stops the background check when notifications are turned on or off. */
    constructor(context: Context, schedule: (Boolean) -> Unit) :
        this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE), schedule)

    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<SharedActivityLedger> = mutableState.asStateFlow()

    /** Counts [clear]s, so a check that started before a sign-out cannot write into the next account's ledger. */
    @Volatile var generation = 0
        private set

    /** Applies [change] unless the ledger was cleared since [expected]; returns null when it was. */
    @Synchronized
    fun updateIf(expected: Int, change: (SharedActivityLedger) -> SharedActivityLedger): SharedActivityLedger? =
        if (generation != expected) null else update(change)

    @Synchronized
    fun update(change: (SharedActivityLedger) -> SharedActivityLedger): SharedActivityLedger {
        val next = change(mutableState.value)
        if (next != mutableState.value) {
            preferences.edit { putString(KEY, encode(next)) }
            mutableState.value = next
        }
        return next
    }

    fun setNotifications(enabled: Boolean) {
        update { it.copy(notificationsEnabled = enabled) }
        schedule(enabled)
    }

    /** Keeps the background check in line with the saved switch, for example after an app update. */
    fun applySchedule() = schedule(state.value.notificationsEnabled)

    /** The same saved state without starting background work, for tests. */
    fun withoutScheduling() = SharedActivityStore(preferences)

    /** Forgets everything and stops the background check, for example when the account signs out. */
    @Synchronized
    fun clear() {
        generation++
        update { SharedActivityLedger(notificationsEnabled = it.notificationsEnabled, notificationsAsked = it.notificationsAsked) }
        schedule(false)
    }

    private fun load(): SharedActivityLedger =
        preferences.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() } ?: SharedActivityLedger()

    private companion object {
        const val FILE = "shared-album-activity"
        const val KEY = "ledger"

        fun encode(ledger: SharedActivityLedger): String = JSONObject()
            .put("seen", marks(ledger.seen))
            .put("announced", marks(ledger.announced))
            .put("seenInvitations", JSONArray(ledger.seenInvitations.toList()))
            .put("announcedInvitations", JSONArray(ledger.announcedInvitations.toList()))
            .put("pendingInvitations", JSONArray(ledger.pendingInvitations.toList()))
            .put("notifications", ledger.notificationsEnabled)
            .put("notificationsAsked", ledger.notificationsAsked)
            .toString()

        fun decode(value: String): SharedActivityLedger {
            val root = JSONObject(value)
            return SharedActivityLedger(marks(root.getJSONObject("seen")), marks(root.getJSONObject("announced")),
                strings(root.getJSONArray("seenInvitations")), strings(root.getJSONArray("announcedInvitations")),
                strings(root.getJSONArray("pendingInvitations")), root.optBoolean("notifications", true), root.optBoolean("notificationsAsked", false))
        }

        fun marks(values: Map<String, SharedAlbumMark>) = JSONObject().apply {
            values.forEach { (id, mark) -> put(id, JSONArray().put(mark.count).put(mark.latestAddedAt)) }
        }

        fun marks(json: JSONObject): Map<String, SharedAlbumMark> = json.keys().asSequence().associateWith { id ->
            json.getJSONArray(id).let { SharedAlbumMark(it.getInt(0), it.getLong(1)) }
        }

        fun strings(json: JSONArray): Set<String> = (0 until json.length()).map(json::getString).toSet()
    }
}

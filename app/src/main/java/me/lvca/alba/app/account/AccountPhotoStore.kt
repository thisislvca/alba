package me.lvca.alba.app.account

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The account's photo and name as last saved, and when. */
internal class SavedAccountProfile(val photo: ByteArray?, val displayName: String?, val savedAtMillis: Long)

/**
 * The signed-in account's photo and display name, kept in app-private storage that is not backed up, so its avatar shows
 * from the first frame of a cold start. It holds one account at a time, keyed by a hash of its dsid: another account
 * never reads it, and saving for one replaces any other's. Nothing here is logged.
 */
open class AccountPhotoStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val photoFile get() = File(directory, PHOTO)
    private val infoFile get() = File(directory, INFO)

    /** What is saved for [dsid], or null when nothing is, or what is saved belongs to another account. */
    @Synchronized
    internal open fun load(dsid: String): SavedAccountProfile? {
        val info = savedInfo() ?: return null
        if (info.optString(KEY_ACCOUNT) != key(dsid)) return null
        val photo = runCatching { photoFile.takeIf { it.isFile && it.length() in 1..MAX_PHOTO_BYTES }?.readBytes() }.getOrNull()
        return SavedAccountProfile(photo, info.optString(KEY_NAME).ifEmpty { null }, info.optLong(KEY_SAVED_AT))
    }

    private fun savedInfo(): JSONObject? = runCatching { JSONObject(infoFile.readText()) }.getOrNull()

    /**
     * Saves [photo] and [displayName] for [dsid], replacing what any account saved before. A null [photo] removes the
     * saved one; a photo too large to keep is not saved.
     */
    @Synchronized
    internal fun save(dsid: String, photo: ByteArray?, displayName: String?): SavedAccountProfile {
        directory.mkdirs()
        val kept = photo?.takeIf { it.size in 1..MAX_PHOTO_BYTES }
        if (kept == null) photoFile.delete() else writeAtomically(photoFile, kept)
        val savedAt = now()
        val info = JSONObject().put(KEY_ACCOUNT, key(dsid)).put(KEY_SAVED_AT, savedAt)
        if (displayName != null) info.put(KEY_NAME, displayName)
        writeAtomically(infoFile, info.toString().toByteArray())
        return SavedAccountProfile(kept, displayName, savedAt)
    }

    /** Whether [saved] is missing or more than a day old, so the profile is worth reading again. */
    internal fun isStale(saved: SavedAccountProfile?): Boolean =
        saved == null || now() - saved.savedAtMillis !in 0 until REFRESH_INTERVAL_MILLIS

    /** Deletes what is saved unless it belongs to [dsid]; with no [dsid], deletes it whatever it belongs to. */
    @Synchronized
    fun keepOnly(dsid: String?) {
        if (dsid == null || savedInfo()?.optString(KEY_ACCOUNT) != key(dsid)) clear()
    }

    /** Deletes the saved photo and name, for example when the account signs out. */
    @Synchronized
    fun clear() {
        directory.deleteRecursively()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    private fun key(dsid: String): String =
        MessageDigest.getInstance("SHA-256").digest(dsid.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PHOTO = "photo"
        const val INFO = "account.json"
        const val KEY_ACCOUNT = "account"
        const val KEY_NAME = "name"
        const val KEY_SAVED_AT = "savedAt"
        const val MAX_PHOTO_BYTES = 2L * 1024 * 1024
        const val REFRESH_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}

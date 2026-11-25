package me.lvca.alba.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.gallery.PreviewController
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.platform.OfflinePreviewPreferences
import me.lvca.alba.app.transfers.TransferController
import me.lvca.alba.engine.cache.StorageWriteGuard
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.LocalMediaStorage
import me.lvca.alba.engine.model.OfflinePreviewLimit

/** Alba's own storage on this phone. */
data class PhoneStorageState(
    val usage: LocalMediaStorage? = null,
    val checking: Boolean = false,
    val error: UiMessage? = null,
    /** How much room saved previews may take. */
    val previewLimit: OfflinePreviewLimit = OfflinePreviewLimit.DEFAULT,
    /** iCloud items in the library, for what keeping every preview would take; null while unknown. */
    val libraryItemCount: Int? = null,
) {
    /** Roughly what previews for the whole library take: items times the measured average preview; null if unknown. */
    val wholeLibraryPreviewBytes: Long?
        get() = libraryItemCount?.takeIf { it > 0 }?.let { count ->
            count * (usage?.averagePreviewBytes ?: TYPICAL_PREVIEW_BYTES)
        }

    companion object {
        /** iCloud's grid preview, a 360 by 480 JPEG, before any preview is on the phone to measure. */
        const val TYPICAL_PREVIEW_BYTES = 63_000L
    }
}

/** Measures and clears Alba's private copies on this phone. Never touches the phone's own photos. */
internal class PhoneStorageController(
    private val scope: CoroutineScope,
    private val repository: GalleryRepository,
    private val transfers: TransferController,
    private val previews: PreviewController,
    private val runner: MediaActionRunner,
    private val messages: UserMessages,
    private val offlinePreviews: OfflinePreviewPreferences,
    /** iCloud items in the library now. */
    private val libraryItemCount: () -> Int,
) {
    private val mutableState = MutableStateFlow(PhoneStorageState(previewLimit = offlinePreviews.limit.value))
    val state: StateFlow<PhoneStorageState> = mutableState.asStateFlow()
    private var checkJob: Job? = null

    fun checkPhoneStorage() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            mutableState.update { it.copy(checking = true, error = null) }
            try {
                val usage = repository.localStorageUsage()
                if (previews.storageBlocked && usage.availableBytes > StorageWriteGuard.RESERVE_BYTES) {
                    previews.storageBlocked = false
                    previews.retry()
                }
                mutableState.update { it.copy(usage = usage, libraryItemCount = libraryItemCount()) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(error = UiMessage(R.string.phone_storage_failed)) } }
            finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    /** Keeps the choice and trims saved previews to it right away; a larger limit keeps what is already saved. */
    fun setOfflinePreviewLimit(limit: OfflinePreviewLimit) {
        offlinePreviews.setLimit(limit)
        mutableState.update { it.copy(previewLimit = limit) }
        scope.launch {
            try {
                repository.trimPreviews()
                val usage = repository.localStorageUsage()
                mutableState.update { it.copy(usage = usage, libraryItemCount = libraryItemCount()) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(error = UiMessage(R.string.phone_storage_failed)) } }
        }
    }

    fun clearLocalMedia(category: LocalMediaCategory) {
        runner.start(MediaActionKey(MediaAction.STORAGE_CLEANUP), R.string.phone_storage_failed) {
            checkJob?.cancel(); checkJob?.join()
            previews.cancelAndJoin()
            if (category == LocalMediaCategory.ORIGINALS) transfers.cancelOfflineDownloads()
            repository.clearLocalMedia(category)
            previews.storageBlocked = false
            mutableState.update { it.copy(usage = repository.localStorageUsage(), error = null) }
            messages.show(R.string.phone_storage_cleared)
        }
    }
}

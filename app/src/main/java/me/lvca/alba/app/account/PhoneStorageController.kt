package me.lvca.alba.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.lvca.alba.app.R
import me.lvca.alba.app.actions.MediaAction
import me.lvca.alba.app.actions.MediaActionKey
import me.lvca.alba.app.actions.MediaActionRunner
import me.lvca.alba.app.gallery.PreviewController
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.transfers.TransferController
import me.lvca.alba.engine.cache.StorageWriteGuard
import me.lvca.alba.engine.model.CacheLimit
import me.lvca.alba.engine.model.GalleryRepository
import me.lvca.alba.engine.model.LocalMediaCategory
import me.lvca.alba.engine.model.LocalMediaStorage
import me.lvca.alba.engine.model.ThumbnailBudget

/** Alba's own storage on this phone. */
data class PhoneStorageState(
    val usage: LocalMediaStorage? = null,
    val checking: Boolean = false,
    val error: UiMessage? = null,
    /** Room for the thumbnails that keep photos available offline. */
    val thumbnailBudget: ThumbnailBudget = ThumbnailBudget.DEFAULT,
    /** Room for the cache of full-size photos and videos. */
    val cacheLimit: CacheLimit = CacheLimit.DEFAULT,
    /** How many photos are available offline; null before the library is read. */
    val offlinePhotos: OfflinePhotosStatus? = null,
)

/** Measures and clears Alba's private copies on this phone. Never touches the phone's own photos. */
internal class PhoneStorageController(
    private val scope: CoroutineScope,
    private val repository: GalleryRepository,
    private val transfers: TransferController,
    private val previews: PreviewController,
    private val runner: MediaActionRunner,
    private val messages: UserMessages,
    private val offline: OfflinePhotos,
    offlineStatus: Flow<OfflinePhotosStatus>,
) {
    private val mutableState = MutableStateFlow(
        PhoneStorageState(thumbnailBudget = offline.budget.value, cacheLimit = offline.cacheLimit.value),
    )
    val state: StateFlow<PhoneStorageState> = mutableState.asStateFlow()
    private var checkJob: Job? = null

    init {
        offlineStatus.onEach { status -> mutableState.update { it.copy(offlinePhotos = status) } }.launchIn(scope)
        offline.budget.onEach { budget -> mutableState.update { it.copy(thumbnailBudget = budget) } }.launchIn(scope)
        offline.cacheLimit.onEach { limit -> mutableState.update { it.copy(cacheLimit = limit) } }.launchIn(scope)
    }

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
                mutableState.update { it.copy(usage = usage) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(error = UiMessage(R.string.phone_storage_failed)) } }
            finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    /** Keeps the budget, then moves the offline set to it: a smaller one stops the fill and trims once. */
    fun setThumbnailBudget(budget: ThumbnailBudget) = applyChoice { offline.chooseBudget(budget) }

    /** Keeps the limit and trims the cache to it once. */
    fun setCacheLimit(limit: CacheLimit) = applyChoice { offline.chooseCacheLimit(limit) }

    private fun applyChoice(choose: suspend () -> Unit) {
        scope.launch {
            try {
                choose()
                mutableState.update { it.copy(usage = repository.localStorageUsage()) }
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

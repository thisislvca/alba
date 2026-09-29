package dev.mela.engine.companion

import dev.mela.engine.database.BatchItemEntity
import dev.mela.engine.database.LibraryDao
import dev.mela.engine.model.GalleryRepository
import dev.mela.engine.model.PhotoCompanion
import dev.mela.engine.model.UserSelectedPhoto
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import dev.mela.engine.cache.InsufficientStorageException

enum class BatchAction { KEEP_OFFLINE, REMOVE_CACHE, UPLOAD, PICKER_UPLOAD, SAVE_TO_PHONE }

class GalleryBatchActions(private val dao: LibraryDao, private val gallery: GalleryRepository,
    private val photos: PhotoCompanion,
    private val saveToPhone: suspend (String) -> Unit = { error("Saving to phone is unavailable") },
    private val account: () -> String) {
    private val gate = Mutex()
    private val downloads = Semaphore(2)
    private val uploads = Mutex()
    @Volatile private var activeRun: Job? = null
    private val activeBatches = ConcurrentHashMap<String, Job>()
    val activeAccount: String get() = account()
    suspend fun cancelDownload(id: String) {
        // Persist cancellation before stopping the running job, including queued rows.
        if (dao.cancelDownloadBatch(id, account()) > 0) activeBatches[id]?.cancelAndJoin()
    }
    suspend fun cancelOfflineDownloads() {
        dao.pendingBatches().filter { it.account == account() && it.action == BatchAction.KEEP_OFFLINE.name }
            .map { it.batchId }.distinct().forEach { cancelDownload(it) }
    }
    suspend fun cancelActive() {
        val running = activeRun
        // Persist the stop before interrupting work, including a large queued batch.
        // Finishing items cannot overwrite STOPPED because their update is conditional.
        dao.stopPendingBatches()
        running?.cancelAndJoin()
    }
    fun observe() = dao.observeBatches()
    suspend fun enqueue(action: BatchAction, ids: Collection<String>): String {
        val id = UUID.randomUUID().toString()
        val owner = account()
        dao.putBatches(ids.distinct().map { BatchItemEntity(id, it, owner, action.name, "WAITING", null, System.currentTimeMillis()) })
        return id
    }
    suspend fun retry(id: String) {
        dao.putBatches(dao.failedBatch(id).filter { it.account == account() }.map { it.copy(state = "WAITING", message = null) })
    }
    suspend fun hasPending(localOnly: Boolean = false): Boolean = dao.pendingBatches(1, localOnly).isNotEmpty()

    suspend fun runPending(limit: Int = 64, localOnly: Boolean = false): Boolean = gate.withLock {
        require(limit in 1..256)
        val pending = dao.pendingBatches(limit, localOnly)
        coroutineScope {
            activeRun = coroutineContext[Job]
            try {
            pending.groupBy { it.batchId }.map { (id, rows) ->
                val job = launch(start = CoroutineStart.LAZY) {
                    val next = AtomicInteger()
                    // A large selection uses a bounded number of workers, not one coroutine per photo.
                    repeat(minOf(2, rows.size)) { launch {
                        while (true) {
                            val index = next.getAndIncrement()
                            if (index >= rows.size) break
                            downloads.withPermit { runItem(rows[index]) }
                        }
                    } }
                }
                activeBatches[id] = job
                job.invokeOnCompletion { activeBatches.remove(id, job) }
                job.start()
                job
            }.joinAll()
            } finally { activeRun = null }
        }
        hasPending(localOnly)
    }

    private suspend fun runItem(row: BatchItemEntity) {
        if (row.account != account()) {
            dao.finishPendingBatch(row.batchId, row.mediaId, "STOPPED", "Account changed")
            return
        }
        val action = BatchAction.valueOf(row.action)
        val isUpload = action == BatchAction.UPLOAD || action == BatchAction.PICKER_UPLOAD
        if ((isUpload || action == BatchAction.SAVE_TO_PHONE) && row.state == "RUNNING") {
            dao.finishPendingBatch(row.batchId, row.mediaId, "NEEDS_ATTENTION", if (isUpload)
                "Check the upload queue before selecting this item again" else "Check your phone gallery before saving this item again")
            return
        }
        if (dao.startBatchItem(row.batchId, row.mediaId) == 0) return
        try {
            when (action) {
                BatchAction.KEEP_OFFLINE -> gallery.keepOriginalOffline(row.mediaId)
                BatchAction.REMOVE_CACHE -> gallery.removeCachedOriginal(row.mediaId)
                BatchAction.SAVE_TO_PHONE -> saveToPhone(row.mediaId)
                BatchAction.UPLOAD, BatchAction.PICKER_UPLOAD -> uploads.withLock {
                    check(row.account == account()) { "Account changed" }
                    if (action == BatchAction.UPLOAD) photos.uploadDeviceMedia(row.mediaId)
                    else photos.uploadSelected(UserSelectedPhoto(row.mediaId))
                }
            }
            dao.finishPendingBatch(row.batchId, row.mediaId, "DONE", if (isUpload) "Queued" else null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = if (error is dev.mela.engine.source.UnsupportedUploadException) requireNotNull(error.message)
                else if (error is InsufficientStorageException) requireNotNull(error.message)
                else if (isUpload) "Could not stage this file; check file access and the upload queue" else "Could not complete this item"
            dao.finishPendingBatch(row.batchId, row.mediaId, "FAILED", message)
            if (error is InsufficientStorageException) dao.failWaitingBatch(row.batchId, message)
        }
    }
}

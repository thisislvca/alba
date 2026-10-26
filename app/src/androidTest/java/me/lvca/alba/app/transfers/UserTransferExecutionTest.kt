package me.lvca.alba.app.transfers

import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.testing.AlbaAppRule
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.Smoke
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.work.UserTransferScheduler
import me.lvca.alba.app.work.cancelUserTransfers
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.BatchItemState
import me.lvca.alba.engine.model.MediaAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class UserTransferExecutionTest {
    @get:Rule val app = AlbaAppRule()
    private val compose get() = app.compose

    @Smoke @Test fun persistedTransfersCompleteAfterARefreshAndThroughThePlatformRunner() {
        val graph = (compose.activity.application as AlbaApplication).graph
        val id = "fixture:icloud:0001"
        // Refreshing after the app resumes schedules transfers that were persisted earlier.
        val persisted = runBlocking {
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.enqueue(BatchAction.KEEP_OFFLINE, listOf(id))
        }
        compose.runOnIdle { ViewModelProvider(compose.activity)[AlbaViewModel::class.java].sync.refresh() }
        compose.waitUntil(LONG_WAIT) {
            runBlocking { graph.batches.observe().first().any { it.batchId == persisted && it.status == BatchItemState.DONE } }
        }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { graph.galleryRepository.findMedia(id)?.availability })
        // A transfer scheduled directly completes through the platform runner.
        val batch = runBlocking {
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.enqueue(BatchAction.KEEP_OFFLINE, listOf(id))
        }
        compose.runOnIdle { UserTransferScheduler(compose.activity).enqueue() }
        compose.waitUntil(LONG_WAIT) {
            runBlocking { graph.batches.observe().first().any { it.batchId == batch && it.status == BatchItemState.DONE } }
        }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { graph.galleryRepository.findMedia(id)?.availability })
    }

    @Test fun singleItemActionCreatesAPersistentBatchAndCompletesThroughThePlatformRunner() {
        val graph = (compose.activity.application as AlbaApplication).graph
        val id = "fixture:icloud:0001"
        val previous = runBlocking {
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.observe().first().map { it.batchId }.toSet()
        }
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[AlbaViewModel::class.java].transfers.keepOriginalOffline(id)
        }
        compose.waitUntil(LONG_WAIT) {
            runBlocking { graph.batches.observe().first().any {
                it.batchId !in previous && it.mediaId == id && it.action == BatchAction.KEEP_OFFLINE.name && it.status == BatchItemState.DONE
            } }
        }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { graph.galleryRepository.findMedia(id)?.availability })
    }

    @Test fun notificationCancelStopsQueuedItemsBeforeThePlatformRunnerCanResumeThem() {
        val graph = (compose.activity.application as AlbaApplication).graph
        val id = "fixture:icloud:0001"
        val batch = runBlocking {
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.enqueue(BatchAction.KEEP_OFFLINE, listOf(id))
        }
        runBlocking {
            cancelUserTransfers(compose.activity)
            graph.batches.runPending()
        }
        assertEquals(BatchItemState.STOPPED, runBlocking { graph.batches.observe().first().single { it.batchId == batch }.status })
        assertNull(runBlocking { requireNotNull(graph.galleryRepository.findMedia(id)).originalReference })
    }
}

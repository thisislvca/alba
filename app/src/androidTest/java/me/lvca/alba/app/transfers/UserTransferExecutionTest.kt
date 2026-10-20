package me.lvca.alba.app.transfers

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import me.lvca.alba.engine.companion.BatchAction
import me.lvca.alba.engine.model.BatchItemState
import me.lvca.alba.engine.model.MediaAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.MainActivity
import me.lvca.alba.app.testing.FailureCapture
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.OnboardingRule
import me.lvca.alba.app.testing.scrollToGalleryMedia
import me.lvca.alba.app.work.UserTransferScheduler
import me.lvca.alba.app.work.cancelUserTransfers

class UserTransferExecutionTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = FailureCapture { compose }

    @Test fun scheduledTransferCompletesThroughThePlatformRunner() {
        val graph = (compose.activity.application as AlbaApplication).graph
        val id = "fixture:icloud:0001"
        val batch = runBlocking {
            graph.galleryRepository.refresh(false)
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
            graph.galleryRepository.refresh(false)
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
        compose.scrollToGalleryMedia(id)
        val batch = runBlocking {
            graph.galleryRepository.refresh(false)
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

    @Test fun refreshingAfterResumeSchedulesPersistedTransfers() {
        val graph = (compose.activity.application as AlbaApplication).graph
        val id = "fixture:icloud:0001"
        compose.scrollToGalleryMedia(id)
        val batch = runBlocking {
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.enqueue(BatchAction.KEEP_OFFLINE, listOf(id))
        }
        compose.runOnIdle { ViewModelProvider(compose.activity)[AlbaViewModel::class.java].sync.refresh() }
        compose.waitUntil(LONG_WAIT) {
            runBlocking { graph.batches.observe().first().any { it.batchId == batch && it.status == BatchItemState.DONE } }
        }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { graph.galleryRepository.findMedia(id)?.availability })
    }
}

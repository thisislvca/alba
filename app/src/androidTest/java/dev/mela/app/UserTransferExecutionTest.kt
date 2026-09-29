package dev.mela.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.mela.engine.companion.BatchAction
import dev.mela.engine.model.MediaAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class UserTransferExecutionTest {
    @get:Rule(order = 0) val onboarding = OnboardingRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun scheduledTransferCompletesThroughThePlatformRunner() {
        val graph = (compose.activity.application as MelaApplication).graph
        val id = "fixture:icloud:0001"
        val batch = runBlocking {
            graph.galleryRepository.refresh(false)
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.enqueue(BatchAction.KEEP_OFFLINE, listOf(id))
        }
        compose.runOnIdle { UserTransferScheduler(compose.activity).enqueue() }
        compose.waitUntil(30_000) {
            runBlocking { graph.batches.observe().first().any { it.batchId == batch && it.state == "DONE" } }
        }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { graph.galleryRepository.findMedia(id)?.availability })
    }

    @Test fun singleItemActionCreatesAPersistentBatchAndCompletesThroughThePlatformRunner() {
        val graph = (compose.activity.application as MelaApplication).graph
        val id = "fixture:icloud:0001"
        val previous = runBlocking {
            graph.galleryRepository.refresh(false)
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.observe().first().map { it.batchId }.toSet()
        }
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[MelaViewModel::class.java].keepOriginalOffline(id)
        }
        compose.waitUntil(30_000) {
            runBlocking { graph.batches.observe().first().any {
                it.batchId !in previous && it.mediaId == id && it.action == BatchAction.KEEP_OFFLINE.name && it.state == "DONE"
            } }
        }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { graph.galleryRepository.findMedia(id)?.availability })
    }

    @Test fun notificationCancelStopsQueuedItemsBeforeThePlatformRunnerCanResumeThem() {
        val graph = (compose.activity.application as MelaApplication).graph
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
        assertEquals("STOPPED", runBlocking { graph.batches.observe().first().single { it.batchId == batch }.state })
        assertNull(runBlocking { requireNotNull(graph.galleryRepository.findMedia(id)).originalReference })
    }

    @Test fun refreshingAfterResumeSchedulesPersistedTransfers() {
        val graph = (compose.activity.application as MelaApplication).graph
        val id = "fixture:icloud:0001"
        compose.scrollToGalleryMedia(id)
        val batch = runBlocking {
            graph.galleryRepository.removeCachedOriginal(id)
            graph.batches.enqueue(BatchAction.KEEP_OFFLINE, listOf(id))
        }
        compose.runOnIdle { ViewModelProvider(compose.activity)[MelaViewModel::class.java].refresh() }
        compose.waitUntil(30_000) {
            runBlocking { graph.batches.observe().first().any { it.batchId == batch && it.state == "DONE" } }
        }
        assertEquals(MediaAvailability.ORIGINAL_CACHED, runBlocking { graph.galleryRepository.findMedia(id)?.availability })
    }
}

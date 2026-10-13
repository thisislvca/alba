package dev.mela.app

import dev.mela.engine.model.TransferViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class DemoBackupTest {
    @Test fun onlyCurrentAcceptedDisclosureCanStartAndItCannotBeReplayed() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val gate = Channel<Unit>()
            val demo = DemoBackup(scope, step = { gate.receive() })
            val stale = demo.begin()
            val current = demo.begin()
            assertFalse(demo.accept(stale.id))
            assertFalse(demo.finish(current.id))
            assertTrue(demo.accept(current.id))
            assertTrue(demo.finish(current.id))
            assertFalse(demo.finish(current.id))
            assertTrue(demo.backup.value.enabled)
            assertEquals(3, demo.transfers.value.size)
            assertTrue(demo.transfers.value.all { it.mediaId == null && it.verifiedAtEpochMillis == null })
            demo.reset()
            assertFalse(demo.backup.value.enabled)
            assertTrue(demo.transfers.value.isEmpty())
            assertFalse(demo.accept(current.id))
        } finally { scope.cancel() }
    }

    @Test fun progressCompletesOnlySampleRowsAndResetCancelsInFlightWork() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val gate = Channel<Unit>()
            val demo = DemoBackup(scope, step = { gate.receive() })
            val draft = demo.begin()
            demo.accept(draft.id)
            demo.finish(draft.id)
            repeat(9) { gate.send(Unit); yield() }
            assertTrue(demo.transfers.value.all { it.state == TransferViewState.VERIFIED })
            // A simulated completion must never become evidence for deleting a phone original.
            assertTrue(demo.transfers.value.all { it.mediaId == null && it.verifiedAtEpochMillis == null })
            val again = demo.begin()
            demo.accept(again.id)
            demo.finish(again.id)
            gate.send(Unit); yield()
            demo.reset()
            yield()
            assertTrue(demo.transfers.value.isEmpty())
            assertFalse(demo.backup.value.enabled)
            assertTrue(gate.trySend(Unit).isFailure)
        } finally { scope.cancel() }
    }
    @Test fun failureWifiRetryAndCleanupNeverProduceRealVerificationEvidence() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val gate = Channel<Unit>()
            val demo = DemoBackup(scope, step = { gate.receive() })
            val draft = demo.begin(); demo.accept(draft.id); demo.finish(draft.id)
            demo.interrupt(); yield()
            assertEquals(TransferViewState.NEEDS_ATTENTION, demo.transfers.value.first().state)
            demo.cleanUpSamples()
            assertEquals(TransferViewState.NEEDS_ATTENTION, demo.transfers.value.first().state)
            demo.retry(demo.transfers.value.first().id)
            repeat(9) { gate.send(Unit); yield() }
            assertTrue(demo.transfers.value.all { it.state == TransferViewState.VERIFIED })
            demo.pauseForWifi(); yield()
            assertEquals("demo-wifi", demo.transfers.value.first().message)
            demo.retry(demo.transfers.value.first().id)
            repeat(9) { gate.send(Unit); yield() }
            demo.cleanUpSamples()
            assertTrue(demo.transfers.value.all { it.message == "demo-cleaned" })
            assertTrue(demo.transfers.value.all { it.mediaId == null && it.verifiedAtEpochMillis == null })
            demo.reset()
            assertTrue(demo.transfers.value.isEmpty())
        } finally { scope.cancel() }
    }

}

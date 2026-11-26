package me.lvca.alba.engine.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.model.RefreshSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** One library refresh at a time, with joiners that never wait, or spin, on a refresh that is over. */
class SyncPriorityTest {
    private val main = ImmediateDispatcher()

    @After
    fun tearDown() = main.close()

    /**
     * The screen replaces its refresh by cancelling it and starting another, which joins the cancelled one until it
     * ends. Both run on the main thread with an immediate dispatcher, so the cancelled refresh resumes its joiner inside
     * its own completion, on the same thread. The joiner used to find it still running there and loop on it forever
     * without suspending, freezing the main thread: device tests hung at their next call to it.
     */
    @Test(timeout = TEST_TIMEOUT)
    fun aJoinerResumedAtOnceByACancelledRefreshRunsItsOwn() = runBlocking {
        val priority = SyncPriority()
        val started = CompletableDeferred<Unit>()
        val replaced = launch(main) { priority.shared { started.complete(Unit); awaitCancellation() } }
        started.await()
        val joining = CompletableDeferred<Unit>()
        val replacement = async(main) {
            joining.complete(Unit)
            priority.shared { summary(2) }
        }
        joining.await()

        replaced.cancel()
        val shared = replacement.await()
        assertFalse("The cancelled refresh's result is not the joiner's", shared.joined)
        assertEquals(2L, shared.summary.refreshedAtEpochMillis)
    }

    /** A refresh that ended, either way, is never joined afterwards: the next caller runs its own. */
    @Test(timeout = TEST_TIMEOUT)
    fun anEndedRefreshIsNotJoined() = runBlocking {
        val priority = SyncPriority()
        assertFalse(priority.shared { summary(1) }.joined)
        runCatching { priority.shared { error("failed") } }
        val next = priority.shared { summary(3) }
        assertFalse(next.joined)
        assertEquals(3L, next.summary.refreshedAtEpochMillis)
    }

    private fun summary(at: Long) = RefreshSummary(cloudItemCount = 0, deviceItemCount = 0, refreshedAtEpochMillis = at)

    /** Like Dispatchers.Main.immediate: one thread, and no dispatch for a coroutine resumed on it. */
    private class ImmediateDispatcher : CoroutineDispatcher(), AutoCloseable {
        @Volatile private var thread: Thread? = null
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "immediate-main").also { thread = it } }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== thread

        override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)

        override fun close() {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val TEST_TIMEOUT = 10_000L
    }
}

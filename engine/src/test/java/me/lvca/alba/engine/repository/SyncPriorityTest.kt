package me.lvca.alba.engine.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.lvca.alba.engine.model.RefreshSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * One library refresh at a time. A refresh asked for while one runs waits for a single shared follow-up, and no caller
 * waits, or spins, on a refresh that is over.
 */
class SyncPriorityTest {
    private val main = ImmediateDispatcher()

    @After
    fun tearDown() = main.close()

    /**
     * The screen replaces its refresh by cancelling it and starting another, which waits for the cancelled one to end.
     * Both run on the main thread with an immediate dispatcher, so the cancelled refresh resumes its waiter inside its
     * own completion, on the same thread. The waiter used to find it still running there and loop on it forever
     * without suspending, freezing the main thread: device tests hung at their next call to it.
     */
    @Test(timeout = TEST_TIMEOUT)
    fun aWaiterResumedAtOnceByACancelledRefreshRunsItsOwn() = runBlocking {
        val priority = SyncPriority()
        val started = CompletableDeferred<Unit>()
        val replaced = launch(main) { priority.shared(false) { started.complete(Unit); awaitCancellation() } }
        started.await()
        val waiting = CompletableDeferred<Unit>()
        val replacement = async(main) {
            waiting.complete(Unit)
            priority.shared(false) { summary(2) }
        }
        waiting.await()

        replaced.cancel()
        val shared = replacement.await()
        assertFalse("The cancelled refresh's result is not the waiter's", shared.joined)
        assertEquals(2L, shared.summary.refreshedAtEpochMillis)
    }

    /** A refresh that ended, either way, is never shared afterwards: the next caller runs its own. */
    @Test(timeout = TEST_TIMEOUT)
    fun anEndedRefreshIsNotShared() = runBlocking {
        val priority = SyncPriority()
        assertFalse(priority.shared(false) { summary(1) }.joined)
        runCatching { priority.shared(false) { error("failed") } }
        val next = priority.shared(false) { summary(3) }
        assertFalse(next.joined)
        assertEquals(3L, next.summary.refreshedAtEpochMillis)
    }

    /**
     * Requests during a running refresh never get its result, which may predate them: they share one follow-up that
     * starts once it ends, and reads the phone if any of them asked to.
     */
    @Test(timeout = TEST_TIMEOUT)
    fun requestsDuringARefreshShareOneFollowUp() = runBlocking {
        val priority = SyncPriority()
        val runs = AtomicInteger()
        val finish = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = async { priority.shared(false) { started.complete(Unit); finish.await(); summary(runs.incrementAndGet().toLong()) } }
        started.await()
        val devices = mutableListOf<Boolean>()
        val asked = List(5) { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                priority.shared(index == 3) { device -> devices += device; summary(runs.incrementAndGet().toLong()) }
            }
        }
        finish.complete(Unit)

        assertEquals(1L, first.await().summary.refreshedAtEpochMillis)
        val results = asked.awaitAll()
        assertEquals("Every request gets the follow-up", listOf(2L), results.map { it.summary.refreshedAtEpochMillis }.distinct())
        assertEquals("One follow-up for five requests", 2, runs.get())
        assertEquals("It reads the phone, as one request asked", listOf(true), devices)
    }

    /** When the follow-up's runner is cancelled, the requests that shared it go on with one refresh of their own. */
    @Test(timeout = TEST_TIMEOUT)
    fun aCancelledFollowUpLetsItsWaitersProceed() = runBlocking {
        val priority = SyncPriority()
        val finish = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = async { priority.shared(false) { started.complete(Unit); finish.await(); summary(1) } }
        started.await()
        val runner = CompletableDeferred<Job>()
        val runs = AtomicInteger()
        // Whichever request runs the follow-up hangs in it until it is cancelled; the next run finishes.
        val refresh: suspend (Boolean) -> RefreshSummary = {
            if (runs.incrementAndGet() == 1) {
                runner.complete(currentCoroutineContext()[Job]!!)
                awaitCancellation()
            }
            summary(20)
        }
        val asked = List(3) { async(start = CoroutineStart.UNDISPATCHED) { priority.shared(false, refresh) } }
        finish.complete(Unit)
        first.await()
        val stuck = runner.await()

        stuck.cancel()
        val results = asked.filter { it !== stuck }.awaitAll()
        assertTrue(stuck.isCancelled)
        assertEquals("One refresh of their own, shared", listOf(20L), results.map { it.summary.refreshedAtEpochMillis }.distinct())
        assertEquals("The cancelled follow-up and one more", 2, runs.get())
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

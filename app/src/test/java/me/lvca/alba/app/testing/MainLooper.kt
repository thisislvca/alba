package me.lvca.alba.app.testing

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Runs [block] as `runBlocking` would on a device's test thread, which is not the main thread. Robolectric runs tests
 * on the main thread, where `runBlocking` would stop the main looper that view models, `Dispatchers.Main` and
 * `withContext(Dispatchers.Main)` need. So [block] runs on its own thread while this thread keeps the main looper going,
 * advancing Robolectric's clock with real time so main-thread delays fire.
 */
internal fun <T> runBlockingWithMainLooper(timeoutMillis: Long = 30_000L, block: suspend CoroutineScope.() -> T): T {
    val result = CompletableFuture<T>()
    thread(name = "test-body") {
        try { result.complete(runBlocking(block = block)) } catch (error: Throwable) { result.completeExceptionally(error) }
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val looper = shadowOf(Looper.getMainLooper())
    while (!result.isDone) {
        if (System.nanoTime() > deadline) throw TimeoutException("The test body did not finish within $timeoutMillis ms")
        looper.idleFor(Duration.ofMillis(STEP_MILLIS))
        Thread.sleep(STEP_MILLIS)
    }
    return try { result.get() } catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
}

private const val STEP_MILLIS = 5L

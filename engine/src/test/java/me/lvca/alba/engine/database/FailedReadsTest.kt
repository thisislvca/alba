package me.lvca.alba.engine.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A failed observed read is read again rather than ending its collector, and only a failed read is. */
@RunWith(AndroidJUnit4::class)
class FailedReadsTest {
    @Test
    fun aCursorWindowFailureIsReadAgain() = runBlocking {
        var reads = 0
        val catalog = flow {
            reads++
            if (reads == 1) error("Couldn't read row 697, col 0 from CursorWindow.")
            emit(listOf("photo"))
        }
        assertEquals(listOf("photo"), catalog.retryFailedReads("catalog").first())
        assertEquals(2, reads)
    }

    @Test
    fun anyOtherFailureStillReachesTheCollector() = runBlocking {
        val failure = runCatching { flow<Int> { error("a bug") }.retryFailedReads("catalog").first() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException && failure.message == "a bug")
    }
}

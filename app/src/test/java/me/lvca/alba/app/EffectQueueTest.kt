package me.lvca.alba.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectQueueTest {
    @Test fun aRequestWhoseCollectorWasCancelledIsDeliveredAgain() = runBlocking {
        val queue = EffectQueue()
        queue.send(BACKUP)

        // first() cancels its collector right after receiving, like a UI that stops before acting on the request.
        val received = queue.next.first()

        assertEquals(BACKUP, received.effect)
        assertEquals(received, queue.next.first())
    }

    @Test fun aHandledRequestIsNotDeliveredAgain() = runBlocking {
        val queue = EffectQueue()
        queue.send(BACKUP)

        queue.handled(queue.next.first())

        assertNull(withTimeoutOrNull(100) { queue.next.first() })
    }

    @Test fun requestsAreDeliveredOneAtATimeInOrderEvenWhenEqual() = runBlocking {
        val queue = EffectQueue()
        queue.send(BACKUP)
        queue.send(BACKUP)
        queue.send(OTHER)

        val first = queue.next.first()
        queue.handled(first)
        val second = queue.next.first()
        queue.handled(second)
        val third = queue.next.first()

        assertEquals(listOf(BACKUP, BACKUP, OTHER), listOf(first, second, third).map { it.effect })
        assertNotEquals(first, second)
    }

    private companion object {
        val BACKUP = AlbaEffect.RequestBackupPermissions(listOf("android.permission.READ_MEDIA_IMAGES"))
        val OTHER = AlbaEffect.RequestBackupPermissions(listOf("android.permission.READ_MEDIA_VIDEO"))
    }
}

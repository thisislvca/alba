package me.lvca.alba.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The first library read continues as background work that waits for a connection and is never queued twice. */
@RunWith(AndroidJUnit4::class)
class CatalogSyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun schedulingTwiceKeepsOneReadThatWaitsForTheNetworkAndCancelStopsIt() {
        val scheduler = WorkManagerCatalogSyncScheduler(context)
        scheduler.schedule(expedited = true)
        val first = work().single()
        scheduler.schedule(expedited = true)

        val queued = work().single()
        assertEquals(first.id, queued.id)
        assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)

        scheduler.cancel()
        assertEquals(WorkInfo.State.CANCELLED, work().single().state)
    }

    private fun work() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("alba-catalog-sync").get()
}

package me.lvca.alba.app

import android.content.Context
import androidx.work.WorkerParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyWorkerFactoryTest {
    @Test fun legacyNamesMapToTheSameWorkersUnderTheNewPackage() {
        assertEquals(3, LegacyWorkerFactory.LEGACY_WORKERS.size)
        LegacyWorkerFactory.LEGACY_WORKERS.forEach { (legacy, worker) ->
            assertEquals(legacy.replace("dev.mela.", "me.lvca.alba."), worker.name)
            // WorkManager builds workers through this constructor.
            worker.getDeclaredConstructor(Context::class.java, WorkerParameters::class.java)
        }
    }
}

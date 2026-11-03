package me.lvca.alba.app.platform

import me.lvca.alba.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseHoldTest {
    @Test
    fun freeUpSpaceIsOfferedOnlyInDebugBuilds() {
        assertEquals(BuildConfig.DEBUG, FREE_UP_SPACE_ENABLED)
    }
}

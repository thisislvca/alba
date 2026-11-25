package me.lvca.alba.app.platform

import me.lvca.alba.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReleaseHoldTest {
    @Test
    fun freeUpSpaceIsOfferedOnlyInDebugBuilds() {
        assertEquals(BuildConfig.DEBUG, FREE_UP_SPACE_ENABLED)
    }

    @Test
    fun theDemoIsOfferedOnlyInDebugBuilds() {
        assertEquals(BuildConfig.DEBUG, DEMO_MODE_ENABLED)
        val start = {}
        assertNull(demoEntry(start, enabled = false))
        assertSame(start, demoEntry(start, enabled = true))
    }
}

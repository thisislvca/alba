package dev.mela.app.ui

import org.junit.Assert.*
import org.junit.Test

class MediaDurationTest {
    @Test fun formatsMinuteAndHourBoundariesWithoutWrapping() {
        assertEquals("0:00", mediaDuration(0))
        assertEquals("0:04", mediaDuration(4999))
        assertEquals("0:59", mediaDuration(59999))
        assertEquals("1:00", mediaDuration(60000))
        assertEquals("2:14", mediaDuration(134000))
        assertEquals("59:59", mediaDuration(3599999))
        assertEquals("1:00:00", mediaDuration(3600000))
        assertEquals("12:03:04", mediaDuration(43384000))
    }
    @Test fun missingAndInvalidDurationsDoNotPretendToBeZero() {
        assertNull(mediaDuration(null))
        assertNull(mediaDuration(-1))
    }
}

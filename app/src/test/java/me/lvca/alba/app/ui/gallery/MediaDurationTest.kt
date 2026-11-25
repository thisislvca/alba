package me.lvca.alba.app.ui.gallery

import org.junit.Assert.*
import org.junit.Test

class MediaDurationTest {
    @Test fun formatsMinuteAndHourBoundariesWithoutWrapping() {
        assertEquals("0:00", mediaDuration(0))
        assertEquals("0:05", mediaDuration(4999))
        assertEquals("1:00", mediaDuration(59999))
        assertEquals("1:00", mediaDuration(60000))
        assertEquals("2:14", mediaDuration(134000))
        assertEquals("59:59", mediaDuration(3599499))
        assertEquals("1:00:00", mediaDuration(3599500))
        assertEquals("12:03:04", mediaDuration(43384000))
    }

    /** CloudKit's millisecond durations next to what iCloud.com's Videos album shows for the same clips. */
    @Test fun roundsToTheNearestSecondLikeICloudCom() {
        val shown = mapOf(10240L to "0:10", 16213L to "0:16", 43735L to "0:44", 4700L to "0:05", 8458L to "0:08",
            18858L to "0:19", 3700L to "0:04", 26077L to "0:26", 46688L to "0:47")
        shown.forEach { (millis, label) -> assertEquals("$millis ms", label, mediaDuration(millis)) }
        assertEquals("0:00", mediaDuration(499))
        assertEquals("0:01", mediaDuration(500))
    }

    @Test fun missingAndInvalidDurationsDoNotPretendToBeZero() {
        assertNull(mediaDuration(null))
        assertNull(mediaDuration(-1))
    }
}

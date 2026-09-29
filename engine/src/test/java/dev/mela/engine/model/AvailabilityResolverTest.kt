package dev.mela.engine.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilityResolverTest {
    @Test
    fun `cloud item without cached bytes stays cloud only`() {
        assertEquals(
            MediaAvailability.CLOUD_ONLY,
            AvailabilityResolver.resolve(
                origin = MediaOrigin.ICLOUD,
                previewIsReadable = false,
                originalIsReadable = false,
            ),
        )
    }

    @Test
    fun `original takes precedence over preview`() {
        assertEquals(
            MediaAvailability.ORIGINAL_CACHED,
            AvailabilityResolver.resolve(
                origin = MediaOrigin.ICLOUD,
                previewIsReadable = true,
                originalIsReadable = true,
            ),
        )
    }

    @Test
    fun `device item is always represented as a device original`() {
        assertEquals(
            MediaAvailability.DEVICE_ORIGINAL,
            AvailabilityResolver.resolve(
                origin = MediaOrigin.DEVICE,
                previewIsReadable = false,
                originalIsReadable = false,
            ),
        )
    }
}


package dev.mela.app

import dev.mela.protocol.account.ICloudAccountState
import dev.mela.protocol.account.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSessionDecisionTest {
    @Test
    fun `expired saved session pauses background work`() {
        assertEquals(
            BackgroundSessionDecision.PAUSE,
            backgroundSessionDecision(
                ICloudAccountState.SignedIn("person@example.com", SessionStatus.EXPIRED),
                hasSavedSession = true,
            ),
        )
    }

    @Test
    fun `verified or signed out state can run`() {
        assertEquals(
            BackgroundSessionDecision.RUN,
            backgroundSessionDecision(
                ICloudAccountState.SignedIn("person@example.com", SessionStatus.VERIFIED),
                hasSavedSession = true,
            ),
        )
        assertEquals(
            BackgroundSessionDecision.RUN,
            backgroundSessionDecision(ICloudAccountState.Demo, hasSavedSession = false),
        )
    }

    @Test
    fun `offline or restoring saved session retries`() {
        assertEquals(
            BackgroundSessionDecision.RETRY,
            backgroundSessionDecision(
                ICloudAccountState.SignedIn("person@example.com", SessionStatus.OFFLINE),
                hasSavedSession = true,
            ),
        )
        assertEquals(
            BackgroundSessionDecision.RETRY,
            backgroundSessionDecision(ICloudAccountState.Restoring, hasSavedSession = true),
        )
    }

    @Test
    fun `account without iCloud Photos pauses background work`() {
        assertEquals(
            BackgroundSessionDecision.PAUSE,
            backgroundSessionDecision(
                ICloudAccountState.SignedIn("person@example.com", SessionStatus.PHOTOS_NOT_ENABLED),
                hasSavedSession = true,
            ),
        )
    }
}

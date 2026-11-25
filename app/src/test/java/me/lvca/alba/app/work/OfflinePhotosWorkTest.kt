package me.lvca.alba.app.work

import me.lvca.alba.engine.cache.InsufficientStorageException
import me.lvca.alba.engine.repository.FillFailure
import me.lvca.alba.engine.repository.FillOutcome
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.auth.AppleThrottledException
import me.lvca.alba.protocol.network.AppleMediaHttpException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** What the worker does after a run: back off on throttling for at least Apple's wait, and stop quietly when signed out. */
class OfflinePhotosWorkTest {
    @Test fun throttlingBacksOffExponentiallyAndNeverForLessThanApplesWait() {
        // The first retry waits a minute, then two, four...
        assertEquals(60_000L, nextBackoffMillis(0))
        assertEquals(240_000L, nextBackoffMillis(2))
        assertEquals(5 * 60 * 60 * 1_000L, nextBackoffMillis(30))

        // Apple's wait is shorter than the backoff, or not given: WorkManager's backoff covers it.
        assertEquals(FillDecision.Retry, fillDecision(FillOutcome.Throttled(retryAfterMillis = 30_000L), runAttemptCount = 0))
        assertEquals(FillDecision.Retry, fillDecision(FillOutcome.Throttled(retryAfterMillis = null), runAttemptCount = 3))
        // Longer: the fill is queued again after exactly that wait.
        assertEquals(FillDecision.RetryAfter(600_000L), fillDecision(FillOutcome.Throttled(retryAfterMillis = 600_000L), runAttemptCount = 1))
        assertEquals(FillDecision.Retry, fillDecision(FillOutcome.Throttled(retryAfterMillis = 600_000L), runAttemptCount = 4))
    }

    @Test fun eachOutcomeEndsOrRetriesTheWork() {
        assertEquals(FillDecision.Done, fillDecision(FillOutcome.Finished(skipped = 0), 0))
        // Photos skipped this run are tried in a later one, started by the app, not by retrying now.
        assertEquals(FillDecision.Done, fillDecision(FillOutcome.Finished(skipped = 3), 0))
        assertEquals(FillDecision.Done, fillDecision(FillOutcome.SignedOut, 0))
        assertEquals(FillDecision.Done, fillDecision(FillOutcome.Stopped, 0))
        assertEquals(FillDecision.Retry, fillDecision(FillOutcome.StorageLow, 0))
        assertEquals(FillDecision.Retry, fillDecision(FillOutcome.Interrupted, 0))
    }

    @Test fun failuresAreReadAsThrottlingSignInConnectionStorageOrOnePhoto() {
        assertEquals(FillFailure.THROTTLED, offlinePhotosFailure(AppleMediaHttpException.of(429, "120")))
        assertEquals(FillFailure.THROTTLED, offlinePhotosFailure(AppleMediaHttpException.of(503, null)))
        assertEquals(FillFailure.THROTTLED, offlinePhotosFailure(AppleThrottledException("THROTTLED", 5_000L)))
        assertEquals(FillFailure.SIGNED_OUT, offlinePhotosFailure(AppleProtocolException(AppleProtocolError.SESSION_EXPIRED, "expired")))
        assertEquals(FillFailure.STORAGE, offlinePhotosFailure(InsufficientStorageException()))
        assertEquals(FillFailure.CONNECTION, offlinePhotosFailure(IOException("connection reset")))
        assertEquals(FillFailure.ITEM, offlinePhotosFailure(AppleMediaHttpException.of(500, null)))
        assertEquals(FillFailure.ITEM, offlinePhotosFailure(AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, "gone")))
        assertEquals(FillFailure.ITEM, offlinePhotosFailure(IllegalArgumentException("Unknown media item")))
    }

    @Test fun applesRetryAfterIsReadInSeconds() {
        val throttled = AppleMediaHttpException.of(429, " 120 ") as me.lvca.alba.engine.source.ThrottledFailure
        assertEquals(120_000L, throttled.retryAfterMillis)
        assertEquals(null, (AppleMediaHttpException.of(503, "Wed, 21 Oct 2026 07:28:00 GMT") as me.lvca.alba.engine.source.ThrottledFailure).retryAfterMillis)
    }
}

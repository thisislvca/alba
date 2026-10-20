package me.lvca.alba.engine.companion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.engine.cache.UploadStagingStore
import me.lvca.alba.engine.database.AccountWriteSlotEntity
import me.lvca.alba.engine.database.AlbaDatabase
import me.lvca.alba.engine.database.UploadAttemptEntity
import me.lvca.alba.engine.model.AccountBinding
import me.lvca.alba.engine.model.AccountChangeResult
import me.lvca.alba.engine.model.AutomaticBackupPolicy
import me.lvca.alba.engine.model.BackupChange
import me.lvca.alba.engine.model.BackupScope
import me.lvca.alba.engine.model.BackupSetupEffect
import me.lvca.alba.engine.model.CandidatePage
import me.lvca.alba.engine.model.ICloudDestination
import me.lvca.alba.engine.model.ICloudPhotoProtocol
import me.lvca.alba.engine.model.OneShotUploadSource
import me.lvca.alba.engine.model.PreparedICloudUpload
import me.lvca.alba.engine.model.RemoteOriginalObservation
import me.lvca.alba.engine.model.RemotePairCandidate
import me.lvca.alba.engine.model.RemoteRecordRef
import me.lvca.alba.engine.model.SystemConsentCallback
import me.lvca.alba.engine.model.TransferViewState
import me.lvca.alba.engine.model.UploadAcceptance
import me.lvca.alba.engine.model.UploadProcessingStatus
import me.lvca.alba.engine.model.UserSelectedPhoto
import me.lvca.alba.engine.model.WakeReason
import me.lvca.alba.engine.model.WakeupScheduler
import me.lvca.alba.engine.source.DeviceMediaRecord
import me.lvca.alba.engine.source.ExactOriginalEvidence
import me.lvca.alba.engine.source.ExactOriginalSource
import me.lvca.alba.engine.source.LocalDiscoveryCheckpoint
import me.lvca.alba.engine.source.LocalDiscoveryPage
import me.lvca.alba.engine.source.LocalTrashState
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultPhotoCompanionTest {
    private lateinit var context: Context
    private lateinit var database: AlbaDatabase
    private lateinit var staging: UploadStagingStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlbaDatabase::class.java).build()
        staging = UploadStagingStore(context).also { it.sweepOrphans(emptySet(), minimumAgeMillis = 0) }
    }

    @After
    fun tearDown() {
        database.close()
        staging.sweepOrphans(emptySet(), minimumAgeMillis = 0)
    }

    @Test
    fun maintenanceLeavesManualUploadsForTheTransferRunner() = runBlocking {
        var scheduled = 0
        val scheduler = object : WakeupScheduler {
            override suspend fun scheduleSoon() { scheduled++ }
            override suspend fun setAutomaticBackupEnabled(enabled: Boolean) = Unit
        }
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(FakeOriginalSource(context), protocol, scheduler = scheduler)
        photos.uploadSelected(UserSelectedPhoto("content://picker/manual", "manual.jpg"))
        listOf(WakeReason.PROCESS_STARTED, WakeReason.FOREGROUND, WakeReason.BACKGROUND).forEach { photos.wake(it) }
        assertEquals(0, scheduled)
        assertEquals(0, protocol.uploadStarts.get())
        assertTrue(photos.hasPendingUserUploads())
        photos.runUserUploads()
        assertEquals(1, protocol.uploadStarts.get())
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertFalse(photos.hasPendingUserUploads())
    }

    @Test
    fun maintenanceCannotResumeAnInterruptedManualWrite() = runBlocking {
        val protocol = FakeProtocol(UploadMode.THROW_BEFORE_BODY, SOURCE_BYTES).apply { safeResume = true }
        val photos = companion(FakeOriginalSource(context), protocol)
        photos.uploadSelected(UserSelectedPhoto("content://picker/interrupted", "interrupted.jpg"))
        photos.wake(WakeReason.USER_TRANSFER)
        assertEquals(1, protocol.uploadStarts.get())
        protocol.mode = UploadMode.ACCEPT
        val restarted = companion(FakeOriginalSource(context), protocol)
        restarted.wake(WakeReason.BACKGROUND)
        assertEquals(1, protocol.uploadStarts.get())
        assertTrue(restarted.hasPendingUserUploads())
        restarted.runUserUploads()
        assertEquals(2, protocol.uploadStarts.get())
        assertEquals(TransferViewState.VERIFIED, restarted.observeTransfers().first().single().state)
    }

    @Test
    fun transferRunnerDrainsEveryQueuedManualOriginal() = runBlocking {
        val source = FakeOriginalSource(context).apply {
            selectedBytes["content://picker/two"] = SOURCE_BYTES + 6.toByte()
        }
        val protocol = FakeProtocol(UploadMode.ACCEPT, null).apply { verifyUploadedBytes = true }
        val photos = companion(source, protocol)
        photos.uploadSelected(UserSelectedPhoto("content://picker/one", "one.jpg"))
        photos.uploadSelected(UserSelectedPhoto("content://picker/two", "two.jpg"))
        withTimeout(5_000) { photos.runUserUploads() }
        assertEquals(2, protocol.uploadStarts.get())
        assertEquals(2, photos.observeTransfers().first().count { it.state == TransferViewState.VERIFIED })
        assertFalse(photos.hasPendingUserUploads())
    }

    @Test
    fun cancelQueuedManualUploadSurvivesRestart() = runBlocking {
        val source = FakeOriginalSource(context)
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(source, protocol)
        photos.uploadSelected(UserSelectedPhoto("content://picker/canceled", "canceled.jpg"))
        photos.cancelUserUploads()
        val restarted = companion(source, protocol)
        restarted.wake(WakeReason.BACKGROUND)
        restarted.runUserUploads()
        assertEquals(0, protocol.uploadStarts.get())
        assertEquals(TransferViewState.NEEDS_ATTENTION, restarted.observeTransfers().first().single().state)
        assertFalse(restarted.hasPendingUserUploads())
        assertNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
    }

    @Test
    fun cancelDuringPreparationNeverStartsTheUpload() = runBlocking {
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(FakeOriginalSource(context), protocol)
        photos.uploadSelected(UserSelectedPhoto("content://picker/preparing", "preparing.jpg"))
        protocol.beforePrepare = { runBlocking { photos.cancelUserUploads() } }
        photos.runUserUploads()
        assertEquals(0, protocol.uploadStarts.get())
        assertEquals(1, protocol.uploadCancels.get())
        assertFalse(photos.hasPendingUserUploads())
        assertNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
    }

    @Test
    fun cancelSendingManualUploadPreventsSafeResumeAfterRestart() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val protocol = FakeProtocol(UploadMode.ACCEPT, null).apply {
            safeResume = true
            beforeStart = { started.complete(Unit); awaitCancellation() }
        }
        val source = FakeOriginalSource(context)
        val photos = companion(source, protocol)
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/sending", "sending.jpg"))
        val run = launch { photos.runUserUploads() }
        try {
            withTimeout(5_000) { started.await(); photos.cancelUserUploads() }
        } finally { run.cancelAndJoin() }
        val restarted = companion(source, protocol)
        restarted.wake(WakeReason.BACKGROUND)
        restarted.runUserUploads()
        assertEquals(1, protocol.uploadStarts.get())
        assertFalse(restarted.hasPendingUserUploads())
        assertNotNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
        assertTrue(database.transferDao().findTransfer(ticket.id.value)?.state in setOf(me.lvca.alba.engine.database.TransferState.OUTCOME_UNKNOWN, me.lvca.alba.engine.database.TransferState.RECONCILING, me.lvca.alba.engine.database.TransferState.READ_FAILURE))
    }

    @Test
    fun cancelManualUploadsLeavesAutomaticBackupEnabled() = runBlocking {
        val source = FakeOriginalSource(context, automaticAccess = true)
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(source, protocol)
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id)
        photos.uploadSelected(UserSelectedPhoto("content://picker/cancel-manual", "manual.jpg"))
        photos.cancelUserUploads()
        photos.wake(WakeReason.BACKGROUND)
        assertEquals(1, protocol.uploadStarts.get())
        assertTrue(database.backupDao().findEnabledBackupEnrollment(ACCOUNT_ID)?.enabled == true)
        assertEquals(1, photos.observeTransfers().first().count { it.state == TransferViewState.NEEDS_ATTENTION })
        assertEquals(1, photos.observeTransfers().first().count { it.state == TransferViewState.VERIFIED })
    }

    @Test
    fun acceptedUploadRequiresFreshExactRemoteBytesBeforeVerified() = runBlocking {
        val source = FakeOriginalSource(context)
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(source, protocol)

        photos.uploadSelected(UserSelectedPhoto("content://picker/one", "one.jpg"))
        photos.syncNow()

        val transfer = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.VERIFIED, transfer.state)
        assertNotNull(transfer.verifiedAtEpochMillis)
        assertEquals(1, protocol.uploadStarts.get())
        assertEquals(SOURCE_BYTES.toList(), protocol.uploadedBytes.single().toList())
        val storedTransfer = requireNotNull(database.transferDao().findTransfer(transfer.id.value))
        val stagedSource = requireNotNull(
            database.transferDao().findStagedSource(storedTransfer.stagedSourceId),
        )
        assertNotNull(stagedSource.releasedAtEpochMillis)
        assertEquals(NOW - 1234, stagedSource.lastModifiedAtEpochMillis)
        assertEquals(NOW - 1234, protocol.preparedDate)
    }

    @Test
    fun processingJobSurvivesRestartAndStillNeedsExactOriginalVerification() = runBlocking {
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES).apply {
            processingStatus = UploadProcessingStatus.Processing(95)
        }
        var time = NOW
        val source = FakeOriginalSource(context)
        val photos = companion(source, protocol, clock = { time })
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/processing", "processing.jpg"))
        photos.syncNow()
        val row = requireNotNull(database.transferDao().findTransfer(ticket.id.value))
        assertEquals("job", database.transferDao().findAcceptance(requireNotNull(row.activeAttemptId))?.uploadJobId)
        assertEquals(TransferViewState.CHECKING_ICLOUD, photos.observeTransfers().first().single().state)
        assertEquals(0, protocol.candidateReads)
        assertEquals(1, protocol.uploadStarts.get())

        time = requireNotNull(row.reconciliationDueAtEpochMillis) + 1
        protocol.processingStatus = UploadProcessingStatus.Complete
        val restarted = companion(source, protocol, clock = { time })
        restarted.wake(WakeReason.PROCESS_STARTED)
        assertEquals(TransferViewState.VERIFIED, restarted.observeTransfers().first().single().state)
        assertEquals(1, protocol.candidateReads)
        assertEquals(1, protocol.uploadStarts.get())
    }

    @Test
    fun processingFailureIsTerminalAndNeverReplaysOrAuthorizesCleanup() = runBlocking {
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES).apply {
            processingStatus = UploadProcessingStatus.Failed(500)
        }
        val photos = companion(FakeOriginalSource(context), protocol)
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/failed", "failed.jpg"))
        photos.syncNow()
        photos.wake(WakeReason.PROCESS_STARTED)
        val transfer = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.UNRESOLVED, transfer.state)
        assertNull(transfer.verifiedAtEpochMillis)
        assertEquals(me.lvca.alba.engine.model.StoredMessage.UPLOAD_PROCESSING_FAILED.code, transfer.message)
        assertEquals(0, protocol.candidateReads)
        assertEquals(1, protocol.uploadStarts.get())
        assertNotNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
        photos.continuePastUnresolved(ticket.id)
        assertTrue(runCatching { photos.uploadSelected(UserSelectedPhoto("content://picker/failed-again", "failed.jpg")) }.isFailure)
        assertEquals(1, protocol.uploadStarts.get())
    }

    @Test
    fun statusEndpointFailureFallsBackToAuthoritativeCatalogAndOriginalHash() = runBlocking {
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES).apply { statusReadFails = true }
        val photos = companion(FakeOriginalSource(context), protocol)
        photos.uploadSelected(UserSelectedPhoto("content://picker/status-down", "status-down.jpg"))
        photos.syncNow()
        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
        assertEquals(1, protocol.candidateReads)
        assertEquals(1, protocol.uploadStarts.get())
    }

    @Test
    fun processingCannotHoldWriteSlotForeverAfterDeadline() = runBlocking {
        var time = NOW
        val protocol = FakeProtocol(UploadMode.ACCEPT, null).apply {
            processingStatus = UploadProcessingStatus.Processing(95)
        }
        val photos = companion(FakeOriginalSource(context), protocol, clock = { time })
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/stuck", "stuck.jpg"))
        photos.syncNow()
        time = requireNotNull(database.transferDao().findTransfer(ticket.id.value)?.reconciliationDeadlineEpochMillis) + 1
        photos.syncNow()
        assertEquals(TransferViewState.UNRESOLVED, photos.observeTransfers().first().single().state)
        assertEquals(1, protocol.candidateReads)
        assertEquals(1, protocol.uploadStarts.get())
        photos.continuePastUnresolved(ticket.id)
        assertNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
    }

    @Test
    fun freshStageIsNotMisclassifiedAsCrashOrphan() {
        val fresh = java.io.File(
            context.filesDir,
            "upload_staging/00000000-0000-0000-0000-000000000001.jpeg",
        ).apply { writeBytes(byteArrayOf(1)) }

        assertEquals(0, staging.sweepOrphans(emptySet()))
        assertTrue(fresh.exists())
        assertEquals(1, staging.sweepOrphans(emptySet(), minimumAgeMillis = 0))
        assertFalse(fresh.exists())
    }

    @Test
    fun lostUploadResponseIsNeverReplayed() = runBlocking {
        val protocol = FakeProtocol(UploadMode.THROW_AFTER_BODY, remoteBytes = null)
        val photos = companion(FakeOriginalSource(context), protocol)

        photos.uploadSelected(UserSelectedPhoto("content://picker/lost", "lost.jpg"))
        photos.syncNow()
        photos.syncNow()

        val transfer = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.CHECKING_ICLOUD, transfer.state)
        assertEquals(1, protocol.uploadStarts.get())
        assertNotNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
    }

    @Test
    fun startupTurnsSendingIntoUnknownWithoutCallingNetwork() = runBlocking {
        val protocol = FakeProtocol(UploadMode.ACCEPT, remoteBytes = null)
        val photos = companion(FakeOriginalSource(context), protocol)
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/recovery", "recovery.jpg"))
        val dao = database.transferDao()
        val transfer = requireNotNull(dao.findTransfer(ticket.id.value))
        val lease = "test-lease"
        assertEquals(
            1,
            dao.claimReady(transfer.transferId, transfer.stateVersion, lease, NOW + 1_000, NOW),
        )
        val claimed = requireNotNull(dao.findTransfer(transfer.transferId))
        val attemptId = "attempt-recovery"
        database.withTransaction {
            dao.insertAttempt(
                UploadAttemptEntity(
                    attemptId,
                    claimed.transferId,
                    claimed.stagedSourceId,
                    claimed.accountId,
                    claimed.authEpoch,
                    claimed.operationFence,
                    "before",
                    null,
                    NOW,
                ),
            )
            dao.acquireWriteSlot(AccountWriteSlotEntity(ACCOUNT_ID, attemptId, claimed.authEpoch, NOW))
            check(
                dao.armSending(
                    claimed.transferId,
                    claimed.stateVersion,
                    lease,
                    claimed.accountId,
                    claimed.authEpoch,
                    claimed.operationFence,
                    attemptId,
                    NOW,
                ) == 1,
            )
        }

        photos.wake(WakeReason.PROCESS_STARTED)

        assertEquals(TransferViewState.CHECKING_ICLOUD, photos.observeTransfers().first().single().state)
        assertEquals(0, protocol.uploadStarts.get())
        assertNotNull(dao.findWriteSlot(ACCOUNT_ID))
    }

    @Test
    fun corruptedStageCannotCrossInvocationBoundary() = runBlocking {
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(FakeOriginalSource(context), protocol)
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/corrupt", "corrupt.jpg"))
        val transfer = requireNotNull(database.transferDao().findTransfer(ticket.id.value))
        val source = requireNotNull(database.transferDao().findStagedSource(transfer.stagedSourceId))
        java.io.File(context.filesDir, "upload_staging/${source.ownedFileToken}").writeBytes(byteArrayOf(1, 2, 3))

        runCatching { photos.syncNow() }

        assertEquals(0, protocol.uploadStarts.get())
        assertNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
        assertEquals(TransferViewState.NEEDS_ATTENTION, photos.observeTransfers().first().single().state)
    }

    @Test
    fun selectingTheSameExactBytesTwiceCreatesOnlyOneTransfer() = runBlocking {
        val photos = companion(FakeOriginalSource(context), FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES))

        photos.uploadSelected(UserSelectedPhoto("content://picker/one", "one.jpg"))
        val duplicate = runCatching {
            photos.uploadSelected(UserSelectedPhoto("content://picker/one-again", "one-again.jpg"))
        }

        assertTrue(duplicate.isFailure)
        assertEquals(1, photos.observeTransfers().first().size)
    }

    @Test
    fun exactMatchesAcrossPagesMustBeUniqueBeforeVerification() = runBlocking {
        val pages = listOf(
            CandidatePage(listOf(candidate(MASTER_NAME, ASSET_NAME)), "page-two", moreComing = true),
            CandidatePage(listOf(candidate("master-two", "asset-two")), "done", moreComing = false),
        )
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES, pages)
        val photos = companion(FakeOriginalSource(context), protocol)
        val ticket = photos.uploadSelected(UserSelectedPhoto("content://picker/paged", "paged.jpg"))

        photos.syncNow()
        assertEquals(TransferViewState.CHECKING_ICLOUD, photos.observeTransfers().first().single().state)
        photos.syncNow()

        assertEquals(TransferViewState.UNRESOLVED, photos.observeTransfers().first().single().state)
        assertNotNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
        photos.continuePastUnresolved(ticket.id)
        assertEquals(TransferViewState.SKIPPED, photos.observeTransfers().first().single().state)
        assertNull(database.transferDao().findWriteSlot(ACCOUNT_ID))
        assertEquals(1, protocol.uploadStarts.get())
    }

    @Test
    fun automaticBackupCanBeDisabledAndEnrolledAgain() = runBlocking {
        val source = FakeOriginalSource(context, automaticAccess = true)
        val photos = companion(source, FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES))
        val first = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(first.id)
        photos.finishBackupEnrollment(first.id)
        photos.changeBackup(BackupChange.Disable)
        val second = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(second.id)
        photos.finishBackupEnrollment(second.id)

        photos.syncNow()

        assertEquals(TransferViewState.VERIFIED, photos.observeTransfers().first().single().state)
    }

    @Test
    fun automaticBackupUsesSameVerifiedPipelineAndTrashCallbackIsDurable() = runBlocking {
        val source = FakeOriginalSource(context, automaticAccess = true)
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(source, protocol)
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        val effect = photos.acceptBackupDisclosure(draft.id)
        assertTrue(effect is BackupSetupEffect.RequestMediaPermissions)
        photos.finishBackupEnrollment(draft.id)

        photos.syncNow()

        val transfer = photos.observeTransfers().first().single()
        assertEquals(TransferViewState.VERIFIED, transfer.state)
        assertEquals(LOCAL_MEDIA_ID, transfer.mediaId)
        assertEquals(1, protocol.uploadStarts.get())

        val proposal = photos.prepareTrash(setOf(LOCAL_MEDIA_ID))
        assertTrue(proposal.blockedReason == null)
        val handoff = photos.beginTrash(proposal.id)
        photos.recordTrashResult(handoff.attemptId, SystemConsentCallback.APPROVED)
        assertEquals(AccountChangeResult.Allowed, photos.accountWillChange())
    }

    @Test
    fun lostTrashCallbackBlocksAccountReplacement() = runBlocking {
        val source = FakeOriginalSource(context, automaticAccess = true)
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(source, protocol)
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id)
        photos.syncNow()
        val proposal = photos.prepareTrash(setOf(LOCAL_MEDIA_ID))
        photos.beginTrash(proposal.id)

        photos.wake(WakeReason.PROCESS_STARTED)

        assertTrue(photos.accountWillChange() is AccountChangeResult.Blocked)
    }

    @Test
    fun automaticBackupPausesOnMeteredForegroundAndResumesOnUnmetered() = runBlocking {
        var unmetered = false
        val source = FakeOriginalSource(context, automaticAccess = true)
        val protocol = FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES)
        val photos = companion(source, protocol, AutomaticBackupPolicy { unmetered })
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id)
        photos.wake(WakeReason.FOREGROUND)
        assertEquals(0, protocol.uploadStarts.get())
        assertTrue(photos.observeTransfers().first().isEmpty())
        unmetered = true
        photos.wake(WakeReason.FOREGROUND)
        assertEquals(1, protocol.uploadStarts.get())
    }

    @Test
    fun resumableAutomaticSendingDoesNotResumeOnMeteredNetwork() = runBlocking {
        var unmetered = true
        val source = FakeOriginalSource(context, automaticAccess = true)
        val protocol = FakeProtocol(UploadMode.THROW_BEFORE_BODY, null).apply { safeResume = true }
        val photos = companion(source, protocol, AutomaticBackupPolicy { unmetered })
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id)
        photos.syncNow()
        assertEquals(1, protocol.uploadStarts.get())
        unmetered = false
        photos.wake(WakeReason.PROCESS_STARTED)
        assertEquals(1, protocol.uploadStarts.get())
        val transfer = photos.observeTransfers().first().single()
        assertEquals(me.lvca.alba.engine.database.TransferState.SENDING, database.transferDao().findTransfer(transfer.id.value)?.state)
        unmetered = true
        photos.wake(WakeReason.FOREGROUND)
        assertEquals(2, protocol.uploadStarts.get())
    }

    @Test
    fun automaticReconciliationWaitsForUnmeteredWithoutSchedulingBusyLoop() = runBlocking {
        var unmetered = true
        val source = FakeOriginalSource(context, automaticAccess = true)
        val protocol = FakeProtocol(UploadMode.THROW_AFTER_BODY, null)
        val photos = companion(source, protocol, AutomaticBackupPolicy { unmetered })
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id)
        photos.syncNow()
        val row = requireNotNull(database.transferDao().findTransfer(photos.observeTransfers().first().single().id.value))
        database.transferDao().markReadFailure(row.transferId, requireNotNull(row.activeAttemptId), NOW, NOW, "Check pending")
        val before = protocol.candidateReads
        unmetered = false
        val wake = photos.wake(WakeReason.FOREGROUND)
        assertEquals(before, protocol.candidateReads)
        assertFalse(wake.moreWorkScheduled)
        unmetered = true
        photos.wake(WakeReason.FOREGROUND)
        assertTrue(protocol.candidateReads > before)
    }

    @Test
    fun authorizationLostDuringResumePreservesSafeJournalState() = runBlocking {
        val protocol = FakeProtocol(UploadMode.THROW_BEFORE_BODY, null).apply { safeResume = true }
        val photos = companion(FakeOriginalSource(context), protocol)
        photos.uploadSelected(UserSelectedPhoto("content://picker/auth-race", "auth-race.jpg"))
        photos.syncNow()
        protocol.beforePrepare = {
            protocol.authorized = false
            error("Session became offline")
        }
        photos.wake(WakeReason.USER_TRANSFER)
        val row = requireNotNull(database.transferDao().findTransfer(photos.observeTransfers().first().single().id.value))
        assertEquals(me.lvca.alba.engine.database.TransferState.SENDING, row.state)
        assertEquals(1, protocol.uploadStarts.get())
    }

    @Test
    fun deterministicResumeFailureEndsInReconciliationInsteadOfLooping() = runBlocking {
        val protocol = FakeProtocol(UploadMode.THROW_BEFORE_BODY, null).apply { safeResume = true }
        val photos = companion(FakeOriginalSource(context), protocol)
        photos.uploadSelected(UserSelectedPhoto("content://picker/changed", "changed.jpg"))
        photos.syncNow()
        // The protocol's own checks fail the same way on every attempt, e.g. when the source file changed.
        protocol.beforePrepare = { error("Upload filename changed") }
        photos.wake(WakeReason.USER_TRANSFER)
        val row = requireNotNull(database.transferDao().findTransfer(photos.observeTransfers().first().single().id.value))
        assertTrue(row.state != me.lvca.alba.engine.database.TransferState.SENDING)
        assertEquals(1, protocol.uploadStarts.get())
    }

    @Test
    fun futureReconciliationSchedulesItsDueTime() = runBlocking {
        val times = mutableListOf<Long>()
        val scheduler = object : WakeupScheduler {
            override suspend fun scheduleSoon() = Unit
            override suspend fun scheduleAt(notBeforeEpochMillis: Long) { times += notBeforeEpochMillis }
            override suspend fun setAutomaticBackupEnabled(enabled: Boolean) = Unit
        }
        val photos = companion(FakeOriginalSource(context), FakeProtocol(UploadMode.THROW_AFTER_BODY, null), scheduler = scheduler)
        photos.uploadSelected(UserSelectedPhoto("content://picker/future", "future.jpg"))
        photos.syncNow()
        assertTrue(times.any { it > NOW })
    }

    @Test
    fun lostTrashCallbackRequiresRebootAndReadableStateBeforeAccountReplacement() = runBlocking {
        val source = FakeOriginalSource(context, automaticAccess = true)
        val photos = companion(source, FakeProtocol(UploadMode.ACCEPT, SOURCE_BYTES))
        val draft = photos.beginBackupEnrollment(BackupScope.CAMERA_JPEGS)
        photos.acceptBackupDisclosure(draft.id)
        photos.finishBackupEnrollment(draft.id)
        photos.syncNow()
        photos.beginTrash(photos.prepareTrash(setOf(LOCAL_MEDIA_ID)).id)
        source.trashState = LocalTrashState(false, null)
        photos.wake(WakeReason.PROCESS_STARTED)
        assertTrue(photos.accountWillChange() is AccountChangeResult.Blocked)
        source.boot = "2"
        source.trashState = null
        photos.wake(WakeReason.PROCESS_STARTED)
        assertTrue(photos.accountWillChange() is AccountChangeResult.Blocked)
        source.trashState = LocalTrashState(false, null)
        photos.wake(WakeReason.FOREGROUND)
        assertEquals(AccountChangeResult.Allowed, photos.accountWillChange())
    }

    private fun companion(
        source: FakeOriginalSource,
        protocol: FakeProtocol,
        policy: AutomaticBackupPolicy = AutomaticBackupPolicy { true },
        scheduler: WakeupScheduler = NoOpScheduler,
        clock: () -> Long = { NOW },
    ) = DefaultPhotoCompanion(
        database = database,
        originals = source,
        staging = staging,
        protocol = protocol,
        scheduler = scheduler,
        automaticBackupPolicy = policy,
        now = clock,
    )

    private class FakeOriginalSource(
        private val context: Context,
        private val automaticAccess: Boolean = false,
    ) : ExactOriginalSource {
        private var discovered = false
        val selectedBytes = mutableMapOf<String, ByteArray>()
        var boot = "1"
        var trashState: LocalTrashState? = LocalTrashState(true, NOW + 86_400_000)
        override fun bootSessionId(): String = boot
        override suspend fun discoverEligibleJpegs(
            checkpoint: LocalDiscoveryCheckpoint?, limit: Int, scope: BackupScope, addedAfterEpochMillis: Long,
            enrollmentBaseline: LocalDiscoveryCheckpoint?,
        ): LocalDiscoveryPage = discoverJpegs(checkpoint, limit)

        override suspend fun copySelected(
            contentUri: String,
            displayNameHint: String?,
            output: OutputStream,
        ): ExactOriginalEvidence = evidence(contentUri, displayNameHint ?: "selected.jpg", output)

        override suspend fun copyMediaStoreItem(
            contentUri: String,
            expectedRevision: String,
            output: OutputStream,
        ): ExactOriginalEvidence = evidence(contentUri, "camera.jpg", output)

        override suspend fun hashMediaStoreItem(
            contentUri: String,
            expectedRevision: String,
        ): ExactOriginalEvidence = evidence(contentUri, "camera.jpg", null)

        override fun permissionFingerprint(): String = if (automaticAccess) "full:unredacted:test" else "none:test"

        override fun hasAutomaticBackupAccess(): Boolean = automaticAccess

        override suspend fun discoverJpegs(
            checkpoint: LocalDiscoveryCheckpoint?,
            limit: Int,
        ): LocalDiscoveryPage {
            val item = DeviceMediaRecord(
                id = LOCAL_MEDIA_ID,
                fileName = "camera.jpg",
                capturedAtEpochMillis = NOW,
                width = 10,
                height = 10,
                contentUri = LOCAL_URI,
                sourceRevision = LOCAL_REVISION,
                volumeName = "external",
                volumeVersion = "v1",
                mediaStoreId = 7,
                generationModified = 3,
                mimeType = "image/jpeg",
                byteCount = SOURCE_BYTES.size.toLong(),
            )
            val items = if (discovered) emptyList() else listOf(item)
            discovered = true
            return LocalDiscoveryPage(
                items,
                LocalDiscoveryCheckpoint("external", "v1", 3, 7),
                false,
            )
        }

        override fun createTrashRequest(contentUris: List<String>): IntentSender = PendingIntent.getActivity(
            context,
            44,
            Intent("me.lvca.alba.TEST_TRASH").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        ).intentSender

        override suspend fun readTrashState(contentUri: String): LocalTrashState? = trashState

        private fun evidence(
            contentUri: String,
            name: String,
            output: OutputStream?,
        ): ExactOriginalEvidence {
            val bytes = selectedBytes[contentUri] ?: SOURCE_BYTES
            output?.write(bytes)
            return ExactOriginalEvidence(
                contentUri = contentUri,
                displayName = name,
                mimeType = "image/jpeg",
                lineageKey = if (contentUri == LOCAL_URI) "external:7" else contentUri,
                sourceRevision = if (contentUri == LOCAL_URI) LOCAL_REVISION else "selected-rev",
                acquisitionMethod = "TEST",
                originalFormatRequested = true,
                unredacted = true,
                formatValidated = true,
                byteCount = bytes.size.toLong(),
                sha256Hex = sha256(bytes),
                lastModifiedAtEpochMillis = NOW - 1234,
            )
        }
    }

    private class FakeProtocol(
        var mode: UploadMode,
        private var remoteBytes: ByteArray?,
        private val candidatePages: List<CandidatePage>? = null,
    ) : ICloudPhotoProtocol {
        var safeResume = false
        var authorized = true
        var beforePrepare: () -> Unit = {}
        var beforeStart: suspend () -> Unit = {}
        var verifyUploadedBytes = false
        var candidateReads = 0
        var processingStatus: UploadProcessingStatus = UploadProcessingStatus.Unknown
        var statusReadFails = false
        var preparedDate: Long? = null
        override fun isWriteAuthorized(): Boolean = authorized
        override suspend fun canResume(binding: AccountBinding, attemptId: String): Boolean = safeResume
        val uploadStarts = AtomicInteger(0)
        val uploadCancels = AtomicInteger(0)
        val uploadedBytes = mutableListOf<ByteArray>()
        private val candidatePageIndex = AtomicInteger(0)

        override fun currentDestination() = ICloudDestination(ACCOUNT_ID, AUTH_SESSION_ID, "test@example.com")

        override suspend fun captureChangeToken(binding: AccountBinding): String = "before"

        override suspend fun prepareOriginal(
            binding: AccountBinding,
            fileName: String,
            source: OneShotUploadSource,
            lastModifiedAtEpochMillis: Long?,
        ): PreparedICloudUpload {
            beforePrepare()
            preparedDate = lastModifiedAtEpochMillis
            return object : PreparedICloudUpload {
            private val started = AtomicBoolean(false)

            override suspend fun startOnce(attemptId: String): UploadAcceptance {
                check(started.compareAndSet(false, true))
                uploadStarts.incrementAndGet()
                beforeStart()
                if (mode == UploadMode.THROW_BEFORE_BODY) throw IOException("reservation interrupted before body")
                uploadedBytes += source.openOnce().use { it.readBytes() }
                if (verifyUploadedBytes) remoteBytes = uploadedBytes.last()
                if (mode == UploadMode.THROW_AFTER_BODY) throw IOException("response lost")
                return UploadAcceptance("request", MASTER_NAME, ASSET_NAME, false, "job")
            }

            override fun cancel() { uploadCancels.incrementAndGet() }
            }
        }

        override suspend fun uploadProcessingStatus(binding: AccountBinding, acceptance: UploadAcceptance): UploadProcessingStatus {
            assertEquals("job", acceptance.uploadJobId)
            if (statusReadFails) throw IOException("Status endpoint unavailable")
            return processingStatus
        }

        override suspend fun findUploadCandidates(
            binding: AccountBinding,
            afterChangeToken: String,
            acceptance: UploadAcceptance?,
            limit: Int,
        ): CandidatePage {
            candidateReads += 1
            return candidatePages?.let { pages ->
            pages[candidatePageIndex.getAndIncrement().coerceAtMost(pages.lastIndex)]
        } ?: CandidatePage(
            candidates = remoteBytes?.let { listOf(candidate(MASTER_NAME, ASSET_NAME)) }.orEmpty(),
            nextChangeToken = "after",
            moreComing = false,
        )
        }

        override suspend fun streamFreshOriginal(
            binding: AccountBinding,
            pair: RemotePairCandidate,
            output: OutputStream,
        ): RemoteOriginalObservation {
            val bytes = requireNotNull(remoteBytes)
            output.write(bytes)
            return RemoteOriginalObservation(RESOURCE_ID, bytes.size.toLong())
        }
    }

    private enum class UploadMode { ACCEPT, THROW_AFTER_BODY, THROW_BEFORE_BODY }

    private object NoOpScheduler : WakeupScheduler {
        override suspend fun scheduleSoon() = Unit
        override suspend fun setAutomaticBackupEnabled(enabled: Boolean) = Unit
    }

    private companion object {
        const val NOW = 1_000_000L
        const val ACCOUNT_ID = "account"
        const val AUTH_SESSION_ID = "auth-session"
        const val MASTER_NAME = "master"
        const val ASSET_NAME = "asset"
        const val RESOURCE_ID = "resource"
        const val LOCAL_MEDIA_ID = "device:external:7"
        const val LOCAL_URI = "content://media/external/images/media/7"
        const val LOCAL_REVISION = "v1:3:8"
        val SOURCE_BYTES = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 4, 5,
        )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

        fun candidate(master: String, asset: String) = RemotePairCandidate(
            RemoteRecordRef(master, "master-tag"),
            RemoteRecordRef(asset, "asset-tag"),
            relationIsCurrent = true,
            masterDeleted = false,
            assetDeleted = false,
            originalResourceIdentity = RESOURCE_ID,
        )
    }
}

package me.lvca.alba.app.transfers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lvca.alba.app.EffectQueue
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UserMessages
import me.lvca.alba.app.platform.BackupPreferences
import me.lvca.alba.engine.model.BackupControls
import me.lvca.alba.engine.model.FreeUpSpaceCandidates
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.engine.model.ReclaimProposal
import me.lvca.alba.engine.model.ReclaimProposalId
import me.lvca.alba.engine.model.StoredMessage
import me.lvca.alba.engine.model.TrashAttemptId
import me.lvca.alba.engine.model.TrashHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** "Free up space" runs in chunks: the next chunk, stopping when iCloud is unreachable, and a run left by a restart. */
@RunWith(AndroidJUnit4::class)
class BackupControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val savedState = SavedStateHandle()
    private val messages = UserMessages()
    private val photos = FakePhotos(context)

    @Test
    fun anApprovedChunkContinuesWithTheNextOne() {
        photos.proposals += proposal("p1", eligible = setOf("a"), unchecked = listOf("b"))
        photos.proposals += proposal("p2", eligible = setOf("b"))
        val backup = controller()

        backup.freeUpSpace(listOf("a", "b"))
        assertEquals(listOf(setOf("a", "b")), photos.checked)
        backup.trashAnswered("attempt-p1", approved = true)

        assertEquals(listOf(setOf("a", "b"), setOf("b")), photos.checked)
        assertEquals(listOf("p1", "p2"), photos.handedOff)
    }

    @Test
    fun anUnrelatedTrashAnswerNeverMovesTheRun() {
        photos.proposals += proposal("p1", eligible = setOf("a"), unchecked = listOf("b"))
        val backup = controller()
        backup.freeUpSpace(listOf("a", "b"))

        backup.trashAnswered("single-photo-attempt", approved = true)

        assertEquals("Only the free-up chunk's own answer continues the run", 1, photos.checked.size)
    }

    @Test
    fun anUnreachableICloudFinishesTheChunkButStopsTheRun() {
        photos.proposals += proposal("p1", eligible = setOf("a"), unchecked = listOf("c"),
            refusals = mapOf("b" to StoredMessage.COULD_NOT_REACH_ICLOUD_TO_CHECK.code))
        val backup = controller()

        backup.freeUpSpace(listOf("a", "b", "c"))
        assertEquals("The verified item in this chunk still goes to Android", listOf("p1"), photos.handedOff)
        assertEquals(UiMessage.quantity(R.plurals.free_up_space_stopped_unreachable, 2), messages.message.value)

        backup.trashAnswered("attempt-p1", approved = true)
        assertEquals("No chunk starts after iCloud became unreachable", 1, photos.checked.size)
    }

    @Test
    fun aRunLeftByARestartIsOfferedNotResumed() {
        photos.proposals += proposal("p1", eligible = setOf("a"), unchecked = listOf("b"))
        controller().freeUpSpace(listOf("a", "b"))

        // The process died while Android was asking; a new controller restores from the same saved state.
        photos.proposals += proposal("p2", eligible = setOf("b"))
        val restored = controller()
        restored.trashAnswered("attempt-p1", approved = true)
        assertEquals("No surprise consent request after a restart", 1, photos.checked.size)
        assertEquals(1, restored.state.leftover)

        restored.continueFreeUp()
        assertEquals(listOf(setOf("a", "b"), setOf("b")), photos.checked)
    }

    @Test
    fun aRunLeftBeforeConsentIsOfferedWhenRestored() {
        savedState["freeUpRemaining"] = arrayListOf("x", "y")
        assertEquals(2, controller().state.leftover)
        assertTrue(photos.checked.isEmpty())
    }

    private fun controller() = BackupController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        savedState = savedState,
        photos = photos,
        preferences = BackupPreferences(context),
        scheduler = TransferScheduler {},
        messages = messages,
        effects = EffectQueue(),
        isDemo = { false },
        io = Dispatchers.Unconfined,
    )

    private val BackupController.state: FreeUpSpaceState
        get() = runBlocking { extras.first().freeUpSpace }

    private fun proposal(id: String, eligible: Set<String>, unchecked: List<String> = emptyList(), refusals: Map<String, String> = emptyMap()) =
        ReclaimProposal(ReclaimProposalId(id), eligible, Long.MAX_VALUE, refusals = refusals, unchecked = unchecked)

    private class FakePhotos(private val context: Context) : PhotoCompanion by unexpected() {
        val proposals = ArrayDeque<ReclaimProposal>()
        val checked = mutableListOf<Set<String>>()
        val handedOff = mutableListOf<String>()

        override val backup: BackupControls = object : BackupControls by unexpected() {
            override suspend fun freeUpSpaceCandidates() = FreeUpSpaceCandidates(emptyList(), 0)
        }

        override suspend fun prepareTrash(
            items: Set<String>,
            allowPartial: Boolean,
            onProgress: suspend (checked: Int, total: Int) -> Unit,
        ): ReclaimProposal {
            checked += items
            return proposals.removeFirst()
        }

        override suspend fun beginTrash(proposal: ReclaimProposalId): TrashHandoff {
            handedOff += proposal.value
            val sender = PendingIntent.getActivity(context, 1, Intent("test").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            return TrashHandoff(TrashAttemptId("attempt-${proposal.value}"), sender.intentSender, setOf("any"))
        }
    }
}

private inline fun <reified T : Any> unexpected(): T = java.lang.reflect.Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("Unexpected call to ${method.name}") } as T

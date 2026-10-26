package me.lvca.alba.app.gallery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.R
import me.lvca.alba.app.messages.UiMessage
import me.lvca.alba.app.messages.UndoAction
import me.lvca.alba.app.testing.FIRST_DEMO_PHOTO
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.runBlockingWithMainLooper
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Trashing phone and iCloud items together: the iCloud part follows Android's answer, with one combined Undo. */
@RunWith(AndroidJUnit4::class)
class MixedTrashTest {
    private val phoneUri = "content://media/external/images/media/7"

    @Test fun approvedPhoneTrashMovesTheICloudItemsWithOneUndoForBoth() = withViewModel { app, vm ->
        withContext(Dispatchers.Main) {
            vm.mixedTrashRequested(listOf(FIRST_DEMO_PHOTO))
            vm.phoneTrashRequested(listOf(phoneUri), restore = false)
            vm.phoneTrashAnswered(approved = true)
        }
        val message = withTimeout(SHORT_WAIT) { vm.message.filterIsInstance<UiMessage.Undoable>().first() }
        assertEquals(UndoAction.MixedTrash(UndoAction.CloudTrash(listOf(FIRST_DEMO_PHOTO)), UndoAction.PhoneTrash(listOf(phoneUri))),
            message.undo)
        assertEquals(app.resources.getQuantityString(R.plurals.mixed_trash_moved, 2, 2), message.resolve(app))
    }

    @Test fun declinedPhoneTrashStillMovesTheConfirmedICloudItems() = withViewModel { _, vm ->
        withContext(Dispatchers.Main) {
            vm.mixedTrashRequested(listOf(FIRST_DEMO_PHOTO))
            vm.phoneTrashRequested(listOf(phoneUri), restore = false)
            vm.phoneTrashAnswered(approved = false)
        }
        val message = withTimeout(SHORT_WAIT) { vm.message.filterIsInstance<UiMessage.Undoable>().first() }
        assertEquals(UndoAction.CloudTrash(listOf(FIRST_DEMO_PHOTO)), message.undo)
    }

    @Test fun aFailedPhoneRequestForgetsTheICloudPart() = withViewModel { _, vm ->
        withContext(Dispatchers.Main) {
            vm.mixedTrashRequested(listOf(FIRST_DEMO_PHOTO))
            vm.phoneTrashFailed()
            // A later, unrelated phone trash must not carry the abandoned iCloud items with it.
            vm.phoneTrashRequested(listOf(phoneUri), restore = false)
            vm.phoneTrashAnswered(approved = true)
        }
        val message = withTimeout(SHORT_WAIT) { vm.message.filterIsInstance<UiMessage.Undoable>().first() }
        assertEquals(UndoAction.PhoneTrash(listOf(phoneUri)), message.undo)
    }

    private fun withViewModel(block: suspend (AlbaApplication, AlbaViewModel) -> Unit) = runBlockingWithMainLooper {
        val app = ApplicationProvider.getApplicationContext<AlbaApplication>()
        val graph = app.graph
        graph.accountManager.selectDemo(true)
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            AlbaViewModel(testDependencies(graph), SavedStateHandle()).also { store.put("mixed-trash", it) }
        }
        val collector = launch { vm.account.state.collect() }
        try {
            withTimeout(LONG_WAIT) { vm.account.state.first { it.accountState == ICloudAccountState.Demo && !it.isRefreshing } }
            block(app, vm)
        } finally {
            collector.cancelAndJoin()
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}

package me.lvca.alba.app.ui.editor

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.lvca.alba.app.platform.SmallerPhotoCopyRequired
import me.lvca.alba.engine.model.GalleryMedia
import me.lvca.alba.engine.model.MediaAvailability
import me.lvca.alba.engine.model.MediaOrigin
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Saves outlive the editor screen, keep their source until they end, and run one at a time. */
@RunWith(AndroidJUnit4::class)
class PhotoEditSavesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val media = GalleryMedia("fixture:save", "save.jpg", 1, 10, 10, MediaOrigin.ICLOUD, MediaAvailability.ORIGINAL_CACHED, null, null, 0, 0)
    private fun request(source: File, upload: Boolean = false) = EditSaveRequest(media, source, EditStep(), allowSmaller = false, upload = upload)
    private fun PhotoEditSaves.awaitOutcome(id: Long) = runBlocking {
        withTimeout(5_000) { state.first { it[id] != null && it[id] !is EditSave.Running }.getValue(id) }
    }

    @After fun stop() = scope.cancel()

    @Test fun aSaveKeepsItsSourceAfterTheScreenLetsGoAndReportsUntilAcknowledged() {
        val gate = CompletableDeferred<Unit>()
        val saves = PhotoEditSaves(scope) { request, progress ->
            progress(.5f); gate.await()
            assertTrue("The source is still readable", request.source.exists())
            Uri.parse("content://media/1")
        }
        val source = File.createTempFile("save", ".src")
        val id = saves.newId()
        saves.start(id, request(source, upload = true))
        // The screen is recreated: the old one releases its file while the save still reads it.
        saves.release(source)
        assertTrue(source.exists())
        gate.complete(Unit)
        assertEquals(EditSave.Saved(id, Uri.parse("content://media/1"), upload = true), saves.awaitOutcome(id))
        assertFalse("Deleted once the save ended", source.exists())
        assertEquals(EditSave.Saved(id, Uri.parse("content://media/1"), true), saves.state.value[id])
        saves.acknowledge(id)
        assertNull(saves.state.value[id])
    }

    @Test fun savesRunOneAtATime() {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val saves = PhotoEditSaves(scope) { _, _ ->
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            Thread.sleep(50)
            running.decrementAndGet()
            Uri.parse("content://media/2")
        }
        val ids = (1..3).map { saves.newId().also { id -> saves.start(id, request(File.createTempFile("save", ".src"))) } }
        ids.forEach { assertTrue(saves.awaitOutcome(it) is EditSave.Saved) }
        assertEquals(1, most.get())
    }

    @Test fun smallerCopiesAndFailuresAreReportedAndAnIdleSourceIsDeletedAtOnce() {
        val outcomes = mutableListOf<Exception>(SmallerPhotoCopyRequired(40, 30), IllegalStateException())
        val saves = PhotoEditSaves(scope) { _, _ -> throw outcomes.removeAt(0) }
        val first = saves.newId().also { saves.start(it, request(File.createTempFile("save", ".src"), upload = true)) }
        assertEquals(EditSave.NeedsSmallerCopy(first, 40, 30, upload = true), saves.awaitOutcome(first))
        val second = saves.newId().also { saves.start(it, request(File.createTempFile("save", ".src"))) }
        assertEquals(EditSave.Failed(second), saves.awaitOutcome(second))
        val idle = File.createTempFile("save", ".src")
        saves.release(idle)
        assertFalse(idle.exists())
    }
}

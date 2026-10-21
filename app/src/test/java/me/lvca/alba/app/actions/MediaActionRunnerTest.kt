package me.lvca.alba.app.actions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.lvca.alba.app.R
import me.lvca.alba.app.diagnostics.AlbaDiagnostics
import me.lvca.alba.app.messages.UserMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaActionRunnerTest {
    private val messages = UserMessages()
    private val export = MediaActionKey(MediaAction.EXPORT, "photo-1")
    private val save = MediaActionKey(MediaAction.SAVE_TO_GALLERY, "photo-2")

    @Test fun startingAnotherActionKeepsAnEarlierActionsError() = runBlocking<Unit> {
        val runner = MediaActionRunner(this, AlbaDiagnostics.None, messages)
        runner.start(export, R.string.message_could_not_export_this_photo) { error("export failed") }
        runner.idle()
        val failure = messages.message.value
        assertNotNull(failure)

        val release = CompletableDeferred<Unit>()
        runner.start(save, R.string.message_could_not_save_to_the_gallery) { release.await() }
        yield()

        assertEquals(failure, messages.message.value)
        release.complete(Unit)
    }

    @Test fun retryingAnActionClearsItsOwnEarlierError() = runBlocking<Unit> {
        val runner = MediaActionRunner(this, AlbaDiagnostics.None, messages)
        runner.start(export, R.string.message_could_not_export_this_photo) { error("export failed") }
        runner.idle()
        assertNotNull(messages.message.value)

        val release = CompletableDeferred<Unit>()
        runner.start(export, R.string.message_could_not_export_this_photo) { release.await() }
        yield()

        assertNull(messages.message.value)
        release.complete(Unit)
    }

    @Test fun aSuccessMessageShownOffTheMainThreadIsClearedWhenTheSameActionStartsAgain() = runBlocking<Unit> {
        val runner = MediaActionRunner(this, AlbaDiagnostics.None, messages)
        runner.start(save, R.string.message_could_not_save_to_the_gallery) {
            withContext(Dispatchers.IO) { messages.show(R.string.message_saved_to_your_phone_gallery) }
        }
        runner.idle()
        assertNotNull(messages.message.value)

        val release = CompletableDeferred<Unit>()
        runner.start(save, R.string.message_could_not_save_to_the_gallery) { release.await() }
        yield()

        assertNull(messages.message.value)
        release.complete(Unit)
    }

    @Test fun anotherActionsSuccessMessageIsKept() = runBlocking<Unit> {
        val runner = MediaActionRunner(this, AlbaDiagnostics.None, messages)
        runner.start(save, R.string.message_could_not_save_to_the_gallery) { messages.show(R.string.message_saved_to_your_phone_gallery) }
        runner.idle()
        val saved = messages.message.value
        assertNotNull(saved)

        val release = CompletableDeferred<Unit>()
        runner.start(export, R.string.message_could_not_export_this_photo) { release.await() }
        yield()

        assertEquals(saved, messages.message.value)
        release.complete(Unit)
    }

    private suspend fun MediaActionRunner.idle() = running.first { it.isEmpty() }
}

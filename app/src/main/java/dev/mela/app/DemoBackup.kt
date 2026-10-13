package dev.mela.app

import dev.mela.engine.model.BackupDraft
import dev.mela.engine.model.BackupDraftId
import dev.mela.engine.model.BackupScope
import dev.mela.engine.model.BackupView
import dev.mela.engine.model.TransferId
import dev.mela.engine.model.TransferView
import dev.mela.engine.model.TransferViewState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sample-only walkthrough: no account, device photos, network, work scheduler, or durable upload queue. */
internal class DemoBackup(
    private val scope: CoroutineScope,
    private val step: suspend () -> Unit = { delay(700) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val backupState = MutableStateFlow(BackupView(false))
    val backup = backupState.asStateFlow()
    private val transferState = MutableStateFlow<List<TransferView>>(emptyList())
    val transfers = transferState.asStateFlow()
    private var pending: BackupDraftId? = null
    private var accepted = false
    private var job: Job? = null

    fun begin(): BackupDraft {
        val id = BackupDraftId(PREFIX + UUID.randomUUID())
        pending = id
        accepted = false
        return BackupDraft(id, "Demo", "")
    }

    fun accept(id: BackupDraftId): Boolean {
        if (id != pending) return false
        accepted = true
        return true
    }

    fun finish(id: BackupDraftId): Boolean {
        if (id != pending || !accepted) return false
        pending = null
        accepted = false
        job?.cancel()
        backupState.value = BackupView(true, "Demo", BackupScope.CAMERA_JPEGS)
        transferState.value = listOf("Sample coast.jpg", "Sample garden.jpg", "Sample city.jpg").mapIndexed { index, name ->
            TransferView(TransferId("demo-backup:$index"), null, name, TransferViewState.WAITING,
                240_000L + index * 80_000L, now())
        }
        resume()

        return true
    }

    private fun resume() {
        job?.cancel()
        job = scope.launch {
            for (id in transferState.value.map { it.id }) {
                if (transferState.value.first { it.id == id }.state == TransferViewState.VERIFIED) continue
                for (state in listOf(TransferViewState.UPLOADING, TransferViewState.CHECKING_ICLOUD, TransferViewState.VERIFIED)) {
                    step()
                    transferState.value = transferState.value.map {
                        if (it.id == id) it.copy(state = state, message = null, updatedAtEpochMillis = now()) else it
                    }
                }
            }
        }
    }

    fun interrupt() = pause(TransferViewState.NEEDS_ATTENTION, "demo-interrupted")
    fun pauseForWifi() = pause(TransferViewState.WAITING, "demo-wifi")
    private fun pause(state: TransferViewState, reason: String) {
        if (!backupState.value.enabled) return
        job?.cancel()
        // Replayable scenarios let reviewers exercise an interruption even after the first run finishes.
        transferState.value = transferState.value.mapIndexed { index, item ->
            item.copy(state = if (index == 0) state else TransferViewState.WAITING,
                message = if (index == 0) reason else null, updatedAtEpochMillis = now())
        }
    }

    fun retry(id: TransferId) {
        if (!backupState.value.enabled || transferState.value.none { it.id == id && it.message in setOf("demo-interrupted", "demo-wifi") }) return
        resume()
    }

    fun cleanUpSamples() {
        if (transferState.value.isEmpty() || transferState.value.any { it.state != TransferViewState.VERIFIED }) return
        // Only changes imaginary rows. No MediaStore, trash handoff, proof, or local asset ID exists here.
        transferState.value = transferState.value.map { it.copy(state = TransferViewState.SKIPPED, message = "demo-cleaned") }
    }

    fun reset() {
        job?.cancel()
        job = null
        pending = null
        accepted = false
        backupState.value = BackupView(false)
        transferState.value = emptyList()
    }

    companion object {
        private const val PREFIX = "demo-backup:"
        fun owns(id: BackupDraftId) = id.value.startsWith(PREFIX)
    }
}

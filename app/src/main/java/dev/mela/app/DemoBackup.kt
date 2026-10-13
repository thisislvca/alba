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
        job = scope.launch {
            for (index in transferState.value.indices) {
                for (state in listOf(TransferViewState.UPLOADING, TransferViewState.CHECKING_ICLOUD, TransferViewState.VERIFIED)) {
                    step()
                    transferState.value = transferState.value.mapIndexed { itemIndex, item ->
                        if (itemIndex == index) item.copy(state = state, updatedAtEpochMillis = now()) else item
                    }
                }
            }
        }
        return true
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

package me.lvca.alba.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.lvca.alba.engine.model.AccountChangeResult
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.protocol.account.ICloudAccountState
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountPreparationTest {
    @Test fun failedPreparationIsReportedAndDoesNotLeaveSignInBusy() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AlbaApplication>()
        val graph = app.graph
        graph.accountManager.selectDemo(true)
        check(graph.accountManager.session.value == null) { "Requires the demo account" }
        val attempts = MutableStateFlow(0)
        val photos = object : PhotoCompanion by graph.photos {
            override suspend fun accountWillChange(): AccountChangeResult {
                attempts.value++
                throw IOException("Account journal unavailable")
            }
        }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            AlbaViewModel(graph.galleryRepository, graph.accountManager, photos, graph.maintenance,
                savedState = SavedStateHandle()).also { store.put("account-preparation", it) }
        }
        val collector = launch { vm.uiState.collect() }
        try {
            withTimeout(20_000) { vm.uiState.first { it.accountState == ICloudAccountState.Demo && !it.isRefreshing } }
            repeat(2) { attempt ->
                withContext(Dispatchers.Main) { vm.dismissMessage() }
                withTimeout(5_000) { vm.uiState.first { it.message == null && !it.accountBusy } }
                withContext(Dispatchers.Main) { vm.signIn("unused@example.test", "unused") }
                withTimeout(5_000) { attempts.first { it == attempt + 1 } }
                val state = withTimeout(5_000) {
                    vm.uiState.first { !it.accountBusy && it.message != null }
                }
                assertEquals(app.getString(R.string.message_could_not_sign_in_to_icloud), state.message!!.resolve(app))
            }
            assertEquals(2, attempts.value)
            assertNull(graph.accountManager.session.value)
        } finally {
            collector.cancelAndJoin()
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}

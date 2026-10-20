package me.lvca.alba.app.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.lvca.alba.app.AlbaApplication
import me.lvca.alba.app.AlbaViewModel
import me.lvca.alba.app.R
import me.lvca.alba.app.testing.LONG_WAIT
import me.lvca.alba.app.testing.SHORT_WAIT
import me.lvca.alba.app.testing.testDependencies
import me.lvca.alba.engine.model.AccountChangeResult
import me.lvca.alba.engine.model.PhotoCompanion
import me.lvca.alba.protocol.account.ICloudAccountState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AccountPreparationTest {
    private fun session(vm: AlbaViewModel) = combine(vm.account.state, vm.message) { state, message -> state to message }

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
            AlbaViewModel(testDependencies(graph).copy(photos = photos),
                SavedStateHandle()).also { store.put("account-preparation", it) }
        }
        val collector = launch { vm.account.state.collect() }
        try {
            withTimeout(LONG_WAIT) { vm.account.state.first { it.accountState == ICloudAccountState.Demo && !it.isRefreshing } }
            repeat(2) { attempt ->
                withContext(Dispatchers.Main) { vm.dismissMessage() }
                withTimeout(SHORT_WAIT) { session(vm).first { (state, message) -> message == null && !state.accountBusy } }
                withContext(Dispatchers.Main) { vm.account.signIn("unused@example.test", "unused") }
                withTimeout(SHORT_WAIT) { attempts.first { it == attempt + 1 } }
                val state = withTimeout(SHORT_WAIT) {
                    session(vm).first { (state, message) -> !state.accountBusy && message != null }
                }
                assertEquals(app.getString(R.string.message_could_not_sign_in_to_icloud), state.second!!.resolve(app))
            }
            assertEquals(2, attempts.value)
            assertNull(graph.accountManager.session.value)
        } finally {
            collector.cancelAndJoin()
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}

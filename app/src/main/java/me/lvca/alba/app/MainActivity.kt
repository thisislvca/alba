package me.lvca.alba.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Reads what the launching or new intent asks for, keeps it across recreation, and shows [AlbaRoot]. */
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AlbaViewModel> { AlbaViewModel.Factory(this) }
    private var incoming by mutableStateOf(IncomingSession.None)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = IncomingSession.from(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        incoming.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = IncomingSession.restore(savedInstanceState) ?: IncomingSession.from(intent)
        enableEdgeToEdge()
        val onboarding = OnboardingPreferences(this)
        if (BuildConfig.BENCHMARK_MODE) { onboarding.complete(); onboarding.enabled = true }
        setContent {
            AlbaRoot(viewModel, onboarding, incoming, onIncomingChange = { incoming = it })
        }
    }
}

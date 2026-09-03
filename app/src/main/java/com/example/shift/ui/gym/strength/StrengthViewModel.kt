package com.example.shift.ui.gym.strength

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.shift.ShiftApplication
import com.example.shift.data.SettingsManager
import com.example.shift.data.gym.CachedRecommendation
import com.example.shift.data.gym.GymRepository
import com.example.shift.data.gym.GymSessionWithSets
import com.example.shift.data.gym.Recommender
import com.example.shift.data.gym.RunnerEngine
import com.example.shift.data.gym.RunnerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StrengthUiState(
    val loading: Boolean = false,
    val recommendation: CachedRecommendation? = null,
    val error: String? = null,
    val sessionInProgress: Boolean = false,
    val hasAnthropicKey: Boolean = false
)

/**
 * The strength tab: today's plan, a resume card for an unfinished session,
 * and the log of past ones. The plan is computed once per day and cached so
 * opening the tab is not a model call every time.
 */
class StrengthViewModel(
    application: Application,
    private val store: RunnerStore,
    private val repo: GymRepository,
    private val settings: SettingsManager
) : AndroidViewModel(application) {

    private val recommender = Recommender(repo, settings)

    private val _state = MutableStateFlow(StrengthUiState())
    val state: StateFlow<StrengthUiState> = _state.asStateFlow()

    val history: StateFlow<List<GymSessionWithSets>> = repo.allSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var planJob: kotlinx.coroutines.Job? = null

    init { refresh() }

    /** Called whenever the tab comes back into view. */
    fun refresh() {
        viewModelScope.launch {
            val key = settings.anthropicApiKeyFlow.first()?.trim().orEmpty()
            _state.update { it.copy(sessionInProgress = store.hasSession(), hasAnthropicKey = key.isNotBlank()) }
            val today = LocalDate.now().toString()
            val cached = store.loadRecommendation()
            if (cached != null && cached.date == today) {
                _state.update { it.copy(recommendation = cached) }
            } else if (planJob?.isActive != true) {
                loadPlan(today)
            }
        }
    }

    /** Throw the cached plan away and plan again, model and all. */
    fun replan() {
        store.clearRecommendation()
        loadPlan(LocalDate.now().toString())
    }

    private fun loadPlan(today: String) {
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val rec = recommender.recommend(LocalDate.parse(today))
                val cached = CachedRecommendation(
                    date = today,
                    source = rec.source.name,
                    reasons = rec.reasons,
                    detail = rec.detail,
                    plan = rec.plan
                )
                store.saveRecommendation(cached)
                _state.update { it.copy(loading = false, recommendation = cached) }
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update { it.copy(loading = false, error = e.message ?: "Could not plan a session") }
            }
        }
    }

    /** Writes a fresh snapshot for the runner to pick up, then hands over to it. */
    fun startSession(onStarted: () -> Unit) {
        val plan = _state.value.recommendation?.plan ?: return
        store.save(RunnerEngine.start(plan, System.currentTimeMillis()))
        _state.update { it.copy(sessionInProgress = true) }
        onStarted()
    }

    fun discardInProgress() {
        store.clear()
        _state.update { it.copy(sessionInProgress = false) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repo.deleteSession(id)
            // The plan was built on this history; rebuild it.
            store.clearRecommendation()
            loadPlan(LocalDate.now().toString())
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ShiftApplication
                StrengthViewModel(
                    application = app,
                    store = RunnerStore(app),
                    repo = GymRepository(app.database.gymDao()),
                    settings = SettingsManager(app)
                )
            }
        }
    }
}

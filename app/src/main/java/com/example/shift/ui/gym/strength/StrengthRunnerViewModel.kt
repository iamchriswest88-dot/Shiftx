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
import com.example.shift.data.gym.Equipment
import com.example.shift.data.gym.Gear
import com.example.shift.data.gym.GymExercise
import com.example.shift.data.gym.GymRepository
import com.example.shift.data.gym.GymSession
import com.example.shift.data.gym.IntervalsGymPush
import com.example.shift.data.gym.RunnerEngine
import com.example.shift.data.gym.RunnerSnapshot
import com.example.shift.data.gym.RunnerStep
import com.example.shift.data.gym.RunnerStore
import com.example.shift.data.gym.StepKind
import com.example.shift.data.repository.DoneRepository
import com.example.shift.ui.runner.AudioCueManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class RunnerUiState(
    val snapshot: RunnerSnapshot? = null,
    val steps: List<RunnerStep> = emptyList(),
    /** Wall clock at the last tick; the screen derives countdowns from it. */
    val nowMs: Long = 0L,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val saveError: String? = null,
    val intervalsMessage: String? = null,
    val canPushToIntervals: Boolean = false
) {
    val step: RunnerStep? get() = snapshot?.let { RunnerEngine.currentStep(it, steps) }
    val remainingMs: Long? get() = snapshot?.let { RunnerEngine.remainingMs(it, nowMs, steps) }
    val finished: Boolean get() = snapshot?.finished == true
}

/**
 * Drives the session runner. All state lives in a [RunnerSnapshot] that is
 * written to disk on every change; this class adds the clock, the beeps, and
 * the save at the end.
 */
class StrengthRunnerViewModel(
    application: Application,
    private val store: RunnerStore,
    private val repo: GymRepository,
    private val doneRepo: DoneRepository,
    private val settings: SettingsManager
) : AndroidViewModel(application) {

    private val audio = AudioCueManager(application)
    private var library: List<GymExercise> = emptyList()
    private var lastCueSecond: Int = -1

    private val _state = MutableStateFlow(RunnerUiState())
    val state: StateFlow<RunnerUiState> = _state.asStateFlow()

    var muted: Boolean
        get() = audio.muted
        set(v) { audio.muted = v }

    init {
        viewModelScope.launch {
            library = try { repo.activeExercises() } catch (e: Exception) { emptyList() }
            val key = settings.apiKeyFlow.first().orEmpty()
            val athlete = settings.athleteIdFlow.first().orEmpty()
            val snapshot = store.load()
            _state.update {
                it.copy(
                    snapshot = snapshot,
                    steps = snapshot?.let { s -> RunnerEngine.steps(s) } ?: emptyList(),
                    nowMs = System.currentTimeMillis(),
                    loaded = true,
                    canPushToIntervals = key.isNotBlank() && athlete.isNotBlank()
                )
            }
            // Restoring after a refresh or a lock: land on the right step.
            tick()
        }
    }

    /** Called about four times a second while the screen is visible. */
    fun tick() {
        val now = System.currentTimeMillis()
        val s = _state.value.snapshot ?: run { _state.update { it.copy(nowMs = now) }; return }
        val next = RunnerEngine.tick(s, now)
        if (next !== s && next != s) {
            cueTransition(s, next)
            commit(next, now)
        } else {
            _state.update { it.copy(nowMs = now) }
            cueCountdown(now)
        }
    }

    fun done() = transition { s, now -> RunnerEngine.complete(s, now) }
    fun skip() = transition { s, now -> RunnerEngine.skip(s, now) }
    fun back() = transition { s, now -> RunnerEngine.back(s, now) }
    fun pauseResume() = transition { s, now -> if (s.isPaused) RunnerEngine.resume(s, now) else RunnerEngine.pause(s, now) }
    fun adjustRest(deltaSeconds: Int) = transition { s, _ -> RunnerEngine.adjustRest(s, deltaSeconds) }

    fun setWeight(exerciseIndex: Int, kg: Double?) = transition { s, _ -> RunnerEngine.setOverride(s, exerciseIndex, kg, null) }
    fun setReps(exerciseIndex: Int, reps: Int) = transition { s, _ -> RunnerEngine.setOverride(s, exerciseIndex, null, reps.coerceIn(1, 50)) }

    /** The owned load one step up or down from [kg] for this exercise's equipment. */
    fun steppedWeight(step: RunnerStep, kg: Double?, up: Boolean): Double? {
        val equipment = equipmentFor(step.exerciseName)
        if (equipment == Equipment.BODYWEIGHT) return null
        val loads = Gear.loadsFor(equipment)
        if (kg == null) return if (up) loads.firstOrNull() else null
        return if (up) loads.firstOrNull { it > kg + 0.01 } ?: kg else loads.lastOrNull { it < kg - 0.01 } ?: kg
    }

    fun equipmentFor(exerciseName: String): Equipment =
        library.firstOrNull { it.name.equals(exerciseName, ignoreCase = true) }?.equipmentType
            ?: if (_state.value.snapshot?.plan?.exercises?.any { it.name == exerciseName && it.weightKg == null } == true) Equipment.BODYWEIGHT else Equipment.CABLE

    /** Throw the session away entirely. */
    fun discard() {
        store.clear()
        _state.update { it.copy(snapshot = null, steps = emptyList()) }
    }

    /** Write the session and its sets, then optionally hand a summary to intervals.icu. */
    fun finish(perceivedEffort: Int?, notes: String, pushToIntervals: Boolean) {
        val s = _state.value.snapshot ?: return
        if (_state.value.saving || _state.value.saved) return
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val startedAt = s.sessionStartedAtMs
                val date = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                val durationMinutes = ((now - startedAt) / 60_000L).toInt().coerceIn(1, 24 * 60)
                val session = GymSession(
                    id = UUID.randomUUID().toString(),
                    name = s.plan.sessionName,
                    date = date.toString(),
                    startedAtMillis = startedAt,
                    durationMinutes = durationMinutes,
                    notes = notes.trim().ifBlank { null },
                    perceivedEffort = perceivedEffort
                )
                val sets = RunnerEngine.toGymSets(s, session.id)
                repo.saveSession(session, sets)
                // The Hub counts strength days from done_log; keep it in step.
                try { doneRepo.logDone("gym", session.date) } catch (e: Exception) { e.printStackTrace() }
                store.clear()
                store.clearRecommendation()
                _state.update { it.copy(saving = false, saved = true) }

                if (pushToIntervals) {
                    val result = IntervalsGymPush.push(settings, session, sets)
                    _state.update { it.copy(intervalsMessage = result.fold({ "Pushed to intervals.icu" }, { "intervals.icu push failed: ${it.message}" })) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update { it.copy(saving = false, saveError = e.message ?: "Could not save the session") }
            }
        }
    }

    private fun transition(f: (RunnerSnapshot, Long) -> RunnerSnapshot) {
        val s = _state.value.snapshot ?: return
        val now = System.currentTimeMillis()
        val next = f(s, now)
        if (next == s) { _state.update { it.copy(nowMs = now) }; return }
        cueTransition(s, next)
        commit(next, now)
    }

    private fun commit(next: RunnerSnapshot, now: Long) {
        store.save(next)
        lastCueSecond = -1
        _state.update { it.copy(snapshot = next, nowMs = now) }
    }

    private fun cueTransition(before: RunnerSnapshot, after: RunnerSnapshot) {
        if (after.stepIndex == before.stepIndex) return
        val steps = _state.value.steps
        val from = steps.getOrNull(before.stepIndex)
        val to = if (after.finished) null else steps.getOrNull(after.stepIndex)
        when (from?.kind) {
            StepKind.REST, StepKind.SWAP -> audio.playRestEnd()
            StepKind.HOLD -> audio.playWorkEnd()
            else -> Unit
        }
        if (to?.isWork == true) audio.playWorkStart()
    }

    private fun cueCountdown(now: Long) {
        val s = _state.value.snapshot ?: return
        if (s.isPaused) return
        val step = RunnerEngine.currentStep(s, _state.value.steps) ?: return
        if (step.kind == StepKind.REPS) return
        val remaining = RunnerEngine.remainingMs(s, now, _state.value.steps) ?: return
        val seconds = ((remaining + 999) / 1000).toInt()
        if (seconds in 1..3 && seconds != lastCueSecond) {
            lastCueSecond = seconds
            audio.playTick()
        }
    }

    override fun onCleared() {
        super.onCleared()
        audio.release()
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ShiftApplication
                StrengthRunnerViewModel(
                    application = app,
                    store = RunnerStore(app),
                    repo = GymRepository(app.database.gymDao()),
                    doneRepo = DoneRepository(app.database.doneDao()),
                    settings = SettingsManager(app)
                )
            }
        }
    }
}

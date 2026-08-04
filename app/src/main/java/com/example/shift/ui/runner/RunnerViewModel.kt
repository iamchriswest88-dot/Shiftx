package com.example.shift.ui.runner

import android.app.Application
import android.content.Context
import android.os.CountDownTimer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.shift.ShiftApplication
import com.example.shift.data.model.Exercise
import com.example.shift.data.model.WorkoutWithSteps
import com.example.shift.data.repository.DoneRepository
import com.example.shift.data.repository.ExerciseRepository
import com.example.shift.data.repository.WorkoutRepository
import com.example.shift.data.SettingsManager
import com.example.shift.data.ApiClient
import com.example.shift.data.IntervalsEventRequest
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class PhaseType { WORK, SWAP, REST }

data class Phase(
    val exerciseId:   String,
    val isWeightLoggable: Boolean,
    val suggestedWeight: Float?,
    val type:         PhaseType,
    val exerciseName: String,
    val side:         String,       // "LEFT", "RIGHT", or ""
    val durationSec:  Int,
    val setNumber:    Int,
    val totalSets:    Int,
    val roundNumber:  Int,
    val totalRounds:  Int
)

data class RunnerState(
    val exerciseName: String  = "",
    val phaseLabel:   String  = "",
    val sideLabel:    String  = "",
    val workoutName:  String  = "",
    val exerciseInstruction: String = "",
    val isInstructionLoading: Boolean = false,
    val secondsLeft:  Int     = 0,
    val totalSeconds: Int     = 0,
    val currentSet:   Int     = 1,
    val totalSets:    Int     = 1,
    val currentRound: Int     = 1,
    val totalRounds:  Int     = 1,
    val isPaused:     Boolean = false,
    val isLoading:    Boolean = true,
    val isFinished:   Boolean = false,
    val category:     String  = "",
    val showWeightPrompt: Boolean = false,
    val promptExerciseId: String = "",
    val promptExerciseName: String = "",
    val suggestedWeight: Float? = null,
    val dateKey: String = "",
    val startTimeMillis: Long = 0L,
    val endTimeMillis: Long = 0L,
    val hrTss: Int? = null,
    val isTssCalculating: Boolean = false,
    val showRpePrompt: Boolean = false
)

class RunnerViewModel(
    private val workoutId:   String,
    private val workoutRepo: WorkoutRepository,
    private val exerciseRepo: ExerciseRepository,
    private val doneRepo:    DoneRepository,
    private val settingsManager: SettingsManager,
    application:             Application
) : AndroidViewModel(application) {

    private val audio = AudioCueManager(application)
    private var phases: List<Phase> = emptyList()
    private var phaseIndex = 0
    private var timer: CountDownTimer? = null
    private var pausedSecondsLeft = 0
    private var workoutData: WorkoutWithSteps? = null
    private val fmt = DateTimeFormatter.ISO_DATE
    private val loggedExercisesInThisSession = mutableSetOf<String>()

    private val _state = MutableStateFlow(RunnerState())
    val state: StateFlow<RunnerState> = _state

    var audioMuted: Boolean
        get()  = audio.muted
        set(v) { audio.muted = v }

    init {
        viewModelScope.launch {
            val wws = workoutRepo.getWorkoutWithSteps(workoutId).first()
            val allEx = exerciseRepo.getAllExercises().first()
            
            val suggestedMap = mutableMapOf<String, Float>()
            if (wws != null) {
                val exIds = wws.steps.map { it.exerciseId }.distinct()
                val logs = exerciseRepo.getLatestLogs(exIds)
                for (log in logs) {
                    val suggestion = when (log.feeling) {
                        1 -> log.weightUsed * 0.9f
                        2 -> log.weightUsed
                        3 -> log.weightUsed * 1.05f
                        else -> log.weightUsed
                    }
                    suggestedMap[log.exerciseId] = suggestion
                }
            }

            workoutData = wws
            if (wws != null) {
                _state.update { it.copy(workoutName = wws.workout.name, startTimeMillis = System.currentTimeMillis()) }
                phases = buildQueue(wws, allEx, suggestedMap)
                if (phases.isNotEmpty()) startPhase() else finishWorkout()
            } else {
                _state.update { it.copy(isLoading = false, isFinished = true) }
            }
        }
    }

    private fun buildQueue(wws: WorkoutWithSteps, exercises: List<Exercise>, suggestedMap: Map<String, Float>): List<Phase> {
        val raw = mutableListOf<Phase>()
        val totalRounds = wws.workout.rounds
        for (round in 1..totalRounds) {
            for (step in wws.steps.sortedBy { it.sortOrder }) {
                val ex = exercises.find { it.id == step.exerciseId }
                val isLoggable = ex != null && ex.equipment != "None" && ex.equipment != "Bodyweight"
                val sw = suggestedMap[step.exerciseId]
                
                for (set in 1..step.sets) {
                    if (step.sides) {
                        raw += Phase(step.exerciseId, isLoggable, sw, PhaseType.WORK, step.exerciseName, "LEFT",  step.workSec, set, step.sets, round, totalRounds)
                        raw += Phase(step.exerciseId, isLoggable, sw, PhaseType.SWAP, step.exerciseName, "RIGHT", step.swapSec, set, step.sets, round, totalRounds)
                        raw += Phase(step.exerciseId, isLoggable, sw, PhaseType.WORK, step.exerciseName, "RIGHT", step.workSec, set, step.sets, round, totalRounds)
                    } else {
                        raw += Phase(step.exerciseId, isLoggable, sw, PhaseType.WORK, step.exerciseName, "",      step.workSec, set, step.sets, round, totalRounds)
                    }
                    raw += Phase(step.exerciseId, isLoggable, sw, PhaseType.REST, step.exerciseName, "", step.restSec, set, step.sets, round, totalRounds)
                }
            }
        }
        // Drop trailing REST(s)
        while (raw.isNotEmpty() && raw.last().type == PhaseType.REST) raw.removeLast()
        
        // Second pass: Update REST/SWAP to preview the NEXT work phase
        for (i in 0 until raw.size - 1) {
            val p = raw[i]
            if (p.type == PhaseType.REST || p.type == PhaseType.SWAP) {
                // Find next WORK phase
                var nextWork: Phase? = null
                for (j in (i + 1) until raw.size) {
                    if (raw[j].type == PhaseType.WORK) {
                        nextWork = raw[j]
                        break
                    }
                }
                if (nextWork != null) {
                    raw[i] = p.copy(
                        exerciseId       = nextWork.exerciseId,
                        isWeightLoggable = nextWork.isWeightLoggable,
                        suggestedWeight  = nextWork.suggestedWeight,
                        exerciseName     = nextWork.exerciseName,
                        side             = nextWork.side,
                        setNumber        = nextWork.setNumber,
                        totalSets        = nextWork.totalSets,
                        roundNumber      = nextWork.roundNumber,
                        totalRounds      = nextWork.totalRounds
                    )
                }
            }
        }
        return raw
    }

    private val instructionCache = mutableMapOf<String, String>()

    private fun startPhase() {
        val phase = phases[phaseIndex]
        
        // Check if we should prompt for weight
        // Trigger condition: we are entering REST, and the PREVIOUS phase was the final WORK phase for a loggable exercise.
        var showPrompt = false
        var pExId = ""
        var pExName = ""
        if (phase.type == PhaseType.REST && phaseIndex > 0) {
            val prevPhase = phases[phaseIndex - 1]
            if (prevPhase.type == PhaseType.WORK && prevPhase.isWeightLoggable && prevPhase.setNumber == prevPhase.totalSets) {
                showPrompt = true
                pExId = prevPhase.exerciseId
                pExName = prevPhase.exerciseName
            }
        }

        _state.update {
            it.copy(
                isLoading    = false,
                exerciseName = phase.exerciseName,
                phaseLabel   = phase.type.name,
                sideLabel    = phase.side,
                secondsLeft  = phase.durationSec,
                totalSeconds = phase.durationSec,
                currentSet   = phase.setNumber,
                totalSets    = phase.totalSets,
                currentRound = phase.roundNumber,
                totalRounds  = phase.totalRounds,
                isPaused     = false,
                isInstructionLoading = false,
                exerciseInstruction = "",
                showWeightPrompt = showPrompt,
                promptExerciseId = pExId,
                promptExerciseName = pExName,
                suggestedWeight = phase.suggestedWeight
            )
        }
        
        if (phase.type == PhaseType.WORK || phase.type == PhaseType.REST || phase.type == PhaseType.SWAP) {
            val exName = phase.exerciseName
            if (instructionCache.containsKey(exName)) {
                _state.update { it.copy(exerciseInstruction = instructionCache[exName] ?: "", isInstructionLoading = false) }
            } else {
                fetchInstruction(exName)
            }
        }

        if (phase.type == PhaseType.WORK) audio.playWorkStart()

        timer = object : CountDownTimer(phase.durationSec.toLong() * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                val secs = ((ms + 500L) / 1000L).toInt()
                _state.update { it.copy(secondsLeft = secs) }
                if (secs in 1..3 && phase.type != PhaseType.REST) audio.playTick()
            }
            override fun onFinish() {
                _state.update { it.copy(secondsLeft = 0) }
                when (phase.type) {
                    PhaseType.WORK -> audio.playWorkEnd()
                    PhaseType.REST -> audio.playRestEnd()
                    PhaseType.SWAP -> Unit
                }
                advance()
            }
        }.start()
    }

    private fun fetchInstruction(exerciseName: String) {
        if (_state.value.isInstructionLoading) return
        _state.update { it.copy(isInstructionLoading = true, exerciseInstruction = "") }
        viewModelScope.launch {
            try {
                val apiKey = com.example.shift.BuildConfig.GEMINI_API_KEY.trim()
                if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                    instructionCache[exerciseName] = "Mock instruction for $exerciseName"
                    _state.update { it.copy(exerciseInstruction = instructionCache[exerciseName] ?: "", isInstructionLoading = false) }
                    return@launch
                }
                val model = com.google.ai.client.generativeai.GenerativeModel("gemini-2.5-flash", apiKey)
                val prompt = "Provide a very short, 1-2 sentence instruction on how to perform the exercise: '$exerciseName'. Focus purely on form. No markdown formatting, just plain text."
                val response = model.generateContent(prompt)
                val text = response.text?.trim() ?: "No instruction available."
                instructionCache[exerciseName] = text
                if (_state.value.exerciseName == exerciseName) {
                    _state.update { it.copy(exerciseInstruction = text, isInstructionLoading = false) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (_state.value.exerciseName == exerciseName) {
                    _state.update { it.copy(exerciseInstruction = "Failed to load instruction.", isInstructionLoading = false) }
                }
            }
        }
    }

    private fun advance() {
        phaseIndex++
        if (phaseIndex >= phases.size) finishWorkout() else startPhase()
    }

    fun pause() {
        if (_state.value.isPaused) return
        timer?.cancel()
        pausedSecondsLeft = _state.value.secondsLeft
        _state.update { it.copy(isPaused = true) }
    }

    fun resume() {
        if (!_state.value.isPaused) return
        _state.update { it.copy(isPaused = false) }
        val phase = phases[phaseIndex]
        timer = object : CountDownTimer(pausedSecondsLeft * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                val secs = ((ms + 500L) / 1000L).toInt()
                _state.update { it.copy(secondsLeft = secs) }
                if (secs in 1..3 && phase.type != PhaseType.REST) audio.playTick()
            }
            override fun onFinish() {
                _state.update { it.copy(secondsLeft = 0) }
                when (phase.type) {
                    PhaseType.WORK -> audio.playWorkEnd()
                    PhaseType.REST -> audio.playRestEnd()
                    PhaseType.SWAP -> Unit
                }
                advance()
            }
        }.start()
    }
    
    fun skip() {
        timer?.cancel()
        _state.update { it.copy(secondsLeft = 0) }
        advance()
    }

    fun logWeight(weight: Float, feeling: Int) {
        val exId = _state.value.promptExerciseId
        if (exId.isNotBlank()) {
            viewModelScope.launch {
                val rounds = workoutData?.workout?.rounds ?: 1
                val stepSets = workoutData?.steps?.filter { it.exerciseId == exId }?.sumOf { it.sets } ?: 1
                val setsCount = stepSets * rounds
                for (i in 1..setsCount) {
                    exerciseRepo.logWeight(exId, weight, feeling)
                    kotlinx.coroutines.delay(10) // Ensure dateMillis is slightly different for sorting
                }
                loggedExercisesInThisSession.add(exId)
            }
        }
        dismissWeightPrompt()
    }

    fun dismissWeightPrompt() {
        _state.update { it.copy(showWeightPrompt = false) }
    }

    private fun finishWorkout() {
        timer?.cancel()
        _state.update { it.copy(showRpePrompt = true) }
    }
    
    fun dismissRpePrompt() {
        _state.update { it.copy(showRpePrompt = false) }
        completeWorkoutWithRpe(null)
    }

    fun completeWorkoutWithRpe(rpe: Float?) {
        _state.update { it.copy(showRpePrompt = false) }
        val c = workoutData?.workout?.category ?: "gym"
        val endTime = System.currentTimeMillis()
        val dateStr = LocalDate.now().format(fmt)
        _state.update { it.copy(isFinished = true, category = c, endTimeMillis = endTime, dateKey = dateStr) }
        
        viewModelScope.launch {
            workoutData?.let { wws ->
                // Add DoneLog
                doneRepo.logDone(wws.workout.category, dateStr)

                // Auto-log any exercises that the user didn't explicitly log weight for
                val uniqueExercises = wws.steps.map { it.exerciseId }.distinct()
                val rounds = wws.workout.rounds
                for (exId in uniqueExercises) {
                    if (!loggedExercisesInThisSession.contains(exId)) {
                        // Use suggested weight if available, otherwise 0f
                        val sw = phases.find { it.exerciseId == exId }?.suggestedWeight ?: 0f
                        val stepSets = wws.steps.filter { it.exerciseId == exId }.sumOf { it.sets }
                        val setsCount = stepSets * rounds
                        for (i in 1..setsCount) {
                            exerciseRepo.logWeight(exId, sw, 2)
                            kotlinx.coroutines.delay(10) // Ensure dateMillis is slightly different for sorting
                        }
                        loggedExercisesInThisSession.add(exId)
                    }
                }
                
                // Upload to Intervals if RPE is provided
                if (rpe != null) {
                    try {
                        val key = settingsManager.apiKeyFlow.firstOrNull() ?: ""
                        val id = settingsManager.athleteIdFlow.firstOrNull() ?: ""
                        if (key.isNotBlank() && id.isNotBlank()) {
                            val api = ApiClient.create(key)
                            val durationSecs = ((endTime - _state.value.startTimeMillis) / 1000).toInt()
                            // Calculate TSS: (duration in hours) * (RPE / 10)^2 * 100
                            // Simplified equivalent to match generic hrTSS/sTSS algorithms
                            val tss = ((durationSecs / 3600f) * (rpe / 10f) * 100f).toInt().coerceAtLeast(1)
                            
                            // start date local format: 2024-06-25T17:00:00
                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                            val startDate = java.time.LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(_state.value.startTimeMillis), 
                                java.time.ZoneId.systemDefault()
                            ).format(formatter)
                            val isYogaOrStretch = wws.workout.name.contains("Yoga", ignoreCase = true) || wws.workout.name.contains("Stretch", ignoreCase = true)
                            val activityType = if (isYogaOrStretch) "Yoga" else "WeightTraining"

                            val req = IntervalsEventRequest(
                                start_date_local = startDate,
                                type = activityType,
                                name = wws.workout.name,
                                moving_time = durationSecs,
                                icu_training_load = tss,
                                category = "WORKOUT"
                            )
                            val res = api.createEvent(id, req)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(getApplication(), "Uploaded to Intervals!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        var errorMsg = e.message
                        if (e is retrofit2.HttpException) {
                            try {
                                val errorBody = e.response()?.errorBody()?.string()
                                if (!errorBody.isNullOrBlank()) {
                                    errorMsg = "$errorMsg: $errorBody"
                                }
                            } catch (ignored: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(getApplication(), "Upload failed: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    fun calculateHrTss(context: Context) {
        // Not fetching from Health Connect in this version
    }

    override fun onCleared() {
        super.onCleared()
        timer?.cancel()
    }

    companion object {
        fun factory(workoutId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ShiftApplication
                val db  = app.database
                RunnerViewModel(
                    workoutId    = workoutId,
                    workoutRepo  = WorkoutRepository(db.workoutDao(), db.stepDao()),
                    exerciseRepo = ExerciseRepository(db.exerciseDao(), db.exerciseLogDao()),
                    doneRepo     = DoneRepository(db.doneDao()),
                    settingsManager = SettingsManager(app),
                    application  = app
                )
            }
        }
    }
}

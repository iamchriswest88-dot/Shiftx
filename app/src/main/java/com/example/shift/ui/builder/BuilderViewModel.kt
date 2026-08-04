package com.example.shift.ui.builder

import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.example.shift.ShiftApplication

import com.example.shift.data.model.Exercise
import com.example.shift.data.model.Step
import com.example.shift.data.model.Workout
import com.example.shift.data.repository.ExerciseRepository
import com.example.shift.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class StepDraft(
    val id:       String   = UUID.randomUUID().toString(),
    val exercise: Exercise? = null,
    val sets:     Int      = 3,
    val workSec:  Int      = 30,
    val restSec:  Int      = 15,
    val sides:    Boolean  = false,
    val swapSec:  Int      = 5,
    val isSwapping: Boolean = false,
) {
    fun toStep(workoutId: String, order: Int): Step? {
        val ex = exercise ?: return null
        return Step(
            id           = id,
            workoutId    = workoutId,
            exerciseId   = ex.id,
            exerciseName = ex.name,
            sets         = sets,
            workSec      = workSec,
            restSec      = restSec,
            sides        = sides,
            swapSec      = swapSec,
            sortOrder    = order
        )
    }
    fun summary(isFlow: Boolean): String {
        val w = if (isFlow) "Hold" else "Timed"
        val s = if (isFlow) "rounds" else "sets"
        val side = if (sides) " · both sides" else ""
        return "$w ${workSec}s · $sets $s$side · ${restSec}s rest"
    }
}

class BuilderViewModel(
    val category: String,
    private val existingWorkoutId: String?,
    private val workoutRepo: WorkoutRepository,
    private val exerciseRepo: ExerciseRepository,
) : ViewModel() {

    var workoutName by mutableStateOf("")
        private set
    var workoutDescription by mutableStateOf("")
        private set
    var workoutRounds by mutableStateOf(1)
        private set
    val isFlow = category == "flow"

    private val _steps = mutableStateListOf<StepDraft>()
    val steps: List<StepDraft> = _steps

    var suggestedWeights by mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    val exercises: StateFlow<List<Exercise>> =
        exerciseRepo.getExercises(category)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        existingWorkoutId?.let { id ->
            viewModelScope.launch {
                workoutRepo.getWorkoutWithSteps(id).first()?.let { wws ->
                    workoutName = wws.workout.name
                    workoutDescription = wws.workout.description
                    workoutRounds = wws.workout.rounds
                    val exList  = exerciseRepo.getExercises(category).first()
                    _steps.clear()
                    _steps.addAll(wws.steps.sortedBy { it.sortOrder }.map { s ->
                        StepDraft(
                            id       = s.id,
                            exercise = exList.find { it.id == s.exerciseId } ?: Exercise(
                                id = s.exerciseId,
                                name = s.exerciseName,
                                area = "",
                                category = category
                            ),
                            sets     = s.sets,
                            workSec  = s.workSec,
                            restSec  = s.restSec,
                            sides    = s.sides,
                            swapSec  = s.swapSec,
                        )
                    })
                }
            }
        }
        
        viewModelScope.launch {
            snapshotFlow { _steps.toList() }.collect { stepsList ->
                val exerciseIds = stepsList.mapNotNull { it.exercise?.id }.distinct()
                if (exerciseIds.isNotEmpty()) {
                    val logs = exerciseRepo.getLatestLogs(exerciseIds)
                    val map = logs.associate { log ->
                        val suggestion = when (log.feeling) {
                            1 -> log.weightUsed * 0.9f  // Too heavy
                            2 -> log.weightUsed         // Good
                            3 -> log.weightUsed * 1.05f // Too light
                            else -> log.weightUsed
                        }
                        log.exerciseId to suggestion
                    }
                    suggestedWeights = map
                } else {
                    suggestedWeights = emptyMap()
                }
            }
        }
    }

    fun setName(name: String) { workoutName = name }
    fun setDescription(description: String) { workoutDescription = description }
    fun setRounds(rounds: Int) { workoutRounds = rounds }
    fun addStep() { _steps += StepDraft() }
    fun updateStep(index: Int, step: StepDraft) { if (index in _steps.indices) _steps[index] = step }
    fun removeStep(index: Int) { if (index in _steps.indices) _steps.removeAt(index) }

    fun save(onDone: () -> Unit) {
        val id = existingWorkoutId ?: UUID.randomUUID().toString()
        val w = Workout(id, if (workoutName.isBlank()) "Unnamed" else workoutName, category, workoutDescription, workoutRounds)
        val s = _steps.mapIndexedNotNull { i, d -> d.toStep(id, i) }
        viewModelScope.launch {
            workoutRepo.saveWorkout(w, s)
            onDone()
        }
    }

    fun deleteWorkout(onDone: () -> Unit) {
        val id = existingWorkoutId ?: return
        viewModelScope.launch { workoutRepo.deleteWorkout(id); onDone() }
    }

    fun addCustomExercise(name: String, area: String, onAdded: (Exercise) -> Unit) {
        viewModelScope.launch {
            val ex = Exercise(UUID.randomUUID().toString(), name.trim(), area, category, isCustom = true)
            exerciseRepo.addCustomExercise(ex)
            onAdded(ex)
        }
    }

    fun swapExerciseAI(index: Int) {
        if (index !in _steps.indices) return
        val step = _steps[index]
        val stepId = step.id
        
        // If no exercise is selected yet, just pick a random one
        if (step.exercise == null) {
            val randomEx = exercises.value.randomOrNull()
            if (randomEx != null) {
                val idx = _steps.indexOfFirst { it.id == stepId }
                if (idx != -1) _steps[idx] = step.copy(exercise = randomEx)
            }
            return
        }
        
        val currentEx = step.exercise
        
        // Mark as swapping
        val idxToMark = _steps.indexOfFirst { it.id == stepId }
        if (idxToMark != -1) _steps[idxToMark] = step.copy(isSwapping = true)
        
        viewModelScope.launch {
            try {
                val apiKey = com.example.shift.BuildConfig.GEMINI_API_KEY.trim()
                val exList = exercises.value.filter { it.id != currentEx.id && it.area == currentEx.area }
                
                if (exList.isEmpty() || apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                    val randomEx = (exercises.value - currentEx).randomOrNull()
                    val idx = _steps.indexOfFirst { it.id == stepId }
                    if (idx != -1) {
                        _steps[idx] = _steps[idx].copy(exercise = randomEx ?: currentEx, isSwapping = false)
                    }
                    return@launch
                }
                
                val exListString = exList.joinToString("\n") { "[ID: ${it.id}, Name: ${it.name}]" }
                val model = com.google.ai.client.generativeai.GenerativeModel("gemini-2.5-flash", apiKey)
                val prompt = """
                    The user wants to swap out the exercise '${currentEx.name}' for a different one that works the same muscle group (${currentEx.area}).
                    Here are the available exercises:
                    $exListString
                    
                    Return ONLY the ID of the best alternative exercise. Do not include any other text, quotes, or markdown.
                """.trimIndent()
                
                val response = model.generateContent(prompt)
                val text = response.text ?: ""
                
                // Extract UUID using regex to handle any weird formatting
                val uuidRegex = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".toRegex()
                val match = uuidRegex.find(text)
                val newId = match?.value ?: text.trim()
                
                val newEx = exList.find { it.id == newId }
                
                val idx = _steps.indexOfFirst { it.id == stepId }
                if (idx != -1) {
                    if (newEx != null) {
                        _steps[idx] = _steps[idx].copy(exercise = newEx, isSwapping = false)
                    } else {
                        // Fallback to random
                        _steps[idx] = _steps[idx].copy(exercise = exList.randomOrNull() ?: currentEx, isSwapping = false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val idx = _steps.indexOfFirst { it.id == stepId }
                if (idx != -1) {
                    // Fallback to random on API error
                    val exList = exercises.value.filter { it.id != currentEx.id && it.area == currentEx.area }
                    val fallbackEx = exList.randomOrNull() ?: (exercises.value - currentEx).randomOrNull()
                    _steps[idx] = _steps[idx].copy(exercise = fallbackEx ?: currentEx, isSwapping = false)
                }
            }
        }
    }

    companion object {
        fun factory(category: String, workoutId: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ShiftApplication
                val db  = app.database
                BuilderViewModel(
                    category          = category,
                    existingWorkoutId = workoutId,
                    workoutRepo       = WorkoutRepository(db.workoutDao(), db.stepDao()),
                    exerciseRepo      = ExerciseRepository(db.exerciseDao(), db.exerciseLogDao()),
                )
            }
        }
    }
}

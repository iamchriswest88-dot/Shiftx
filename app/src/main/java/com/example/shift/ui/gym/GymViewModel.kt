package com.example.shift.ui.gym

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.example.shift.ShiftApplication
import com.example.shift.data.model.Workout
import com.example.shift.data.SettingsManager
import com.example.shift.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GymViewModel(
    private val workoutRepo: WorkoutRepository,
    private val exerciseRepo: ExerciseRepository,
    private val doneRepo: DoneRepository,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    val workouts: StateFlow<List<Workout>> = workoutRepo.getAllWorkouts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun generateWorkout(prompt: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val flowKey = settingsManager.geminiApiKeyFlow.first() ?: ""
                val apiKey = if (flowKey.isNotEmpty()) flowKey else com.example.shift.BuildConfig.GEMINI_API_KEY.trim()
                if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                    onError("Please add your Gemini API Key in Settings")
                    _isGenerating.value = false
                    return@launch
                }

                val exercises = exerciseRepo.getExercises("gym").first()
                if (exercises.isEmpty()) {
                    // Create a generic fallback workout if no exercises exist
                    val workoutId = java.util.UUID.randomUUID().toString()
                    val workout = Workout(id = workoutId, name = "Generated Workout", category = "gym", description = "Fallback", rounds = 1)
                    val steps = (0 until 3).map { i ->
                        com.example.shift.data.model.Step(
                            id = java.util.UUID.randomUUID().toString(),
                            workoutId = workoutId,
                            exerciseId = "ex-$i",
                            exerciseName = "Exercise $i",
                            sortOrder = i,
                            sets = 3,
                            workSec = 45,
                            sides = false
                        )
                    }
                    workoutRepo.saveWorkout(workout, steps)
                    onSuccess(workoutId)
                    return@launch
                }
                val exList = exercises.joinToString("\n") { "[ID: ${it.id}, Name: ${it.name}, Area: ${it.area}, Equipment: ${it.equipment}]" }
                
                val generativeModel = com.google.ai.client.generativeai.GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey,
                    generationConfig = com.google.ai.client.generativeai.type.generationConfig {
                        responseMimeType = "application/json"
                    }
                )

                val fullPrompt = """
                    You are a fitness coach. The user wants to do a strength/gym workout based on this request: "$prompt".
                    You can use the following known exercises:
                    $exList
                    
                    You may also freely invent NEW exercises if they better fit the user's request.
                    You can design traditional workouts (multiple sets per exercise) OR circuit-style workouts (listing a sequence of exercises with 1 set each) depending on what fits best.
                    
                    CRITICAL: If the user requests a specific duration (e.g., "20 mins", "45 mins"), YOU MUST calculate the total time and provide enough exercises and sets to fill that duration!
                    Math reminder: Total Time = sum of (rounds * sets * (workSec + restSec)) for all exercises.
                    For a 20-minute workout, you need 1200 seconds of total volume. Provide enough volume to match the requested time!
                    
                    Return a JSON object with exactly these keys:
                    - "workoutName": (string) a highly engaging, hype name for the workout (e.g. "Heavy Dumbbell Chest Smasher!"). IMPORTANT: If the user requested Yoga, Stretching, or Mobility, you MUST include the word "Yoga" or "Stretch" somewhere in the name.
                    - "description": (string) a hype, engaging overview explaining why you chose these exercises and giving advice (like a real trainer would). Keep it to 1-2 sentences.
                    - "rounds": (int) the number of times to repeat the ENTIRE sequence of exercises. For traditional workouts, this should be 1. For circuit workouts, you can set this to >1 and keep individual exercise 'sets' to 1.
                    - "exercises": a JSON array of objects, each representing an exercise in the workout.
                    
                    Each object in the "exercises" array must have exactly these keys:
                    - "id": (string) the ID of the chosen exercise. If you invent a NEW exercise, set this to "NEW".
                    - "name": (string) the name of the exercise.
                    - "area": (string) the primary muscle group or area (e.g. Chest, Back, Legs, Full Body).
                    - "equipment": (string) the equipment required (e.g. Dumbbell, Barbell, Bodyweight, Cable, Machine).
                    - "sets": (int) the number of sets (e.g., 3-5 for traditional, 1 for circuits).
                    - "workSec": (int) the duration in seconds (usually 30 to 60).
                    - "restSec": (int) the rest duration in seconds (usually 15 to 45).
                    
                    The workout should be tailored to their request. Return ONLY the JSON object.
                """.trimIndent()

                val response = generativeModel.generateContent(fullPrompt)
                var text = response.text ?: "{}"
                
                // Strip markdown formatting if Gemini included it
                val startIndex = text.indexOf('{')
                val endIndex = text.lastIndexOf('}')
                if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                    text = text.substring(startIndex, endIndex + 1)
                }
                
                val jsonObj = org.json.JSONObject(text)
                val workoutName = jsonObj.optString("workoutName", "AI: $prompt".take(30))
                val description = jsonObj.optString("description", "")
                val rounds = jsonObj.optInt("rounds", 1)
                val jsonArray = jsonObj.optJSONArray("exercises") ?: org.json.JSONArray()
                
                val workoutId = java.util.UUID.randomUUID().toString()
                val workout = Workout(id = workoutId, name = workoutName, category = "gym", description = description, rounds = rounds)
                
                val steps = mutableListOf<com.example.shift.data.model.Step>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.getString("name")
                    val area = obj.optString("area", "Full Body")
                    val equipment = obj.optString("equipment", "Bodyweight")
                    
                    val ex = if (id == "NEW") {
                        val newEx = com.example.shift.data.model.Exercise(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            area = area,
                            category = "gym",
                            equipment = equipment,
                            isCustom = true
                        )
                        exerciseRepo.addCustomExercise(newEx)
                        newEx
                    } else {
                        exercises.find { it.id == id } ?: com.example.shift.data.model.Exercise(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            area = area,
                            category = "gym",
                            equipment = equipment,
                            isCustom = true
                        ).also { exerciseRepo.addCustomExercise(it) }
                    }

                    steps.add(
                        com.example.shift.data.model.Step(
                            id = java.util.UUID.randomUUID().toString(),
                            workoutId = workoutId,
                            exerciseId = ex.id,
                            exerciseName = ex.name,
                            sortOrder = i,
                            sets = obj.getInt("sets"),
                            workSec = obj.getInt("workSec"),
                            restSec = if (obj.has("restSec")) obj.getInt("restSec") else 15,
                            sides = ex.isUnilateral
                        )
                    )
                }
                workoutRepo.saveWorkout(workout, steps)
                onSuccess(workoutId)
            } catch (e: Exception) {
                e.printStackTrace()
                onError(e.message ?: "Failed to generate workout")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ShiftApplication
                val db  = app.database
                GymViewModel(
                    workoutRepo  = WorkoutRepository(db.workoutDao(), db.stepDao()),
                    exerciseRepo = ExerciseRepository(db.exerciseDao(), db.exerciseLogDao()),
                    doneRepo     = DoneRepository(db.doneDao()),
                    settingsManager = SettingsManager(app)
                )
            }
        }
    }
}

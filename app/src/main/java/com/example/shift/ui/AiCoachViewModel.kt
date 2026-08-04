package com.example.shift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shift.BuildConfig
import com.example.shift.data.CoachHistoryManager
import com.example.shift.data.CoachMemory
import com.example.shift.data.CoachMemoryManager
import com.example.shift.data.MatchCacheManager
import com.example.shift.data.PersistedMessage
import com.example.shift.data.CourseManager
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.shift.data.model.Workout
import com.example.shift.data.model.Step
import com.example.shift.data.model.Exercise
import com.example.shift.data.repository.ExerciseRepository
import com.example.shift.data.repository.WorkoutRepository
import com.example.shift.data.repository.DoneRepository
import java.util.UUID
import org.json.JSONObject

data class ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long)

// Marker to show a session-resume divider in the UI
val SESSION_DIVIDER = ChatMessage("── resumed ──", isUser = false, timestamp = 0L)

class AiCoachViewModel(
    private val mainViewModel: MainViewModel,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val historyManager: CoachHistoryManager,
    private val memoryManager: CoachMemoryManager,
    private val matchCacheManager: MatchCacheManager,
    private val courseManager: CourseManager,
    private val doneRepository: DoneRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<String>()
    val navigationEvent: SharedFlow<String> = _navigationEvent

    private val _memory = MutableStateFlow(CoachMemory())
    val memory: StateFlow<CoachMemory> = _memory.asStateFlow()

    private var chat: com.google.ai.client.generativeai.Chat? = null
    private var isNewSession = true

    fun initializeChat() {
        if (chat != null) return // Already initialized this session

        viewModelScope.launch {
            // Load persisted memory
            _memory.value = memoryManager.memoryFlow.first()

            // Load persisted chat history
            val storedMessages = historyManager.loadHistory()
            val isRestoredSession = storedMessages.isNotEmpty()

            val flowKey = mainViewModel.geminiApiKey.value.trim()
            val apiKey = if (flowKey.isNotEmpty()) flowKey else BuildConfig.GEMINI_API_KEY.trim()
            if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                addMessage("Please add your Gemini API Key in Settings and tap 'Save Locally'.", false)
                return@launch
            }

            val systemInstructionText = buildSystemPrompt()

            try {
                val generativeModel = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey,
                    systemInstruction = content { text(systemInstructionText) }
                )

                // Rebuild Gemini chat history from stored messages so it remembers context
                val geminiHistory = storedMessages
                    .filter { it.text != SESSION_DIVIDER.text }
                    .map { msg ->
                        content(role = if (msg.isUser) "user" else "model") { text(msg.text) }
                    }

                chat = generativeModel.startChat(history = geminiHistory)

                if (isRestoredSession) {
                    // Show previous messages with a resume divider
                    // Convert to ChatMessage first so SESSION_DIVIDER (ChatMessage) can be added
                    val displayMessages = storedMessages.map {
                        ChatMessage(it.text, it.isUser, it.timestamp)
                    }.toMutableList()
                    displayMessages.add(SESSION_DIVIDER)
                    _messages.value = displayMessages
                    isNewSession = false
                } else {
                    // New session — generate a proactive greeting
                    isNewSession = true
                    generateProactiveGreeting(apiKey, systemInstructionText)
                }

            } catch (e: Exception) {
                addMessage("Failed to initialize chat: ${e.message}", false)
            }
        }
    }

    private suspend fun generateProactiveGreeting(apiKey: String, systemContext: String) {
        _isThinking.value = true
        try {
            val greetingModel = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey,
                systemInstruction = content { text(systemContext) }
            )
            val prompt = """
                Generate a SHORT (2-3 sentence) personalized greeting for the athlete based on their data.
                - Reference one specific insight from their recent activities, vitals, or segment performance.
                - End with an open invitation to ask for help.
                - Be conversational and encouraging, not clinical.
                - Do NOT recommend a full workout unprompted. Just greet and invite.
            """.trimIndent()

            val response = greetingModel.generateContent(prompt)
            val greeting = response.text ?: "Hi! I've reviewed your recent training. How can I help you today?"
            addMessage(greeting, false)
        } catch (e: Exception) {
            addMessage("Hi! I've reviewed your recent training. How can I help you today?", false)
        } finally {
            _isThinking.value = false
        }
    }

    private suspend fun buildSystemPrompt(): String {
        val vitals = mainViewModel.userVitals.value
        val activities = mainViewModel.activities.value.take(20)
        val memory = _memory.value

        return buildString {
            appendLine("You are a smart, data-driven personal fitness coach. You have access to the user's full training history, vitals, segment PRs, and gym logs.")
            appendLine("Do NOT recommend a workout unless explicitly asked.")
            appendLine()

            // ── User Memory / Goals ──────────────────────────────────────────
            val hasMemory = memory.goals.isNotBlank() || memory.preferences.isNotBlank() || memory.notes.isNotBlank()
            if (hasMemory) {
                appendLine("USER PROFILE & GOALS:")
                if (memory.goals.isNotBlank()) appendLine("Goals: ${memory.goals}")
                if (memory.preferences.isNotBlank()) appendLine("Preferences: ${memory.preferences}")
                if (memory.notes.isNotBlank()) appendLine("Notes: ${memory.notes}")
                appendLine()
            }

            // ── Current Vitals ───────────────────────────────────────────────
            if (vitals != null) {
                appendLine("USER CURRENT STATE:")
                appendLine("Feel Score: ${vitals.feelScore}/100 (100=fresh, 0=exhausted)")
                appendLine("Avg Sleep (last 3 nights): ${String.format("%.1f", vitals.avgSleepHours)}h")
                if (vitals.avgRestingHR != null) appendLine("Avg Resting HR: ${String.format("%.0f", vitals.avgRestingHR)} bpm")
                if (vitals.avgHRV != null) appendLine("Avg HRV: ${String.format("%.0f", vitals.avgHRV)} ms")
                appendLine()
            }

            // ── Weekly Load Trend (last 8 weeks) ────────────────────────────
            if (activities.isNotEmpty()) {
                appendLine("WEEKLY TRAINING LOAD TREND (last 8 weeks):")
                val byWeek = activities.groupBy { act ->
                    try {
                        val date = java.time.LocalDate.parse(act.start_date_local.take(10))
                        val weekStart = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                        weekStart.toString()
                    } catch (e: Exception) { "unknown" }
                }.entries.sortedByDescending { it.key }.take(8)

                byWeek.forEach { (weekStart, acts) ->
                    val rides = acts.filter { it.type == "Ride" || it.type == "VirtualRide" }
                    val totalKm = acts.sumOf { it.distance ?: 0.0 } / 1000.0
                    val totalMins = acts.sumOf { it.moving_time ?: 0 } / 60
                    val avgTss = acts.mapNotNull { it.icu_training_load?.toInt() }.let {
                        if (it.isEmpty()) null else it.average().toInt()
                    }
                    val tssStr = if (avgTss != null) " · avg TSS $avgTss" else ""
                    appendLine("  Week of $weekStart: ${acts.size} activities (${rides.size} rides) · ${"%.0f".format(totalKm)}km · ${totalMins}min$tssStr")
                }
                appendLine()
            }

            // ── Recent Activities (detailed) ──────────────────────────────────
            appendLine("RECENT ACTIVITIES (last 20):")
            if (activities.isEmpty()) {
                appendLine("No recent activities found.")
            } else {
                activities.forEach { act ->
                    val date = act.start_date_local.take(10)
                    val dist = act.distance?.let { "${"%.1f".format(it / 1000)}km" } ?: ""
                    val time = act.moving_time?.let { "${it / 60}m" } ?: ""
                    val tl = act.icu_training_load?.let { "TSS: ${it.toInt()}" } ?: ""
                    val watts = act.icu_average_watts?.let { "avg ${it}W" } ?: ""
                    appendLine("  - $date | ${act.type} | ${act.name} | $time | $dist | $tl $watts".trimEnd())
                }
            }
            appendLine()

            // ── Segment PRs ──────────────────────────────────────────────────
            try {
                val courses = courseManager.coursesFlow.first()
                val allMatches = matchCacheManager.getAllMatches()
                if (courses.isNotEmpty() && allMatches.isNotEmpty()) {
                    appendLine("SEGMENT PR BOARD:")
                    courses.take(8).forEach { course ->
                        val courseMatches = allMatches.filter { it.courseId == course.id }
                            .sortedBy { it.timeSeconds }
                        if (courseMatches.isNotEmpty()) {
                            val pr = courseMatches.first()
                            val latest = allMatches.filter { it.courseId == course.id }
                                .sortedByDescending { it.activityId }
                                .firstOrNull()
                            val prStr = "${pr.timeSeconds / 60}:${"%02d".format(pr.timeSeconds % 60)}"
                            val latestStr = if (latest != null && latest.activityId != pr.activityId) {
                                val latSecs = latest.timeSeconds
                                val diff = latSecs - pr.timeSeconds
                                val latStr = "${latSecs / 60}:${"%02d".format(latSecs % 60)}"
                                " · last: $latStr (+${diff}s off PR)"
                            } else " · PR matched on last effort!"
                            appendLine("  ${course.name}: PR $prStr$latestStr")
                        }
                    }
                    appendLine()
                }
            } catch (e: Exception) { /* silently skip if unavailable */ }

            // ── Gym History ──────────────────────────────────────────────────
            try {
                val doneLogs = doneRepository.getAllFlow().first()
                val gymLogs = doneLogs
                    .filter { it.category.contains("gym", ignoreCase = true) || it.category.contains("workout", ignoreCase = true) }
                    .sortedByDescending { it.dateKey }
                    .take(10)
                if (gymLogs.isNotEmpty()) {
                    appendLine("GYM HISTORY (last 10 sessions):")
                    gymLogs.forEach { log ->
                        appendLine("  - ${log.dateKey} | ${log.category}")
                    }
                    appendLine()
                }
            } catch (e: Exception) { /* silently skip */ }

            // ── Instructions ─────────────────────────────────────────────────
            appendLine("INSTRUCTIONS:")
            appendLine("1. A Feel Score near 100 = very fresh. Near 0 = extremely fatigued. Adjust intensity recommendations accordingly.")
            appendLine("2. Reference specific data (e.g. segment times, recent TSS, sleep) when giving advice — don't be generic.")
            appendLine("3. ALWAYS include a workout tag at the end when the user requests or when you recommend a workout/routine/stretch, formatted EXACTLY like this on its own line:")
            appendLine("   **[WORKOUT: Title Here | 45 min]**")
            appendLine("4. If the user states goals, preferences, or corrections, include a memory update tag EXACTLY like this:")
            appendLine("   [MEMORY_UPDATE: goals | <their stated goal>]")
            appendLine("   [MEMORY_UPDATE: preferences | <their stated preference>]")
            appendLine("   This allows the app to remember it for future sessions. Always confirm what you've saved.")
            appendLine("5. Be encouraging but realistic. Fatigued athletes need rest, not hard sessions.")
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return
        addMessage(userText, true)

        val currentChat = chat
        if (currentChat == null) {
            addMessage("Chat not initialized. Check API key in Settings.", false)
            return
        }

        viewModelScope.launch {
            _isThinking.value = true
            try {
                val response = currentChat.sendMessage(userText)
                val responseText = response.text ?: "Received empty response from AI."
                addMessage(responseText, false)

                // Parse and persist any memory updates from the response
                processMemoryUpdates(responseText)

                // Save the updated conversation history
                persistCurrentHistory()

                // Automatically generate template and open Builder if a workout was recommended
                val workoutRegex = Regex("\\*\\*\\[WORKOUT: (.*?) \\| (\\d+) min\\]\\*\\*")
                val match = workoutRegex.find(responseText)
                if (match != null) {
                    val title = match.groupValues[1]
                    val duration = match.groupValues[2].toIntOrNull() ?: 15
                    generateTemplateFromRecommendation(title, duration, responseText)
                }

            } catch (e: Exception) {
                addMessage("Error: ${e.message}", false)
            } finally {
                _isThinking.value = false
            }
        }
    }

    private suspend fun processMemoryUpdates(responseText: String) {
        val memoryRegex = Regex("\\[MEMORY_UPDATE:\\s*(\\w+)\\s*\\|\\s*(.+?)\\]")
        val matches = memoryRegex.findAll(responseText)
        var current = _memory.value
        var updated = false
        matches.forEach { match ->
            val field = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            current = memoryManager.mergeUpdate(field, value, current)
            updated = true
        }
        if (updated) {
            memoryManager.updateMemory(current)
            _memory.value = current
        }
    }

    private suspend fun persistCurrentHistory() {
        val toSave = _messages.value
            .filter { it.text != SESSION_DIVIDER.text } // Don't persist the divider
            .map { PersistedMessage(it.text, it.isUser, it.timestamp) }
        historyManager.saveHistory(toSave)
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyManager.clearHistory()
            _messages.value = emptyList()
            chat = null
            initializeChat()
        }
    }

    fun clearMemory() {
        viewModelScope.launch {
            memoryManager.clearMemory()
            _memory.value = CoachMemory()
        }
    }

    fun generateTemplateFromRecommendation(title: String, duration: Int, recommendationContext: String = "") {
        viewModelScope.launch {
            _isThinking.value = true
            try {
                val flowKey = mainViewModel.geminiApiKey.value.trim()
                val apiKey = if (flowKey.isNotEmpty()) flowKey else BuildConfig.GEMINI_API_KEY.trim()
                if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                    addMessage("Please add your Gemini API Key in Settings.", false)
                    return@launch
                }

                val allExercises = exerciseRepository.getAllExercises().first()
                val exList = allExercises.joinToString("\n") { "[id: ${it.id}] ${it.name} (${it.area}, ${it.equipment})" }

                val cleanContext = recommendationContext
                    .replace(Regex("\\[MEMORY_UPDATE:[^\\]]*\\]"), "")
                    .replace(Regex("\\[WORKOUT:[^\\]]*\\]"), "")
                    .trim()

                val isFlow = title.contains("Yoga", ignoreCase = true) || title.contains("Stretch", ignoreCase = true) || 
                             title.contains("Flow", ignoreCase = true) || title.contains("Mobility", ignoreCase = true) ||
                             cleanContext.contains("Yoga", ignoreCase = true) || cleanContext.contains("Stretch", ignoreCase = true)
                val category = if (isFlow) "flow" else "gym"

                val generativeModel = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey,
                    generationConfig = com.google.ai.client.generativeai.type.generationConfig {
                        responseMimeType = "application/json"
                    }
                )

                val prompt = """
                    You are converting an AI coach's recommended workout into a structured JSON template for an Android app.
                    
                    The coach provided this exact workout recommendation in the chat message:
                    --------------------------------------------------
                    $cleanContext
                    --------------------------------------------------
                    
                    Workout Title: "$title"
                    Target Duration: $duration minutes.
                    
                    Known Exercise Library:
                    $exList
                    
                    CRITICAL INSTRUCTIONS:
                    1. You MUST extract and use the EXACT exercises, exercise names, sets, work/hold durations, and rest times specified in the coach's recommendation above.
                    2. Do NOT replace them with different exercises or invent a random new routine.
                    3. Match each exercise to an existing ID from the "Known Exercise Library" above if a matching exercise exists.
                    4. If an exercise recommended by the coach is NOT in the Known Exercise Library, set "id": "NEW", and specify its name, area, and equipment.
                    
                    Return a JSON object with exactly these keys:
                    - "workoutName": (string) "$title"
                    - "description": (string) a concise, engaging summary matching the coach's recommendation.
                    - "rounds": (int) number of circuit rounds (default 1 if sets per exercise are specified).
                    - "exercises": a JSON array of objects, each representing an exercise in the workout.
                    
                    Each object in the "exercises" array must have exactly these keys:
                    - "id": (string) the ID of the matched exercise from the Known Exercise Library above, or "NEW".
                    - "name": (string) the exercise name matching the coach recommendation.
                    - "area": (string) primary muscle group or target area.
                    - "equipment": (string) required equipment (e.g., Dumbbell, Mat, Bodyweight, Barbell).
                    - "sets": (int) number of sets specified in recommendation.
                    - "workSec": (int) work duration in seconds per set (e.g. 30 for 30s work/hold, or ~30 for 10-12 reps).
                    - "restSec": (int) rest duration in seconds per set (default 30 if not specified).
                    
                    Return ONLY the valid JSON object.
                """.trimIndent()

                val response = generativeModel.generateContent(prompt)
                var text = response.text ?: "{}"

                val startIndex = text.indexOf('{')
                val endIndex = text.lastIndexOf('}')
                if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                    text = text.substring(startIndex, endIndex + 1)
                }

                val jsonObj = JSONObject(text)
                val workoutId = UUID.randomUUID().toString()

                val workout = Workout(
                    id = workoutId,
                    name = jsonObj.optString("workoutName", title),
                    category = category,
                    description = jsonObj.optString("description", "Coach recommended workout"),
                    rounds = jsonObj.optInt("rounds", 1)
                )

                val stepList = mutableListOf<Step>()
                val newExercises = mutableListOf<Exercise>()

                if (jsonObj.has("exercises")) {
                    val arr = jsonObj.getJSONArray("exercises")
                    for (i in 0 until arr.length()) {
                        val exObj = arr.getJSONObject(i)
                        var exId = exObj.optString("id", "NEW")
                        val exName = exObj.optString("name", "Unknown Exercise")

                        if (exId == "NEW" || exId.isBlank() || exId == "null") {
                            exId = UUID.randomUUID().toString()
                            newExercises.add(
                                Exercise(
                                    id = exId,
                                    name = exName,
                                    area = exObj.optString("area", "Full Body"),
                                    category = category,
                                    equipment = exObj.optString("equipment", "Bodyweight"),
                                    isCustom = true
                                )
                            )
                        }

                        stepList.add(
                            Step(
                                id = UUID.randomUUID().toString(),
                                workoutId = workoutId,
                                exerciseId = exId,
                                exerciseName = exName,
                                sortOrder = i,
                                sets = exObj.optInt("sets", 1),
                                workSec = exObj.optInt("workSec", 30),
                                restSec = exObj.optInt("restSec", 30)
                            )
                        )
                    }
                }

                if (newExercises.isNotEmpty()) {
                    newExercises.forEach { exerciseRepository.addCustomExercise(it) }
                }
                workoutRepository.saveWorkout(workout, stepList)

                addMessage("Template created! Redirecting to Builder...", false)
                _navigationEvent.emit(workoutId)

            } catch (e: Exception) {
                addMessage("Error creating template: ${e.message}", false)
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun createTemplateFromRoutine(
        title: String,
        durationMin: Int,
        exercises: List<String>,
        isFlow: Boolean
    ) {
        viewModelScope.launch {
            _isThinking.value = true
            val workoutId = UUID.randomUUID().toString()
            val category = if (isFlow || title.contains("Yoga", ignoreCase = true) || title.contains("Stretch", ignoreCase = true) || title.contains("Flow", ignoreCase = true)) "flow" else "gym"
            try {
                val contextText = "Routine: $title\nDuration: $durationMin min\nExercises:\n" +
                        exercises.joinToString("\n") { "- $it" }

                val flowKey = mainViewModel.geminiApiKey.value.trim()
                val apiKey = if (flowKey.isNotEmpty()) flowKey else BuildConfig.GEMINI_API_KEY.trim()
                if (apiKey.isNotBlank() && apiKey != "YOUR_API_KEY_HERE") {
                    val allExercises = exerciseRepository.getAllExercises().first()
                    val exList = allExercises.joinToString("\n") { "[id: ${it.id}] ${it.name} (${it.area}, ${it.equipment})" }

                    val generativeModel = GenerativeModel(
                        modelName = "gemini-2.5-flash",
                        apiKey = apiKey,
                        generationConfig = com.google.ai.client.generativeai.type.generationConfig {
                            responseMimeType = "application/json"
                        }
                    )

                    val prompt = """
                        You are converting a recommended fitness routine into a structured JSON template for an Android app.
                        
                        Routine Title: "$title"
                        Duration: $durationMin minutes.
                        Exercises:
                        ${exercises.joinToString("\n") { "- $it" }}
                        
                        Known Exercise Library:
                        $exList
                        
                        CRITICAL: You MUST use the exact exercises provided above. Match each exercise to an existing ID from the Known Exercise Library if possible, or set "id": "NEW".
                        
                        Return a JSON object with:
                        - "workoutName": "$title"
                        - "description": "Recommended routine ($durationMin min)"
                        - "rounds": 1
                        - "exercises": array of objects with: "id", "name", "area", "equipment", "sets", "workSec", "restSec".
                    """.trimIndent()

                    val response = generativeModel.generateContent(prompt)
                    var text = response.text ?: "{}"
                    val sIdx = text.indexOf('{')
                    val eIdx = text.lastIndexOf('}')
                    if (sIdx != -1 && eIdx != -1 && eIdx > sIdx) text = text.substring(sIdx, eIdx + 1)
                    val jsonObj = JSONObject(text)

                    val workout = Workout(
                        id = workoutId,
                        name = jsonObj.optString("workoutName", title),
                        category = category,
                        description = jsonObj.optString("description", "Recommended routine"),
                        rounds = jsonObj.optInt("rounds", 1)
                    )

                    val stepList = mutableListOf<Step>()
                    val newExercises = mutableListOf<Exercise>()

                    if (jsonObj.has("exercises")) {
                        val arr = jsonObj.getJSONArray("exercises")
                        for (i in 0 until arr.length()) {
                            val exObj = arr.getJSONObject(i)
                            var exId = exObj.optString("id", "NEW")
                            val exName = exObj.optString("name", "Unknown Exercise")

                            if (exId == "NEW" || exId.isBlank() || exId == "null") {
                                exId = UUID.randomUUID().toString()
                                newExercises.add(
                                    Exercise(
                                        id = exId,
                                        name = exName,
                                        area = exObj.optString("area", "Full Body"),
                                        category = category,
                                        equipment = exObj.optString("equipment", "Bodyweight"),
                                        isCustom = true
                                    )
                                )
                            }

                            stepList.add(
                                Step(
                                    id = UUID.randomUUID().toString(),
                                    workoutId = workoutId,
                                    exerciseId = exId,
                                    exerciseName = exName,
                                    sortOrder = i,
                                    sets = exObj.optInt("sets", 1),
                                    workSec = exObj.optInt("workSec", 30),
                                    restSec = exObj.optInt("restSec", 30)
                                )
                            )
                        }
                    }

                    if (newExercises.isNotEmpty()) {
                        newExercises.forEach { exerciseRepository.addCustomExercise(it) }
                    }
                    workoutRepository.saveWorkout(workout, stepList)
                } else {
                    buildWorkoutFromLocalExercises(workoutId, title, durationMin, exercises, category)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                buildWorkoutFromLocalExercises(workoutId, title, durationMin, exercises, category)
            } finally {
                _isThinking.value = false
                _navigationEvent.emit(workoutId)
            }
        }
    }

    private suspend fun buildWorkoutFromLocalExercises(
        workoutId: String,
        title: String,
        durationMin: Int,
        exercises: List<String>,
        category: String
    ) {
        val allExercises = exerciseRepository.getAllExercises().first()
        val stepList = mutableListOf<Step>()
        val newExercises = mutableListOf<Exercise>()

        exercises.forEachIndexed { i, line ->
            val parenIndex = line.indexOf('(')
            val exName = if (parenIndex != -1) line.substring(0, parenIndex).trim() else line.trim()
            val meta = if (parenIndex != -1) line.substring(parenIndex) else ""

            val matchedEx = allExercises.find { it.name.equals(exName, ignoreCase = true) }
            val exId = if (matchedEx != null) {
                matchedEx.id
            } else {
                val newId = UUID.randomUUID().toString()
                newExercises.add(
                    Exercise(
                        id = newId,
                        name = exName,
                        area = "Full Body",
                        category = category,
                        equipment = if (meta.contains("Dumbbell", ignoreCase = true)) "Dumbbell" else if (meta.contains("Band", ignoreCase = true)) "Resistance Band" else "Bodyweight",
                        isCustom = true
                    )
                )
                newId
            }

            val setsMatch = Regex("(\\d+)\\s*sets", RegexOption.IGNORE_CASE).find(meta)
            val sets = setsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 3

            val secMatch = Regex("(\\d+)\\s*s\\b", RegexOption.IGNORE_CASE).find(meta)
            val minMatch = Regex("(\\d+)\\s*min\\b", RegexOption.IGNORE_CASE).find(meta)
            val workSec = when {
                secMatch != null -> secMatch.groupValues[1].toIntOrNull() ?: 30
                minMatch != null -> (minMatch.groupValues[1].toIntOrNull() ?: 1) * 60
                else -> 30
            }

            val hasSides = meta.contains("side", ignoreCase = true) || meta.contains("leg", ignoreCase = true)

            stepList.add(
                Step(
                    id = UUID.randomUUID().toString(),
                    workoutId = workoutId,
                    exerciseId = exId,
                    exerciseName = exName,
                    sortOrder = i,
                    sets = sets,
                    workSec = workSec,
                    restSec = 30,
                    sides = hasSides
                )
            )
        }

        if (newExercises.isNotEmpty()) {
            newExercises.forEach { exerciseRepository.addCustomExercise(it) }
        }
        val workout = Workout(
            id = workoutId,
            name = title,
            category = category,
            description = "Recommended workout ($durationMin min)",
            rounds = 1
        )
        workoutRepository.saveWorkout(workout, stepList)
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val currentList = _messages.value.toMutableList()
        currentList.add(ChatMessage(text, isUser, System.currentTimeMillis()))
        _messages.value = currentList
    }
}

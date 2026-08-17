package com.example.shift.ui

import android.content.Context
import androidx.lifecycle.ViewModel


import androidx.lifecycle.viewModelScope
import com.example.shift.data.Activity
import com.example.shift.data.ActivityMap
import com.example.shift.data.Wellness
import com.example.shift.data.ApiClient
import com.example.shift.data.CourseManager
import com.example.shift.data.IntervalsApi
import com.example.shift.data.IntervalsEventRequest
import com.example.shift.data.SettingsManager
import com.example.shift.data.TargetsManager
import com.example.shift.data.WeeklyTargets
import com.example.shift.data.MatchCacheManager
import com.example.shift.data.SegmentScanner
import com.example.shift.data.CloudSyncManager
import com.example.shift.data.CourseMatch
import com.example.shift.data.GpxExporter
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

data class RecommendedRoutine(
    val title: String,
    val durationMin: Int,
    val targetArea: String,
    val exercises: List<String>
)

data class GymRecommendation(
    val title: String,
    val categoryTag: String,
    val isFlowType: Boolean = false,
    val badgeColorHex: String,
    val reason: String,
    val recommendedRoutines: List<RecommendedRoutine>,
    val promptForAi: String
)

data class UserVitals(
    val avgSleepHours: Double,
    val avgRestingHR: Double?,
    val avgHRV: Double?,
    val feelScore: Int
)

class MainViewModel(
    private val application: android.app.Application,
    private val settingsManager: SettingsManager,
    private val matchCacheManager: MatchCacheManager,
    private val courseManager: CourseManager,
    private val cloudSyncManager: CloudSyncManager,
    private val targetsManager: TargetsManager = TargetsManager(application)
) : ViewModel() {

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _athleteId = MutableStateFlow("")
    val athleteId: StateFlow<String> = _athleteId.asStateFlow()

    val orsApiKey: StateFlow<String> = settingsManager.orsApiKeyFlow.map { it ?: "" }.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val firebaseUrl: StateFlow<String> = settingsManager.firebaseUrlFlow.map { it ?: "" }.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val geminiApiKey: StateFlow<String> = settingsManager.geminiApiKeyFlow.map { it ?: "" }.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val autoOpenSegmentPage: StateFlow<Boolean> = settingsManager.autoOpenSegmentPageFlow.stateIn(viewModelScope, SharingStarted.Lazily, true)

    val endRideFanfare: StateFlow<String> = settingsManager.endRideFanfareFlow.stateIn(viewModelScope, SharingStarted.Lazily, "")

    fun saveEndRideFanfare(pattern: String) {
        viewModelScope.launch {
            settingsManager.saveEndRideFanfare(pattern.trim())
            // Push straight away — the melody is edited on the phone but played
            // by the Karoo, so a save that stays local silently does nothing.
            cloudSyncManager.fullSync()
        }
    }

    val showGhostArrows: StateFlow<Boolean> = settingsManager.showGhostArrowsFlow.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val showSegmentLine: StateFlow<Boolean> = settingsManager.showSegmentLineFlow.stateIn(viewModelScope, SharingStarted.Lazily, true)

    fun setShowGhostArrows(enabled: Boolean) {
        viewModelScope.launch { settingsManager.saveShowGhostArrows(enabled) }
    }

    fun setShowSegmentLine(enabled: Boolean) {
        viewModelScope.launch { settingsManager.saveShowSegmentLine(enabled) }
    }

    fun setAutoOpenSegmentPage(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.saveAutoOpenSegmentPage(enabled)
        }
    }


    private val _apiActivities = MutableStateFlow<List<Activity>>(emptyList())
    
    val activities: StateFlow<List<Activity>> = _apiActivities.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _segmentCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val segmentCounts: StateFlow<Map<String, Int>> = _segmentCounts.asStateFlow()

    private val _polylines = MutableStateFlow<Map<String, String>>(emptyMap())
    val polylines: StateFlow<Map<String, String>> = _polylines.asStateFlow()

    private val _latestWellness = MutableStateFlow<Wellness?>(null)
    val latestWellness: StateFlow<Wellness?> = _latestWellness.asStateFlow()

    private val _wellnessHistory = MutableStateFlow<List<Wellness>>(emptyList())
    val wellnessHistory: StateFlow<List<Wellness>> = _wellnessHistory.asStateFlow()

    private val _userVitals = MutableStateFlow<UserVitals?>(null)
    val userVitals: StateFlow<UserVitals?> = _userVitals.asStateFlow()

    private val _hasRoutePermission = MutableStateFlow(false)
    val hasRoutePermission: StateFlow<Boolean> = _hasRoutePermission.asStateFlow()

    val weeklyTargets: StateFlow<WeeklyTargets> = targetsManager.targetsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, WeeklyTargets())

    private val _hubBriefing = MutableStateFlow<String?>(null)
    val hubBriefing: StateFlow<String?> = _hubBriefing.asStateFlow()

    private val _isHubBriefingLoading = MutableStateFlow(false)
    val isHubBriefingLoading: StateFlow<Boolean> = _isHubBriefingLoading.asStateFlow()

    // ── Gym & Recovery State ──────────────────────────────────────────────
    private val doneDao by lazy {
        try {
            (application as com.example.shift.ShiftApplication).database.doneDao()
        } catch (e: Exception) {
            null
        }
    }

    val doneLogs: StateFlow<List<com.example.shift.data.model.DoneLog>> = flow {
        if (doneDao != null) {
            emitAll(doneDao!!.getAllFlow())
        } else {
            emit(emptyList())
        }
    }.catch { emit(emptyList()) }
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val strengthSessionsThisWeek: StateFlow<Int> = combine(doneLogs, activities) { logs, acts ->
        val now = LocalDate.now()
        val startOfWeek = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val doneCount = logs.filter { it.category == "gym" }.mapNotNull { try { LocalDate.parse(it.dateKey) } catch (e: Exception) { null } }.filter { !it.isBefore(startOfWeek) }.toSet()
        val actCount = acts.filter { it.type == "WeightTraining" || it.id.startsWith("gym_") }.mapNotNull { try { LocalDate.parse(it.start_date_local.take(10)) } catch (e: Exception) { null } }.filter { !it.isBefore(startOfWeek) }.toSet()
        (doneCount + actCount).size
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val flowSessionsThisWeek: StateFlow<Int> = combine(doneLogs, activities) { logs, acts ->
        val now = LocalDate.now()
        val startOfWeek = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val doneCount = logs.filter { it.category == "flow" }.mapNotNull { try { LocalDate.parse(it.dateKey) } catch (e: Exception) { null } }.filter { !it.isBefore(startOfWeek) }.toSet()
        val actCount = acts.filter { it.type == "Yoga" }.mapNotNull { try { LocalDate.parse(it.start_date_local.take(10)) } catch (e: Exception) { null } }.filter { !it.isBefore(startOfWeek) }.toSet()
        (doneCount + actCount).size
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val gymSessionsThisWeek: StateFlow<Int> = combine(strengthSessionsThisWeek, flowSessionsThisWeek) { s, f ->
        s + f
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val lastGymSessionText: StateFlow<String> = combine(doneLogs, activities) { logs, acts ->
        val doneDates = logs.mapNotNull { try { LocalDate.parse(it.dateKey) } catch (e: Exception) { null } }
        val actDates = acts.filter { it.type == "WeightTraining" || it.type == "Workout" || it.type == "Yoga" || it.id.startsWith("gym_") }
            .mapNotNull { try { LocalDate.parse(it.start_date_local.take(10)) } catch (e: Exception) { null } }

        val allDates = (doneDates + actDates).sortedDescending()
        val latest = allDates.firstOrNull()
        if (latest == null) {
            "None"
        } else {
            val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(latest, LocalDate.now())
            when (daysAgo) {
                0L -> "Today"
                1L -> "Yesterday"
                else -> "$daysAgo days ago"
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, "None")

    val gymRecommendation: StateFlow<GymRecommendation> = combine(
        activities,
        _userVitals,
        _latestWellness
    ) { acts, vitals, wellness ->
        val now = LocalDate.now()
        val todayStr = now.toString()
        val yesterdayStr = now.minusDays(1).toString()

        val recentRide = acts.firstOrNull { act ->
            (act.type == "Ride" || act.type == "VirtualRide" || act.type == "E-BikeRide") &&
            (act.start_date_local.startsWith(todayStr) || act.start_date_local.startsWith(yesterdayStr))
        }
        val recentRun = acts.firstOrNull { act ->
            (act.type == "Run" || act.type == "VirtualRun") &&
            (act.start_date_local.startsWith(todayStr) || act.start_date_local.startsWith(yesterdayStr))
        }

        val feelScore = vitals?.feelScore ?: 50
        val ctl = wellness?.ctl ?: acts.firstOrNull()?.icu_ctl ?: 0.0
        val atl = wellness?.atl ?: acts.firstOrNull()?.icu_atl ?: 0.0
        val tsb = ctl - atl

        when {
            recentRide != null -> {
                val distMiles = (recentRide.distance ?: 0.0) * 0.000621371
                val isToday = recentRide.start_date_local.startsWith(todayStr)
                val whenText = if (isToday) "today" else "yesterday"
                GymRecommendation(
                    title = "Post-Cycling Hip & Leg Release",
                    categoryTag = "POST-RIDE STRETCH",
                    isFlowType = true,
                    badgeColorHex = "#81C784",
                    reason = "You completed a Ride ($whenText, ${String.format("%.1f", distMiles)} mi). Cycling causes prolonged hip flexion and tight quads, glutes & lower back.",
                    recommendedRoutines = listOf(
                        RecommendedRoutine(
                            title = "Cycling Hip Flexor & Quad Relief",
                            durationMin = 12,
                            targetArea = "Quads, Hips & Glutes",
                            exercises = listOf(
                                "Kneeling Hip Flexor Drive (45s per side)",
                                "Couch Stretch for Tight Quads (60s per side)",
                                "Pigeon Pose for Glutes (60s per side)",
                                "Butterfly Stretch for Inner Thighs (45s)"
                            )
                        ),
                        RecommendedRoutine(
                            title = "Spine & Lower Back Decompression",
                            durationMin = 8,
                            targetArea = "Lower Back & Spine",
                            exercises = listOf(
                                "Cat-Cow Flow (10 slow reps)",
                                "Child's Pose with Arm Reach (60s)",
                                "Thread the Needle for Thoracic Spine (45s per side)",
                                "Supine Spinal Twist (60s per side)"
                            )
                        )
                    ),
                    promptForAi = "Create a 15-minute post-cycling stretch and mobility routine focusing on opening up tight hip flexors, quads, glutes, and lower back decompression."
                )
            }
            recentRun != null -> {
                val distMiles = (recentRun.distance ?: 0.0) * 0.000621371
                val isToday = recentRun.start_date_local.startsWith(todayStr)
                val whenText = if (isToday) "today" else "yesterday"
                GymRecommendation(
                    title = "Post-Run Calf & Hamstring Care",
                    categoryTag = "POST-RUN STRETCH",
                    isFlowType = true,
                    badgeColorHex = "#81C784",
                    reason = "You completed a Run ($whenText, ${String.format("%.1f", distMiles)} mi). Running impact stresses calves, Achilles, IT bands, and hamstrings.",
                    recommendedRoutines = listOf(
                        RecommendedRoutine(
                            title = "Calf, Achilles & Ankle Release",
                            durationMin = 10,
                            targetArea = "Calves & Achilles",
                            exercises = listOf(
                                "Standing Gastrocnemius Wall Lean (60s per leg)",
                                "Bent-Knee Soleus Wall Stretch (60s per leg)",
                                "Downward Dog Calf Pedal (10 reps per side)",
                                "Ankle Mobility Circles (15 reps each way)"
                            )
                        ),
                        RecommendedRoutine(
                            title = "Hamstring & IT Band Mobilizer",
                            durationMin = 12,
                            targetArea = "Hamstrings, IT Band & Hips",
                            exercises = listOf(
                                "Standing Single-Leg Hamstring Stretch (45s per leg)",
                                "Crossover IT Band Wall Lean (60s per side)",
                                "Runner's Lunge with Overhead Reach (45s per side)",
                                "Seated Forward Fold (60s)"
                            )
                        )
                    ),
                    promptForAi = "Create a 15-minute post-run mobility and recovery routine targeting calves, Achilles tendons, IT bands, and hamstrings."
                )
            }
            feelScore < 40 || tsb < -12.0 -> {
                GymRecommendation(
                    title = "Low-Intensity Recovery & Foam Roll",
                    categoryTag = "LIGHT RECOVERY",
                    isFlowType = true,
                    badgeColorHex = "#81C784",
                    reason = "Elevated fatigue detected (Feel Score: $feelScore, Form: ${String.format("%+.0f", tsb)}). A light mobility flow will speed recovery without adding training stress.",
                    recommendedRoutines = listOf(
                        RecommendedRoutine(
                            title = "Full Body Gentle Flow",
                            durationMin = 15,
                            targetArea = "Full Body Mobility",
                            exercises = listOf(
                                "World's Greatest Stretch (5 reps per side)",
                                "90/90 Hip Switches (8 reps per side)",
                                "Thoracic Spine Openers (10 reps)",
                                "Deep Diaphragmatic Breathwork Child's Pose (2 mins)"
                            )
                        ),
                        RecommendedRoutine(
                            title = "Foam Rolling & Myofascial Release",
                            durationMin = 15,
                            targetArea = "Full Body Foam Roll",
                            exercises = listOf(
                                "Quad & IT Band Roll (90s per leg)",
                                "Thoracic Upper Back Roll (90s)",
                                "Glute & Piriformis Roll (90s per side)",
                                "Lat & Shoulder Blade Roll (60s per side)"
                            )
                        )
                    ),
                    promptForAi = "Create a 15-minute active recovery flow with zero heavy loading, focusing on breathwork, foam rolling, and gentle joint mobility."
                )
            }
            feelScore >= 70 && tsb >= -5.0 -> {
                GymRecommendation(
                    title = "Hypertrophy & Strength Session",
                    categoryTag = "STRENGTH WORK",
                    isFlowType = false,
                    badgeColorHex = "#CE93D8",
                    reason = "Fresh & well-recovered (Feel Score: $feelScore, Form: ${String.format("%+.0f", tsb)})! Prime condition for progressive overload and muscle building.",
                    recommendedRoutines = listOf(
                        RecommendedRoutine(
                            title = "Upper Body Push & Pull",
                            durationMin = 35,
                            targetArea = "Chest, Back, Shoulders & Arms",
                            exercises = listOf(
                                "Dumbbell Bench Press (4 sets x 8-10 reps)",
                                "Bent-Over Dumbbell Rows (4 sets x 10 reps)",
                                "Seated Dumbbell Overhead Press (3 sets x 10 reps)",
                                "Dumbbell Bicep Curls to Tricep Extensions Superset (3 sets x 12 reps)"
                            )
                        ),
                        RecommendedRoutine(
                            title = "Lower Body Power & Core",
                            durationMin = 35,
                            targetArea = "Quads, Glutes, Hamstrings & Core",
                            exercises = listOf(
                                "Goblet Squats (4 sets x 10 reps)",
                                "Dumbbell Romanian Deadlifts (4 sets x 10 reps)",
                                "Walking Dumbbell Lunges (3 sets x 12 steps)",
                                "Hanging Knee Raises or Plank Hold (3 sets x 45s)"
                            )
                        )
                    ),
                    promptForAi = "Create a 35-minute full body dumbbell strength workout with 4 exercises focusing on progressive strength and hypertrophy."
                )
            }
            else -> {
                GymRecommendation(
                    title = "Functional Core & Postural Stability",
                    categoryTag = "STRENGTH & CORE",
                    isFlowType = false,
                    badgeColorHex = "#CE93D8",
                    reason = "Balanced training state (Feel Score: $feelScore). Strengthening core and postural stabilizers improves cycling power and running efficiency.",
                    recommendedRoutines = listOf(
                        RecommendedRoutine(
                            title = "Anti-Rotation Core Stability",
                            durationMin = 20,
                            targetArea = "Deep Abs, Obliques & Lower Back",
                            exercises = listOf(
                                "Pallof Press Hold (3 sets x 30s per side)",
                                "Deadbug with Dumbbell Reach (3 sets x 12 reps)",
                                "Side Plank with Hip Dip (3 sets x 10 reps per side)",
                                "Farmer's Carry Hold (3 sets x 45s)"
                            )
                        ),
                        RecommendedRoutine(
                            title = "Postural & Shoulder Reset",
                            durationMin = 15,
                            targetArea = "Upper Back & Rear Shoulders",
                            exercises = listOf(
                                "Band/Dumbbell Pull-Aparts (3 sets x 15 reps)",
                                "Face Pulls or Y-T-W Raises (3 sets x 12 reps)",
                                "Wall Slides for Scapular Control (3 sets x 10 reps)",
                                "Bird-Dog Holds (3 sets x 30s per side)"
                            )
                        )
                    ),
                    promptForAi = "Create a 20-minute core stability and postural alignment gym workout."
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, GymRecommendation(
        title = "Functional Core & Postural Stability",
        categoryTag = "STRENGTH & CORE",
        isFlowType = false,
        badgeColorHex = "#C084FC",
        reason = "Core stability supports all athletic performance.",
        recommendedRoutines = emptyList(),
        promptForAi = "Create a 20-minute core workout."
    ))

    private var api: IntervalsApi? = null

    init {
        viewModelScope.launch {
            val key = settingsManager.apiKeyFlow.firstOrNull() ?: ""
            val id = settingsManager.athleteIdFlow.firstOrNull() ?: ""
            _apiKey.value = key
            _athleteId.value = id
            
            if (key.isNotEmpty() && id.isNotEmpty()) {
                api = ApiClient.create(key)
            }
            fetchActivities()
            // Read the settings flow directly: the firebaseUrl StateFlow is
            // lazily started and its .value is still "" this early in init.
            val fbUrl = settingsManager.firebaseUrlFlow.firstOrNull() ?: ""
            if (fbUrl.isNotEmpty()) {
                // Pull-merge-push — never a blind push, which used to let a
                // stale device overwrite the cloud on every app launch.
                syncNow()
            }
        }
    }

    fun updateFirebaseUrl(url: String) {
        viewModelScope.launch {
            settingsManager.saveFirebaseUrl(url)
        }
    }

    fun updateGeminiApiKey(key: String) {
        viewModelScope.launch {
            settingsManager.saveGeminiApiKey(key)
        }
    }

    fun saveSettings(key: String, orsKey: String, id: String, fireUrl: String) {
        viewModelScope.launch {
            settingsManager.saveApiKey(key)
            settingsManager.saveOrsApiKey(orsKey)
            settingsManager.saveAthleteId(id)
            settingsManager.saveFirebaseUrl(fireUrl)
            _apiKey.value = key
            _athleteId.value = id
            if (key.isNotEmpty() && id.isNotEmpty()) {
                api = ApiClient.create(key)
            } else {
                api = null
            }
            fetchActivities()
        }
    }

    fun fetchActivities() {
        val currentApi = api
        val currentAthleteId = _athleteId.value
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Fetch Intervals.icu activities concurrently
                val apiDeferred = async {
                    if (currentApi != null && currentAthleteId.isNotEmpty()) {
                        try {
                            val oldest = "2010-01-01"
                            val newest = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                            val responseActivities = currentApi.getActivities(currentAthleteId, oldest = oldest, newest = newest)
                            val responseEvents = currentApi.getEvents(currentAthleteId, oldest = oldest, newest = newest)
                            
                            val currentIsoDate = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            val pastEvents = responseEvents.filter { it.start_date_local < currentIsoDate }
                            
                            (responseActivities + pastEvents)
                                .distinctBy { it.id }
                                .sortedByDescending { it.start_date_local }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }

                // Fetch Health Connect runs concurrently
                val healthDeferred = async {
                    fetchHealthConnectRuns()
                }

                val combined = apiDeferred.await()
                val healthActivities = healthDeferred.await()

                val finalCombined = (combined + healthActivities)
                    .distinctBy { it.id }
                    .sortedByDescending { it.start_date_local }
                
                _apiActivities.value = finalCombined
                
                if (currentApi != null && currentAthleteId.isNotEmpty()) {
                    try {
                        val newest = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                        val wellnessOldest = LocalDate.now().minusDays(49).format(DateTimeFormatter.ISO_LOCAL_DATE)
                        val wellnessResponse = currentApi.getWellness(currentAthleteId, oldest = wellnessOldest, newest = newest)
                        _wellnessHistory.value = wellnessResponse.sortedBy { it.id }
                        _latestWellness.value = wellnessResponse.maxByOrNull { it.id }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                // Load segment counts for live courses only
                reloadSegmentCounts()
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }

            
            // Trigger auto-scan in the background without blocking isLoading
            scanUnscannedActivities()
            
            // Fetch vitals for AI Coach
            fetchSleepAndVitals()
            
            // Auto-sync unpushed HealthConnect runs to Intervals.icu
            pushRunsToIntervalsSilently()
        }
    }

    private fun pushRunsToIntervalsSilently() {
        pushRunsToIntervals(null)
    }

    fun pushRunsToIntervals(onComplete: ((Int, String) -> Unit)? = null) {
        val currentApi = api
        if (currentApi == null) {
            onComplete?.invoke(0, "Intervals API key missing. Please enter your API key in Settings.")
            return
        }

        val athleteIdVal = _athleteId.value.ifBlank { "0" }

        viewModelScope.launch(Dispatchers.IO) {
            var pushedCount = 0
            val errorList = mutableListOf<String>()
            try {
                val oldest = LocalDate.now().minusDays(90).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val newest = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val existingActs = try { currentApi.getActivities(athleteIdVal, oldest = oldest, newest = newest) } catch (e: Exception) { emptyList() }
                val existingEvents = try { currentApi.getEvents(athleteIdVal, oldest = oldest, newest = newest) } catch (e: Exception) { emptyList() }
                val allExisting = (existingActs + existingEvents)

                val runsToSync = _apiActivities.value.filter { 
                    it.type == "Run" || it.id.startsWith("hc_") || it.name.contains("Run", ignoreCase = true) 
                }

                if (runsToSync.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(0, "No runs found to sync. Ensure Health Connect permissions are granted.")
                    }
                    return@launch
                }

                for (run in runsToSync) {
                    val runDateStr = run.start_date_local.take(10)
                    val isAlreadyOnIntervals = allExisting.any { existing ->
                        val existingDate = existing.start_date_local.take(10)
                        val isRunType = existing.type == "Run" || existing.type == "VirtualRun"
                        val sameDate = existingDate == runDateStr
                        val sameDist = run.distance == null || existing.distance == null || kotlin.math.abs((existing.distance ?: 0.0) - run.distance) < 200.0
                        isRunType && sameDate && sameDist
                    }

                    if (!isAlreadyOnIntervals) {
                        val cleanDate = try {
                            val cleanStr = run.start_date_local.take(19).replace(" ", "T")
                            val parsed = java.time.LocalDateTime.parse(cleanStr, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            parsed.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                        } catch (e: Exception) {
                            run.start_date_local.take(19).replace(" ", "T")
                        }

                        var uploadedViaGpx = false
                        val polyline = run.map?.summary_polyline
                        if (!polyline.isNullOrBlank()) {
                            try {
                                val points = com.example.shift.utils.PolylineUtils.decodePolyline(polyline)
                                if (points.isNotEmpty()) {
                                    val gpxXml = GpxExporter.generateGpx(run.name, points)
                                    val gpxBytes = gpxXml.toByteArray(Charsets.UTF_8)
                                    val reqFile = okhttp3.RequestBody.create("application/gpx+xml".toMediaTypeOrNull(), gpxBytes)
                                    val body = okhttp3.MultipartBody.Part.createFormData("file", "${run.name.replace(" ", "_")}.gpx", reqFile)
                                    currentApi.uploadActivity(athleteIdVal, body, run.name)
                                    uploadedViaGpx = true
                                    pushedCount++
                                    android.util.Log.d("IntervalsSync", "Uploaded run '${run.name}' via GPX to Intervals.icu")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("IntervalsSync", "GPX upload failed for '${run.name}', falling back to event post", e)
                            }
                        }

                        if (!uploadedViaGpx) {
                            val durationSecs = run.moving_time ?: run.elapsed_time ?: 1800
                            val estimatedTss = run.icu_training_load?.toInt() ?: ((durationSecs / 3600f) * 65f).toInt().coerceAtLeast(5)
                            val req = IntervalsEventRequest(
                                start_date_local = cleanDate,
                                type = "Run",
                                name = run.name,
                                moving_time = durationSecs,
                                icu_training_load = estimatedTss,
                                category = "WORKOUT",
                                distance = run.distance
                            )
                            try {
                                currentApi.createEvent(athleteIdVal, req)
                                pushedCount++
                                android.util.Log.d("IntervalsSync", "Pushed run event '${run.name}' ($cleanDate) to Intervals.icu")
                            } catch (e: Exception) {
                                e.printStackTrace()
                                val errMsg = e.message ?: e.toString()
                                android.util.Log.e("IntervalsSync", "Failed to push run '${run.name}': $errMsg", e)
                                errorList.add("${run.name}: $errMsg")
                            }
                        }
                    }
                }

                if (pushedCount > 0) {
                    fetchActivities()
                }

                withContext(Dispatchers.Main) {
                    val msg = when {
                        pushedCount > 0 -> "Successfully pushed $pushedCount run(s) to Intervals.icu!"
                        errorList.isNotEmpty() -> "Upload error: ${errorList.first()}"
                        else -> "All runs are already synced to Intervals.icu."
                    }
                    onComplete?.invoke(pushedCount, msg)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(0, "Error pushing runs: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun fetchSleepAndVitals() {
        withContext(Dispatchers.IO) {
            try {
                val sdkStatus = try {
                    androidx.health.connect.client.HealthConnectClient.getSdkStatus(application)
                } catch (t: Throwable) {
                    androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE
                }
                if (sdkStatus != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                    return@withContext
                }
                val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(application)

                val granted = client.permissionController.getGrantedPermissions()
                
                val hasSleepPerm = granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.SleepSessionRecord::class))
                val hasRhrPerm = granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.RestingHeartRateRecord::class))
                val hasHrvPerm = granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord::class))

                val endTime = java.time.Instant.now()
                val startTime7Days = endTime.minus(java.time.Duration.ofDays(7))
                val startTime3Days = endTime.minus(java.time.Duration.ofDays(3))
                val timeRange7Days = androidx.health.connect.client.time.TimeRangeFilter.between(startTime7Days, endTime)
                val timeRange3Days = androidx.health.connect.client.time.TimeRangeFilter.between(startTime3Days, endTime)

                var avgSleepMins = 0.0
                if (hasSleepPerm) {
                    val sleepRequest = androidx.health.connect.client.request.ReadRecordsRequest(
                        recordType = androidx.health.connect.client.records.SleepSessionRecord::class,
                        timeRangeFilter = timeRange3Days
                    )
                    val sleepResponse = client.readRecords(sleepRequest)
                    if (sleepResponse.records.isNotEmpty()) {
                        // Group sleep records by the local date they ended
                        val zone = java.time.ZoneId.systemDefault()
                        val recordsByDate = sleepResponse.records.groupBy {
                            java.time.LocalDateTime.ofInstant(it.endTime, zone).toLocalDate()
                        }
                        
                        // Pick the most recent day and sum all sleep sessions for that day
                        val latestDate = recordsByDate.keys.maxOrNull()
                        if (latestDate != null) {
                            val lastNightRecords = recordsByDate[latestDate] ?: emptyList()
                            val totalSleepMs = lastNightRecords.sumOf { record ->
                                val awakeMs = record.stages.filter { it.stage == androidx.health.connect.client.records.SleepSessionRecord.STAGE_TYPE_AWAKE }.sumOf { stage ->
                                    java.time.Duration.between(stage.startTime, stage.endTime).toMillis()
                                }
                                java.time.Duration.between(record.startTime, record.endTime).toMillis() - awakeMs
                            }
                            avgSleepMins = totalSleepMs / 60000.0
                        }
                    }
                }
                
                var avgRhr: Double? = null
                if (hasRhrPerm) {
                    val rhrRequest = androidx.health.connect.client.request.ReadRecordsRequest(
                        recordType = androidx.health.connect.client.records.RestingHeartRateRecord::class,
                        timeRangeFilter = timeRange7Days
                    )
                    val rhrResponse = client.readRecords(rhrRequest)
                    if (rhrResponse.records.isNotEmpty()) {
                        avgRhr = rhrResponse.records.map { it.beatsPerMinute }.average()
                    }
                }

                var avgHrv: Double? = null
                if (hasHrvPerm) {
                    val hrvRequest = androidx.health.connect.client.request.ReadRecordsRequest(
                        recordType = androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = timeRange7Days
                    )
                    val hrvResponse = client.readRecords(hrvRequest)
                    if (hrvResponse.records.isNotEmpty()) {
                        avgHrv = hrvResponse.records.map { it.heartRateVariabilityMillis }.average()
                    }
                }

                val avgSleepHours = avgSleepMins / 60.0
                val sleepScore = if (avgSleepHours > 0) (avgSleepHours / 8.0 * 100.0).coerceAtMost(100.0) else 0.0
                
                var formScore = 0.0
                val wellness = _latestWellness.value
                var hasWellness = false
                if (wellness != null && wellness.ctl != null && wellness.atl != null) {
                    val tsb = wellness.ctl - wellness.atl
                    formScore = ((tsb + 20.0) / 40.0 * 100.0).coerceIn(0.0, 100.0)
                    hasWellness = true
                }
                
                val feelScore = if (hasWellness && sleepScore > 0) {
                    (sleepScore * 0.4 + formScore * 0.6).roundToInt()
                } else if (hasWellness) {
                    formScore.roundToInt()
                } else if (sleepScore > 0) {
                    sleepScore.roundToInt()
                } else {
                    50 // default unknown
                }

                _userVitals.value = UserVitals(
                    avgSleepHours = avgSleepHours,
                    avgRestingHR = avgRhr,
                    avgHRV = avgHrv,
                    feelScore = feelScore
                )
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error fetching vitals", e)
            }
        }
    }
    
    private suspend fun fetchHealthConnectRuns(): List<Activity> {
        return withContext(Dispatchers.IO) {
            val activitiesList = mutableListOf<Activity>()
            try {
                val sdkStatus = try {
                    androidx.health.connect.client.HealthConnectClient.getSdkStatus(application)
                } catch (t: Throwable) {
                    androidx.health.connect.client.HealthConnectClient.SDK_UNAVAILABLE
                }
                if (sdkStatus == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                    val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(application)

                    val granted = client.permissionController.getGrantedPermissions()
                    android.util.Log.d("HealthConnect", "Granted permissions: $granted")
                    
                    val hasSessionPerm = granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.ExerciseSessionRecord::class))
                    if (hasSessionPerm) {
                        val hasDistancePerm = granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.DistanceRecord::class))
                        val hasRoutePerm = granted.contains("android.permission.health.READ_EXERCISE_ROUTES")
                        _hasRoutePermission.value = hasRoutePerm
                        
                        try {
                            val timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(
                                java.time.Instant.now().minus(java.time.Duration.ofDays(30)),
                                java.time.Instant.now()
                            )
                            val request = androidx.health.connect.client.request.ReadRecordsRequest(
                                androidx.health.connect.client.records.ExerciseSessionRecord::class,
                                timeRangeFilter = timeRangeFilter
                            )
                            val response = client.readRecords(request)
                            android.util.Log.d("HealthConnect", "Fetched ${response.records.size} exercise records")
                            for (record in response.records) {
                                android.util.Log.d("HealthConnect", "Record: type=${record.exerciseType}, title=${record.title}")
                                val exerciseType = record.exerciseType
                                val isTrackedType = exerciseType == androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
                                                    exerciseType == androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_BIKING ||
                                                    exerciseType == androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_WALKING ||
                                                    exerciseType == androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_HIKING

                                if (isTrackedType) {
                                    val activityType = when (exerciseType) {
                                        androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Ride"
                                        androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
                                        androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
                                        else -> "Run"
                                    }

                                    val startLocal = java.time.LocalDateTime.ofInstant(record.startTime, record.startZoneOffset ?: java.time.ZoneOffset.UTC)
                                    val durationSeconds = java.time.Duration.between(record.startTime, record.endTime).seconds.toInt()
                                    
                                    val timeOfDayTitle = when (startLocal.hour) {
                                        in 5..11 -> "Morning $activityType"
                                        in 12..14 -> "Lunch $activityType"
                                        in 15..17 -> "Afternoon $activityType"
                                        in 18..21 -> "Evening $activityType"
                                        else -> "Night $activityType"
                                    }
                                    val name = record.title?.takeIf { it.isNotBlank() } ?: timeOfDayTitle
                                    
                                    var distanceMeters: Double? = null
                                    var caloriesBurned: Double? = null
                                    var stepsCount: Long? = null
                                    var elevMeters: Double? = null
                                    var hrAvg: Double? = null
                                    var hrMax: Double? = null
                                    var speedAvg: Double? = null
                                    var speedMax: Double? = null

                                    val timeRange = androidx.health.connect.client.time.TimeRangeFilter.between(
                                        record.startTime.minus(java.time.Duration.ofMinutes(1)),
                                        record.endTime.plus(java.time.Duration.ofMinutes(1))
                                    )

                                    if (hasDistancePerm) {
                                        try {
                                            val distResponse = client.aggregate(androidx.health.connect.client.request.AggregateRequest(setOf(androidx.health.connect.client.records.DistanceRecord.DISTANCE_TOTAL), timeRange))
                                            distanceMeters = distResponse[androidx.health.connect.client.records.DistanceRecord.DISTANCE_TOTAL]?.inMeters
                                        } catch(e: Exception) { android.util.Log.e("HealthConnect", "Error distance", e) }
                                    }
                                    if (granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class))) {
                                        try {
                                            val calsResponse = client.aggregate(androidx.health.connect.client.request.AggregateRequest(setOf(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), timeRange))
                                            caloriesBurned = calsResponse[androidx.health.connect.client.records.ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                                        } catch(e: Exception) { android.util.Log.e("HealthConnect", "Error calories", e) }
                                    }
                                    if (granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class))) {
                                        try {
                                            val stepsResponse = client.aggregate(androidx.health.connect.client.request.AggregateRequest(setOf(androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL), timeRange))
                                            stepsCount = stepsResponse[androidx.health.connect.client.records.StepsRecord.COUNT_TOTAL]
                                        } catch(e: Exception) { android.util.Log.e("HealthConnect", "Error steps", e) }
                                    }
                                    if (granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.ElevationGainedRecord::class))) {
                                        try {
                                            val elevResponse = client.aggregate(androidx.health.connect.client.request.AggregateRequest(setOf(androidx.health.connect.client.records.ElevationGainedRecord.ELEVATION_GAINED_TOTAL), timeRange))
                                            elevMeters = elevResponse[androidx.health.connect.client.records.ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters
                                        } catch(e: Exception) { android.util.Log.e("HealthConnect", "Error elevation", e) }
                                    }
                                    if (granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class))) {
                                        try {
                                            val hrResponse = client.aggregate(androidx.health.connect.client.request.AggregateRequest(setOf(androidx.health.connect.client.records.HeartRateRecord.BPM_AVG, androidx.health.connect.client.records.HeartRateRecord.BPM_MAX), timeRange))
                                            hrAvg = hrResponse[androidx.health.connect.client.records.HeartRateRecord.BPM_AVG]?.toDouble()
                                            hrMax = hrResponse[androidx.health.connect.client.records.HeartRateRecord.BPM_MAX]?.toDouble()
                                        } catch(e: Exception) { android.util.Log.e("HealthConnect", "Error HR", e) }
                                    }
                                    if (granted.contains(androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.SpeedRecord::class))) {
                                        try {
                                            val speedResponse = client.aggregate(androidx.health.connect.client.request.AggregateRequest(setOf(androidx.health.connect.client.records.SpeedRecord.SPEED_AVG, androidx.health.connect.client.records.SpeedRecord.SPEED_MAX), timeRange))
                                            speedAvg = speedResponse[androidx.health.connect.client.records.SpeedRecord.SPEED_AVG]?.inMetersPerSecond
                                            speedMax = speedResponse[androidx.health.connect.client.records.SpeedRecord.SPEED_MAX]?.inMetersPerSecond
                                        } catch(e: Exception) { android.util.Log.e("HealthConnect", "Error speed", e) }
                                    }
                                    
                                    var encodedPolyline: String? = null
                                    if (hasRoutePerm) {
                                        try {
                                            val routeResult = record.exerciseRouteResult
                                            android.util.Log.d("HealthConnect", "Route result for $name: ${routeResult::class.java.simpleName}")
                                            if (routeResult is androidx.health.connect.client.records.ExerciseRouteResult.Data) {
                                                val route = routeResult.exerciseRoute
                                                val points = route.route.map { loc ->
                                                    Pair(loc.latitude, loc.longitude)
                                                }
                                                if (points.isNotEmpty()) {
                                                    encodedPolyline = com.example.shift.utils.PolylineUtils.encodePolyline(points)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("HealthConnect", "Error fetching route", e)
                                        }
                                    }
                                    
                                    android.util.Log.d("HealthConnect", "Sync Run: $name, date=$startLocal, duration=$durationSeconds s, distance=$distanceMeters m, hasRoutePolyline=${encodedPolyline != null}")
                                    
                                    val activityMap = encodedPolyline?.let { ActivityMap(summary_polyline = it) }
                                    
                                    activitiesList.add(
                                        Activity(
                                            id = "hc_${record.metadata.id}",
                                            start_date_local = startLocal.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                            type = activityType,
                                            name = name,
                                            distance = distanceMeters,
                                            elapsed_time = durationSeconds,
                                            moving_time = durationSeconds,
                                            total_elevation_gain = elevMeters,
                                            average_speed = speedAvg,
                                            max_speed = speedMax,
                                            average_heartrate = hrAvg,
                                            max_heartrate = hrMax,
                                            calories = caloriesBurned,
                                            steps = stepsCount,
                                            map = activityMap,
                                            source = "HealthConnect"
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("HealthConnect", "Error fetching records", e)
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HealthConnect", "Error accessing SDK", e)
                e.printStackTrace()
            }
            activitiesList
        }
    }

    private val autoScanRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun scanUnscannedActivities(limit: Int = 500) {
        val currentApi = api ?: return
        // fetchActivities() runs from several places (startup, settings save, pull to
        // refresh, permission grant) and each ends here. Without this guard the passes
        // overlap, race the same unscanned set and multiply the request rate.
        if (!autoScanRunning.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val courses = courseManager.coursesFlow.first()
                if (courses.isEmpty()) return@launch

                val activitiesList = activities.value
                val activitiesToScan = activitiesList.take(limit)
                var scannedAny = false

                for (activity in activitiesToScan) {
                    var needsStream = false
                    val unscannedCourses = mutableListOf<com.example.shift.data.Course>()
                    
                    for (course in courses) {
                        val scannedIds = matchCacheManager.getScannedActivities(course.id)
                        if (!scannedIds.contains(activity.id)) {
                            needsStream = true
                            unscannedCourses.add(course)
                        }
                    }
                    
                    if (needsStream && unscannedCourses.isNotEmpty()) {
                        try {
                            com.example.shift.data.ScanLogBuffer.log("Auto-scanning activity ${activity.id} ('${activity.name}', ${activity.start_date_local}) against ${unscannedCourses.size} unscanned courses")
                            var markScanned = false
                            var abortPass = false
                            val stream: com.example.shift.data.ParsedStream = try {
                                val rawJson = currentApi.getActivityStreamsRaw(activity.id, "latlng,time,distance,watts,velocity_smooth")
                                markScanned = true
                                com.example.shift.data.ScanLogBuffer.log("Stream fetch OK for activity ${activity.id}")
                                SegmentScanner.parseStream(rawJson)
                            } catch (e: Exception) {
                                val reason = com.example.shift.data.ScanFailure.describe(e)
                                if (com.example.shift.data.ScanFailure.isTransient(e)) {
                                    abortPass = true
                                    com.example.shift.data.ScanLogBuffer.log("Pausing auto-scan at activity ${activity.id}: $reason")
                                } else {
                                    markScanned = true
                                    com.example.shift.data.ScanLogBuffer.log("No stream for activity ${activity.id} ($reason), marking scanned")
                                }
                                android.util.Log.w("MainViewModel", "Stream fetch failed for ${activity.id}: $reason")
                                com.example.shift.data.ParsedStream(null, null, null, null, null, null)
                            }
                            
                            if (abortPass) {
                                com.example.shift.data.ScanLogBuffer.log("Auto-scan paused, resumes on next refresh")
                                break
                            }

                            if (markScanned) {
                                for (course in unscannedCourses) {
                                    // Same guard as the detail-screen scanner: one bad
                                    // ride must not leave itself unmarked and loop.
                                    val matches = try {
                                        SegmentScanner.detectGates(course, activity, stream)
                                    } catch (e: Exception) {
                                        com.example.shift.data.ScanLogBuffer.log("detectGates failed for ${activity.id} on ${course.name}: ${e.message}")
                                        com.example.shift.data.CrashLogger.record(e, "auto-scan, activity ${activity.id}")
                                        emptyList()
                                    }
                                    if (matches.isNotEmpty()) {
                                        matchCacheManager.saveMatches(matches)
                                    }
                                    matchCacheManager.markActivityAsScanned(course.id, activity.id)
                                }
                                scannedAny = true
                            }

                            // Pace requests so Intervals.icu does not rate limit us into
                            // a pass where every fetch fails.
                            kotlinx.coroutines.delay(60L)
                        } catch (e: Exception) {
                            val errMsg = "Scan error for activity ${activity.id}: ${e.message}"
                            android.util.Log.w("MainViewModel", errMsg)
                            com.example.shift.data.ScanLogBuffer.log(errMsg)
                        }
                    }
                }
                // Once at the end rather than once per activity, which previously
                // launched a fresh coroutine for each of up to 500 activities.
                if (scannedAny) reloadSegmentCounts()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                autoScanRunning.set(false)
            }
        }
    }

    private val _orphanedCount = MutableStateFlow(0)
    val orphanedCount: StateFlow<Int> = _orphanedCount.asStateFlow()


    fun checkForOrphanedMatches() {
        viewModelScope.launch {
            try {
                val liveCourses = courseManager.coursesFlow.first()
                val liveCourseIds = liveCourses.map { it.id }.toSet()
                val orphaned = matchCacheManager.getOrphanedMatches(liveCourseIds)
                _orphanedCount.value = orphaned.size
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun repairOrphanedMatches(onComplete: ((Int) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val liveCourses = courseManager.coursesFlow.first()
                val liveCourseIds = liveCourses.map { it.id }.toSet()
                val deletedCount = matchCacheManager.deleteOrphanedMatches(liveCourseIds)
                _orphanedCount.value = 0
                reloadSegmentCounts()
                onComplete?.invoke(deletedCount)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun purgeDeletedRoutesAndRescan(clearScannedHistory: Boolean = false, onComplete: ((Int, Int) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val liveCourses = courseManager.coursesFlow.first()
                val liveCourseIds = liveCourses.map { it.id }.toSet()
                
                // 1. Purge deleted route matches & scanned caches
                val purgedCount = matchCacheManager.purgeDeletedRoutes(liveCourseIds)
                
                // 2. Repair stray matches for live courses
                val reHomedCount = matchCacheManager.repairStrayMatches(liveCourses)
                
                // 3. Clear scanned history if full rescan requested
                if (clearScannedHistory) {
                    matchCacheManager.clearAllScannedCache()
                }
                
                // 4. Reload segment counts & trigger deep rescan
                reloadSegmentCounts()
                scanUnscannedActivities(limit = 500)
                
                onComplete?.invoke(purgedCount, reHomedCount)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete?.invoke(0, 0)
            }
        }
    }

    fun clearEntireCacheAndRescan(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                matchCacheManager.clearEntireCache()
                _segmentCounts.value = emptyMap()
                _orphanedCount.value = 0
                com.example.shift.data.ScanLogBuffer.clear()
                
                scanUnscannedActivities(limit = 500)
                onComplete?.invoke()
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete?.invoke()
            }
        }
    }



    /**
     * How one segment effort inside a ride placed against every other attempt at
     * that segment.
     */
    data class SegmentResult(
        val courseId: String,
        val courseName: String,
        val timeSeconds: Int,
        val position: Int,
        val fieldSize: Int,
        val isPr: Boolean,
        val estimated: Boolean,
        val deltaToPrSeconds: Int
    )

    /**
     * Results for every segment matched inside one ride, fastest effort first.
     *
     * Placing is worked out against the same candidate set the PR logic uses: real
     * timings only, unless a segment has nothing but estimated ones. Mixing them
     * would let an estimate outrank a genuine effort.
     */
    suspend fun segmentResultsFor(activityId: String): List<SegmentResult> = try {
        val courses = courseManager.coursesFlow.first()
        val namesById = courses.associate { it.id to it.name }
        val allMatches = matchCacheManager.getAllMatches()

        allMatches
            .filter { it.activityId == activityId && namesById.containsKey(it.courseId) }
            .map { match ->
                val onThisCourse = allMatches.filter { it.courseId == match.courseId }
                val real = onThisCourse.filter { !it.estimatedTime }
                val candidates = if (real.isNotEmpty()) real else onThisCourse

                val best = candidates.minOfOrNull { it.timeSeconds } ?: match.timeSeconds
                val faster = candidates.count { it.timeSeconds < match.timeSeconds }

                SegmentResult(
                    courseId = match.courseId,
                    courseName = namesById[match.courseId] ?: "Segment",
                    timeSeconds = match.timeSeconds,
                    position = faster + 1,
                    fieldSize = candidates.size,
                    isPr = match.timeSeconds <= best && !match.estimatedTime,
                    estimated = match.estimatedTime,
                    deltaToPrSeconds = match.timeSeconds - best
                )
            }
            .sortedBy { it.timeSeconds }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    /**
     * The date each still-standing segment best was set on, one entry per segment.
     *
     * This is what the Hub counts as a PR for a period: a best that is both current
     * and was set inside the window. A time since beaten is no longer a record, so it
     * stops counting — which means the figure can fall as well as rise. Candidates are
     * picked the way [segmentResultsFor] picks them, real efforts unless a segment has
     * nothing but estimates, so an estimate cannot claim a PR over a genuine ride.
     */
    suspend fun segmentPrDates(): List<LocalDate> = try {
        val liveCourseIds = courseManager.coursesFlow.first().map { it.id }.toSet()
        matchCacheManager.getAllMatches()
            .filter { liveCourseIds.contains(it.courseId) }
            .groupBy { it.courseId }
            .values
            .mapNotNull { effortsOnCourse ->
                val real = effortsOnCourse.filter { !it.estimatedTime }
                val candidates = if (real.isNotEmpty()) real else effortsOnCourse
                candidates.minByOrNull { it.timeSeconds }
                    ?.let { best -> com.example.shift.data.HubStats.dateOf(best.date) }
            }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    fun reloadSegmentCounts() {
        viewModelScope.launch {
            try {
                val liveCourses = courseManager.coursesFlow.first()
                matchCacheManager.repairStrayMatches(liveCourses)
                val liveCourseIds = liveCourses.map { it.id }.toSet()
                val allMatches = matchCacheManager.getAllMatches()
                val validMatches = allMatches.filter { liveCourseIds.contains(it.courseId) }
                val counts = validMatches.groupingBy { it.activityId }.eachCount()
                _segmentCounts.value = counts
                checkForOrphanedMatches()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }




    fun exportDiagnostics(context: Context, onComplete: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val courses = courseManager.coursesFlow.first()
                val matches = matchCacheManager.getAllMatches()
                val scannedCounts = mutableMapOf<String, Int>()
                for (c in courses) {
                    scannedCounts[c.id] = matchCacheManager.getScannedActivities(c.id).size
                }
                val file = com.example.shift.data.DiagnosticsExporter.generateAndExport(context, courses, matches, scannedCounts)
                if (file != null) {
                    com.example.shift.data.DiagnosticsExporter.shareDiagnosticsFile(context, file)
                    onComplete(file.name)
                } else {
                    onComplete("Export failed")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete("Export error: ${e.message}")
            }
        }
    }

    fun fetchPolylineIfNeeded(activityId: String) {

        if (_polylines.value.containsKey(activityId)) return
        if (activityId.startsWith("hc_")) {
            val act = _apiActivities.value.find { it.id == activityId }
            val poly = act?.map?.summary_polyline
            if (poly != null) {
                _polylines.value = _polylines.value + (activityId to poly)
            }
            return
        }
        val currentApi = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rawJson = currentApi.getActivityStreamsRaw(activityId, "latlng")
                val stream = SegmentScanner.parseStream(rawJson)
                if (stream.latlng != null && stream.latlng.isNotEmpty()) {
                    val validPoints = stream.latlng.filter { it[0] != 0.0 || it[1] != 0.0 }
                    val encoded = com.example.shift.utils.PolylineUtils.encodePolyline(validPoints.map { Pair(it[0], it[1]) })
                    withContext(Dispatchers.Main) {
                        _polylines.value = _polylines.value + (activityId to encoded)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteActivity(activityId: String) {
        _apiActivities.value = _apiActivities.value.filter { it.id != activityId }
    }

    fun syncNow() {
        viewModelScope.launch {
            cloudSyncManager.fullSync()
        }
    }

    suspend fun fetchElevationProfile(activityId: String): List<Pair<Double, Double>>? {
        if (activityId.startsWith("hc_")) return null
        return withContext(Dispatchers.IO) {
            try {
                val currentApi = api ?: return@withContext null
                val rawJson = currentApi.getActivityStreamsRaw(activityId, "time,distance,altitude")
                var distData: List<Double>? = null
                var altData: List<Double>? = null
                var timeData: List<Double>? = null
                
                if (rawJson !is kotlinx.serialization.json.JsonArray) return@withContext null
                
                for (element in rawJson) {
                    val obj = element.jsonObject
                    val streamType = obj["type"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content ?: continue
                    if (streamType == "distance") {
                        val data = obj["data"] as? kotlinx.serialization.json.JsonArray
                        distData = data?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                    }
                    if (streamType == "altitude") {
                        val data = obj["data"] as? kotlinx.serialization.json.JsonArray
                        altData = data?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                    }
                    if (streamType == "time") {
                        val data = obj["data"] as? kotlinx.serialization.json.JsonArray
                        timeData = data?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                    }
                }
                
                val xAxis = distData ?: timeData
                if (xAxis != null && altData != null) {
                    xAxis.zip(altData)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun saveWeeklyTargets(targets: WeeklyTargets) {
        viewModelScope.launch {
            targetsManager.saveTargets(targets)
        }
    }

    fun generateHubBriefing() {
        if (_hubBriefing.value != null) return // Already generated this session
        viewModelScope.launch {
            _isHubBriefingLoading.value = true
            try {
                val flowKey = geminiApiKey.value.trim()
                val apiKey = if (flowKey.isNotEmpty()) flowKey else com.example.shift.BuildConfig.GEMINI_API_KEY.trim()
                if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                    _hubBriefing.value = "Set up your Gemini API key in Settings to get personalised insights."
                    return@launch
                }

                val vitals = _userVitals.value
                val acts = _apiActivities.value
                val targets = weeklyTargets.value
                val wellness = _latestWellness.value

                val now = LocalDate.now()
                val startOfWeek = now.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                val weekActs = acts.filter {
                    try {
                        val d = LocalDate.parse(it.start_date_local.take(10))
                        !d.isBefore(startOfWeek)
                    } catch (e: Exception) { false }
                }
                val weekMiles = weekActs.sumOf { it.distance ?: 0.0 } * 0.000621371
                val weekRides = weekActs.size
                val weekTss = weekActs.sumOf { it.icu_training_load ?: 0.0 }.toInt()

                val ctl = wellness?.ctl ?: acts.firstOrNull()?.icu_ctl ?: 0.0
                val atl = wellness?.atl ?: acts.firstOrNull()?.icu_atl ?: 0.0
                val tsb = ctl - atl

                val dayOfWeek = now.dayOfWeek.value // 1=Mon, 7=Sun

                val context = buildString {
                    appendLine("You are a concise fitness analyst. Generate a 2-4 sentence daily briefing for an athlete.")
                    appendLine("Be specific with numbers. Be encouraging but honest.")
                    appendLine()
                    appendLine("CURRENT STATE:")
                    if (vitals != null) {
                        appendLine("Feel Score: ${vitals.feelScore}/100")
                        appendLine("Sleep: ${String.format("%.1f", vitals.avgSleepHours)}h")
                        vitals.avgRestingHR?.let { appendLine("Resting HR: ${String.format("%.0f", it)} bpm") }
                        vitals.avgHRV?.let { appendLine("HRV: ${String.format("%.0f", it)} ms") }
                    }
                    appendLine("CTL (fitness): ${String.format("%.0f", ctl)}")
                    appendLine("ATL (fatigue): ${String.format("%.0f", atl)}")
                    appendLine("TSB (form): ${String.format("%+.0f", tsb)}")
                    appendLine()
                    appendLine("THIS WEEK (day $dayOfWeek of 7):")
                    appendLine("Miles: ${String.format("%.1f", weekMiles)} / ${String.format("%.0f", targets.mileTarget)} target")
                    appendLine("Rides: $weekRides / ${targets.rideTarget} target")
                    appendLine("TSS: $weekTss / ${targets.tssTarget} target")
                    appendLine()
                    appendLine("RECENT ACTIVITIES:")
                    acts.take(5).forEach { act ->
                        val date = act.start_date_local.take(10)
                        val dist = act.distance?.let { "${String.format("%.1f", it * 0.000621371)}mi" } ?: ""
                        val time = act.moving_time?.let { "${it / 60}min" } ?: ""
                        val tl = act.icu_training_load?.let { "TSS:${it.toInt()}" } ?: ""
                        appendLine("  $date ${act.type} $dist $time $tl")
                    }
                }

                val model = com.google.ai.client.generativeai.GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey
                )
                val response = model.generateContent(context)
                _hubBriefing.value = response.text ?: "Unable to generate briefing."
            } catch (e: Exception) {
                _hubBriefing.value = "Unable to generate briefing right now."
            } finally {
                _isHubBriefingLoading.value = false
            }
        }
    }
}

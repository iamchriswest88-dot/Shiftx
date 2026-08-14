package com.example.shift.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.shift.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import com.example.shift.utils.PolylineUtils

class CourseDetailViewModel(
    application: Application,
    private val courseId: String,
    private val settingsManager: SettingsManager,
    private val courseManager: CourseManager,
    private val mainViewModel: MainViewModel
) : AndroidViewModel(application) {

    private val matchCacheManager = MatchCacheManager(application)

    private val _course = MutableStateFlow<Course?>(null)
    val course: StateFlow<Course?> = _course.asStateFlow()

    private val _matches = MutableStateFlow<List<CourseMatch>>(emptyList())
    val matches: StateFlow<List<CourseMatch>> = _matches.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private val _selectedYear = MutableStateFlow("All Time")
    val selectedYear: StateFlow<String> = _selectedYear.asStateFlow()

    val availableYears: StateFlow<List<String>> = _matches.map { matchList ->
        val years = matchList.map { it.date.substringBefore("-") }.distinct().sortedDescending()
        listOf("All Time") + years
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, listOf("All Time"))

    val filteredMatches: StateFlow<List<CourseMatch>> = kotlinx.coroutines.flow.combine(_matches, _selectedYear) { matchList, year ->
        if (year == "All Time") {
            matchList
        } else {
            matchList.filter { it.date.startsWith(year) }
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    fun setSelectedYear(year: String) {
        _selectedYear.value = year
    }

    init {
        viewModelScope.launch {
            val allCourses = courseManager.coursesFlow.firstOrNull() ?: emptyList()
            _course.value = allCourses.find { it.id == courseId }
            val cached = matchCacheManager.getMatches(courseId)
            _matches.value = cached.sortedBy { it.timeSeconds }
        }
    }

    /**
     * Reloads matches from cache and nothing else.
     *
     * This must never start a scan: it is called from scanActivities' finally block,
     * and a scan trigger here is mutual recursion that scans forever.
     */
    fun loadCachedMatches() {
        viewModelScope.launch {
            val cached = matchCacheManager.getMatches(courseId)
            _matches.value = cached.sortedBy { it.timeSeconds }
        }
    }

    /**
     * Screen-entry path: reload, then scan only if there is genuinely new work.
     *
     * The trigger deliberately does not consider whether the match list is empty. A
     * segment you have never ridden has no matches and never will, so treating
     * "no matches" as "needs scanning" rescanned the entire ride history on repeat.
     */
    fun loadAndMaybeScan() {
        viewModelScope.launch {
            val cached = matchCacheManager.getMatches(courseId)
            _matches.value = cached.sortedBy { it.timeSeconds }

            val currentCourse = _course.value
            if (currentCourse != null && !_isScanning.value) {
                val scannedIds = matchCacheManager.getScannedActivities(courseId)
                val allActivities = mainViewModel.activities.value
                val unscanned = allActivities.filter { !scannedIds.contains(it.id) }
                if (unscanned.isNotEmpty()) {
                    scanActivities(forceRescan = false)
                }
            }
        }
    }


    fun deleteMatch(match: CourseMatch) {
        viewModelScope.launch {
            matchCacheManager.deleteSingleMatch(match.courseId, match.activityId, match.attemptIndex)
            loadCachedMatches()
            mainViewModel.reloadSegmentCounts()
        }
    }


    fun scanActivities(forceRescan: Boolean = false) {
        if (_isScanning.value) return
        val currentCourse = _course.value ?: return

        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0f
            
            try {
                if (forceRescan) {
                    matchCacheManager.clearScannedCacheForCourse(courseId)
                }
                val key = settingsManager.apiKeyFlow.firstOrNull() ?: ""
                val api = ApiClient.create(key)
                val activities = mainViewModel.activities.value
                val scannedIds = if (forceRescan) emptySet() else matchCacheManager.getScannedActivities(courseId)
                
                val toScan = activities.filter { forceRescan || !scannedIds.contains(it.id) }
                var count = 0
                val total = toScan.size
                
                if (total == 0) {
                    _scanStatus.value = "All rides up to date"
                    return@launch
                }

                for (act in toScan) {
                    count++
                    _scanProgress.value = count.toFloat() / total.toFloat()
                    _scanStatus.value = "Scanning ${count}/${total}..."
                    com.example.shift.data.ScanLogBuffer.log("Scanning activity ${act.id} ('${act.name}', ${act.start_date_local}) for course ${currentCourse.name}")
                    
                    var markScanned = false
                    var abortPass = false
                    val stream: com.example.shift.data.ParsedStream = try {
                        val rawJson = api.getActivityStreamsRaw(act.id, "latlng,time,distance,watts,velocity_smooth")
                        markScanned = true
                        com.example.shift.data.ScanLogBuffer.log("Stream fetch OK for activity ${act.id}")
                        SegmentScanner.parseStream(rawJson)
                    } catch (e: Exception) {
                        val reason = com.example.shift.data.ScanFailure.describe(e)
                        if (com.example.shift.data.ScanFailure.isTransient(e)) {
                            // Rate limited or offline — stop rather than hammer on, and
                            // leave the activity unscanned so it is retried next time.
                            abortPass = true
                            com.example.shift.data.ScanLogBuffer.log("Pausing scan at activity ${act.id}: $reason")
                        } else {
                            // No stream will ever exist for this activity; mark it so it
                            // stops being retried on every pass.
                            markScanned = true
                            com.example.shift.data.ScanLogBuffer.log("No stream for activity ${act.id} ($reason), marking scanned")
                        }
                        android.util.Log.w("CourseDetailVM", "Stream fetch failed for ${act.id}: $reason")
                        com.example.shift.data.ParsedStream(null, null, null, null, null, null)
                    }

                    if (abortPass) {
                        _scanStatus.value = "Paused — rate limited, will resume"
                        break
                    }

                    val matches = SegmentScanner.detectGates(currentCourse, act, stream)
                    if (matches.isNotEmpty()) {
                        matchCacheManager.saveMatches(matches)
                        loadCachedMatches()
                    }

                    if (markScanned) {
                        matchCacheManager.markActivityAsScanned(courseId, act.id)
                    }

                    kotlinx.coroutines.delay(60L)

                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
                _scanStatus.value = "Scan complete"
                loadCachedMatches()
            }
        }
    }


}

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

    fun loadCachedMatches() {
        viewModelScope.launch {
            val cached = matchCacheManager.getMatches(courseId)
            _matches.value = cached.sortedBy { it.timeSeconds }
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
                    matchCacheManager.clearMatchesForCourse(courseId)
                    _matches.value = emptyList()
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

                val newMatches = mutableListOf<CourseMatch>()

                for (act in toScan) {
                    count++
                    _scanProgress.value = count.toFloat() / total.toFloat()
                    _scanStatus.value = "Scanning ${count}/${total}..."
                    
                    try {
                        val rawJson = api.getActivityStreamsRaw(act.id, "latlng,time,distance,watts,velocity_smooth")
                        val stream = SegmentScanner.parseStream(rawJson)
                        
                        val matches = SegmentScanner.detectGates(currentCourse, act, stream)
                        if (matches.isNotEmpty()) {
                            newMatches.addAll(matches)
                            val allCurrentMatches = matchCacheManager.getMatches(courseId) + newMatches
                            matchCacheManager.saveMatches(allCurrentMatches)
                            loadCachedMatches()
                        }
                        
                        scannedIds.toMutableSet().add(act.id)
                        matchCacheManager.markActivityAsScanned(courseId, act.id)
                        
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
                _scanStatus.value = "Scan complete"
            }
        }
    }

}

package com.example.shift.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shift.data.Activity
import com.example.shift.data.ApiClient
import com.example.shift.data.SettingsManager
import com.example.shift.data.CourseManager
import com.example.shift.data.Course
import com.example.shift.data.MatchCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.example.shift.utils.PolylineUtils
import java.net.URL

data class LatLng(val latitude: Double, val longitude: Double)

data class ParsedStream(
    val latlng: List<List<Double>>? = null,
    val time: List<Int>? = null,
    val distance: List<Double>? = null,
    val watts: List<Int>? = null,
    val velocity: List<Double>? = null,
    val altitude: List<Double>? = null
)

data class CourseMatchInfo(
    val course: Course,
    val timeSeconds: Int,
    val rank: Int,
    val total: Int
)

class CourseViewModel(
    private val settingsManager: SettingsManager,
    private val courseManager: CourseManager,
    private val matchCacheManager: MatchCacheManager,
    private val cloudSyncManager: com.example.shift.data.CloudSyncManager
) : ViewModel() {

    private val _stream = MutableStateFlow<ParsedStream?>(null)
    val stream: StateFlow<ParsedStream?> = _stream.asStateFlow()

    private val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _startMarker = MutableStateFlow<LatLng?>(null)
    val startMarker: StateFlow<LatLng?> = _startMarker.asStateFlow()

    private val _endMarker = MutableStateFlow<LatLng?>(null)
    val endMarker: StateFlow<LatLng?> = _endMarker.asStateFlow()

    /**
     * Points the segment must pass through, in ride order, between start and
     * finish. Each map tap beyond the finish extends the segment: the old
     * finish becomes the newest via point and the tap becomes the finish — so
     * the rider traces the roads they mean, tap by tap.
     */
    private val _viaPoints = MutableStateFlow<List<LatLng>>(emptyList())
    val viaPoints: StateFlow<List<LatLng>> = _viaPoints.asStateFlow()

    /** Road-snapped preview of the whole gate-to-gate path, for the map. */
    private val _previewPolyline = MutableStateFlow<String?>(null)
    val previewPolyline: StateFlow<String?> = _previewPolyline.asStateFlow()

    private var previewJob: kotlinx.coroutines.Job? = null

    private fun refreshPreview() {
        previewJob?.cancel()
        val start = _startMarker.value
        val end = _endMarker.value
        if (start == null || end == null) {
            _previewPolyline.value = null
            return
        }
        previewJob = viewModelScope.launch {
            val pts = buildList {
                add(start.longitude to start.latitude)
                _viaPoints.value.forEach { add(it.longitude to it.latitude) }
                add(end.longitude to end.latitude)
            }
            _previewPolyline.value = com.example.shift.data.OsrmClient.getRoutePolyline(pts)
        }
    }

    /** Steps back one tap: finish returns to the previous via, then gates clear. */
    fun undoLastPoint() {
        when {
            _endMarker.value != null -> {
                val vias = _viaPoints.value
                if (vias.isNotEmpty()) {
                    _endMarker.value = vias.last()
                    _viaPoints.value = vias.dropLast(1)
                } else {
                    _endMarker.value = null
                    _courseTime.value = null
                }
            }
            _startMarker.value != null -> _startMarker.value = null
        }
        refreshPreview()
    }

    private val _courseTime = MutableStateFlow<Int?>(null)
    val courseTime: StateFlow<Int?> = _courseTime.asStateFlow()

    private val _matchedCourses = MutableStateFlow<List<CourseMatchInfo>>(emptyList())
    val matchedCourses: StateFlow<List<CourseMatchInfo>> = _matchedCourses.asStateFlow()

    private val _courseNameForEdit = MutableStateFlow("")
    val courseNameForEdit: StateFlow<String> = _courseNameForEdit.asStateFlow()

    fun loadCourseForEdit(courseId: String) {
        viewModelScope.launch {
            val course = courseManager.coursesFlow.firstOrNull()?.find { it.id == courseId }
            if (course != null) {
                _startMarker.value = LatLng(course.startLat, course.startLng)
                _endMarker.value = LatLng(course.endLat, course.endLng)
                _courseNameForEdit.value = course.name
            }
        }
    }

    fun resetGates() {
        _startMarker.value = null
        _endMarker.value = null
        _viaPoints.value = emptyList()
        _previewPolyline.value = null
        _courseTime.value = null
        _courseNameForEdit.value = ""
    }

    fun clearStartGate() {
        _startMarker.value = null
        _courseTime.value = null
        refreshPreview()
    }

    fun clearEndGate() {
        _endMarker.value = null
        _viaPoints.value = emptyList()
        _courseTime.value = null
        refreshPreview()
    }

    fun loadActivityDirectly(activity: Activity) {
        _activity.value = activity
        val polyline = activity.map?.summary_polyline
        if (polyline != null) {
            val decoded = PolylineUtils.decodePolyline(polyline)
            val latlngList = decoded.map { listOf(it.first, it.second) }
            _stream.value = ParsedStream(latlng = latlngList)
        } else {
            _stream.value = null
        }
        _isLoading.value = false
    }

    fun loadActivity(activityId: String) {
        if (activityId == "new") {
            _stream.value = null
            _activity.value = null
            _isLoading.value = false
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val key = settingsManager.apiKeyFlow.firstOrNull() ?: ""
                val api = ApiClient.create(key)

                // Load activity details
                try {
                    _activity.value = api.getActivity(activityId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Load streams as raw JSON and parse flexibly
                val rawJson = api.getActivityStreamsRaw(activityId, "latlng,time,distance,altitude")

                var latData: List<Double>? = null
                var lngData: List<Double>? = null
                var timeData: List<Int>? = null
                var distData: List<Double>? = null
                var wattsData: List<Int>? = null
                var velocityData: List<Double>? = null
                var altitudeData: List<Double>? = null

                // The response is an array of stream objects
                val streamArray = rawJson.jsonArray
                android.util.Log.i("ShiftMap", "Got ${streamArray.size} stream objects")
                for (element in streamArray) {
                    val obj = element.jsonObject
                    android.util.Log.i("ShiftMap", "Stream keys: ${obj.keys}")
                    // Try "type" first, fall back to "id" for the stream name
                    val streamType = obj["type"]?.jsonPrimitive?.content
                        ?: obj["id"]?.jsonPrimitive?.content
                        ?: continue
                    android.util.Log.i("ShiftMap", "Stream type=$streamType")

                    val data = obj["data"]

                    when (streamType) {
                        "latlng" -> {
                            // Intervals.icu: lat values in "data", lng values in "data2"
                            val latArray = obj["data"]
                            val lngArray = obj["data2"]
                            if (latArray is JsonArray && lngArray is JsonArray) {
                                val lats = latArray.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                                val lngs = lngArray.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                                val pairs = lats.zip(lngs).map { (lat, lng) -> listOf(lat, lng) }
                                android.util.Log.i("ShiftMap", "Parsed ${pairs.size} latlng pairs")
                                latData = lats
                                lngData = lngs
                            } else if (data is JsonArray && data.size > 0) {
                                // Fallback: paired [[lat, lng], ...]
                                val first = data[0]
                                if (first is JsonArray) {
                                    val pairs = data.mapNotNull { item ->
                                        val arr = item as? JsonArray
                                        if (arr != null && arr.size >= 2) {
                                            val lat = if (arr[0] is kotlinx.serialization.json.JsonNull) 0.0 else arr[0].jsonPrimitive.double
                                            val lng = if (arr[1] is kotlinx.serialization.json.JsonNull) 0.0 else arr[1].jsonPrimitive.double
                                            listOf(lat, lng)
                                        } else null
                                    }
                                    latData = pairs.map { it[0] }
                                    lngData = pairs.map { it[1] }
                                }
                            }
                        }
                        "lat" -> {
                            latData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                        }
                        "lng" -> {
                            lngData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                        }
                        "time" -> {
                            timeData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0 else it.jsonPrimitive.int }
                        }
                        "distance" -> {
                            distData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                        }
                        "watts" -> {
                            wattsData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0 else it.jsonPrimitive.int }
                        }
                        "velocity_smooth" -> {
                            velocityData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                        }
                        "altitude" -> {
                            altitudeData = (data as? JsonArray)?.map { if (it is kotlinx.serialization.json.JsonNull) 0.0 else it.jsonPrimitive.double }
                        }
                    }
                }

                // If we got separate lat/lng arrays, combine them
                if (latData != null && lngData != null) {
                    val pairs = latData!!.zip(lngData!!).map { (lat, lng) -> listOf(lat, lng) }
                    _stream.value = ParsedStream(pairs, timeData, distData, wattsData, velocityData, altitudeData)
                } else if (_stream.value != null) {
                    // We already parsed latlng pairs above, update time/distance/watts/velocity
                    val current = _stream.value!!
                    _stream.value = current.copy(
                        time = timeData ?: current.time, 
                        distance = distData ?: current.distance,
                        watts = wattsData ?: current.watts,
                        velocity = velocityData ?: current.velocity,
                        altitude = altitudeData ?: current.altitude
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
                findMatchedCourses()
            }
        }
    }

    fun onMapClick(lat: Double, lng: Double) {
        viewModelScope.launch {
            val snappedPoint = snapPoint(lat, lng)

            if (_startMarker.value == null) {
                _startMarker.value = snappedPoint
            } else if (_endMarker.value == null) {
                _endMarker.value = snappedPoint
                calculateTime()
            } else {
                // Extend: the old finish becomes a via point and this tap is the
                // new finish, so the segment follows the tapped roads instead of
                // whatever path the router would pick between two distant gates.
                _viaPoints.value = _viaPoints.value + _endMarker.value!!
                _endMarker.value = snappedPoint
                calculateTime()
            }
            refreshPreview()
        }
    }

    fun onMarkerMoved(isStart: Boolean, lat: Double, lng: Double) {
        viewModelScope.launch {
            val snappedPoint = snapPoint(lat, lng)

            if (isStart) {
                _startMarker.value = snappedPoint
            } else {
                _endMarker.value = snappedPoint
            }

            if (_startMarker.value != null && _endMarker.value != null) {
                calculateTime()
            }
            refreshPreview()
        }
    }

    /**
     * Snap a point to the GPS track if close enough, otherwise snap to nearest road via OSRM.
     */
    private suspend fun snapPoint(lat: Double, lng: Double): LatLng {
        val currentStream = _stream.value
        val latlngs = currentStream?.latlng

        // First try: snap to the GPS track if loaded and close
        if (latlngs != null) {
            var closestIdx = -1
            var minDist = Double.MAX_VALUE
            for (i in latlngs.indices) {
                val ll = latlngs[i]
                if (ll.size >= 2) {
                    val dist = distance(lat, lng, ll[0], ll[1])
                    if (dist < minDist) {
                        minDist = dist
                        closestIdx = i
                    }
                }
            }
            if (minDist < 500 && closestIdx != -1) {
                return LatLng(latlngs[closestIdx][0], latlngs[closestIdx][1])
            }
        }

        // Second try: snap to nearest road via OSRM
        return snapToNearestRoad(lat, lng) ?: LatLng(lat, lng)
    }

    /**
     * Use OSRM Nearest API to snap a coordinate to the nearest road.
     */
    private suspend fun snapToNearestRoad(lat: Double, lng: Double): LatLng? {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val url = "https://router.project-osrm.org/nearest/v1/cycling/$lng,$lat?number=1"
                val json = URL(url).readText()
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
                val code = parsed["code"]?.jsonPrimitive?.content
                if (code == "Ok") {
                    val waypoints = parsed["waypoints"]?.jsonArray
                    val location = waypoints?.get(0)?.jsonObject?.get("location")?.jsonArray
                    if (location != null && location.size >= 2) {
                        val snappedLng = location[0].jsonPrimitive.double
                        val snappedLat = location[1].jsonPrimitive.double
                        LatLng(snappedLat, snappedLng)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.w("ShiftMap", "OSRM snap failed: ${e.message}")
            null
        }
    }

    private fun calculateTime() {
        val start = _startMarker.value
        val end = _endMarker.value
        val currentStream = _stream.value
        if (start == null || end == null || currentStream == null) return

        val latlngs = currentStream.latlng ?: return
        val times = currentStream.time ?: return

        var startIdx = -1
        var endIdx = -1

        for (i in latlngs.indices) {
            val ll = latlngs[i]
            if (ll.size >= 2) {
                if (startIdx == -1 && ll[0] == start.latitude && ll[1] == start.longitude) startIdx = i
                if (endIdx == -1 && ll[0] == end.latitude && ll[1] == end.longitude) endIdx = i
            }
        }

        if (startIdx != -1 && endIdx != -1 && times.size > startIdx && times.size > endIdx) {
            _courseTime.value = kotlin.math.abs(times[endIdx] - times[startIdx])
        }
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3 // metres
        val phi1 = lat1 * Math.PI / 180
        val phi2 = lat2 * Math.PI / 180
        val deltaPhi = (lat2 - lat1) * Math.PI / 180
        val deltaLambda = (lon2 - lon1) * Math.PI / 180

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    private fun findMatchedCourses() {
        val currentStream = _stream.value ?: return
        val latlngs = currentStream.latlng ?: return
        val times = currentStream.time ?: return

        viewModelScope.launch {
            val allCourses = courseManager.coursesFlow.firstOrNull() ?: emptyList()
            android.util.Log.d("DEBUG_MATCH", "findMatchedCourses: loaded ${allCourses.size} courses. latlngs size: ${latlngs.size}, times size: ${times.size}")
            val matches = mutableListOf<CourseMatchInfo>()

            for (course in allCourses) {
                android.util.Log.d("DEBUG_MATCH", "Checking course: ${course.name}")
                val startIndices = mutableListOf<Int>()
                val endIndices = mutableListOf<Int>()
                
                var inStartZone = false
                var inEndZone = false
                
                for (i in latlngs.indices) {
                    val pt = latlngs[i]
                    if (pt.size >= 2) {
                        val distStart = distance(course.startLat, course.startLng, pt[0], pt[1])
                        if (distStart < 200.0) {
                            if (!inStartZone) {
                                startIndices.add(i)
                                inStartZone = true
                            }
                        } else {
                            inStartZone = false
                        }
                        
                        val distEnd = distance(course.endLat, course.endLng, pt[0], pt[1])
                        if (distEnd < 200.0) {
                            if (!inEndZone) {
                                endIndices.add(i)
                                inEndZone = true
                            }
                        } else {
                            inEndZone = false
                        }
                    }
                }

                var bestTime = Int.MAX_VALUE
                var hasMatch = false

                for (s in startIndices) {
                    for (e in endIndices) {
                        if (e > s) {
                            val timeTaken = times[e] - times[s]
                            if (timeTaken > 0 && timeTaken < bestTime) {
                                bestTime = timeTaken
                                hasMatch = true
                            }
                        }
                    }
                }
                
                if (hasMatch) {
                    // bestTime above is only a coarse trigger: first samples inside a
                    // 200m zone around each gate. It decides WHETHER this ride touched
                    // the segment — never what time to display. Displaying it is why
                    // the activity page and segment page disagreed on every ride: the
                    // segment page shows the scanner's interpolated gate-to-gate time.
                    val currentActivity = _activity.value
                    if (currentActivity != null) {
                        val parsedStream = com.example.shift.data.ParsedStream(
                            latlng = currentStream.latlng,
                            time = currentStream.time,
                            distance = currentStream.distance,
                            watts = currentStream.watts,
                            velocity = currentStream.velocity,
                            heartrate = null
                        )
                        val scannedMatches = com.example.shift.data.SegmentScanner.detectGates(course, currentActivity, parsedStream)
                        if (scannedMatches.isNotEmpty()) {
                            matchCacheManager.saveMatches(scannedMatches)
                            matchCacheManager.markActivityAsScanned(course.id, currentActivity.id)
                        } else {
                            val fallbackMatch = com.example.shift.data.CourseMatch(
                                courseId = course.id,
                                activityId = currentActivity.id,
                                activityName = currentActivity.name,
                                date = currentActivity.start_date_local.substringBefore("T"),
                                timeSeconds = bestTime,
                                timestamp = System.currentTimeMillis(),
                                estimatedTime = true
                            )
                            matchCacheManager.saveMatches(listOf(fallbackMatch))
                            matchCacheManager.markActivityAsScanned(course.id, currentActivity.id)
                        }
                    }

                    // Read the display time back from the cache — the single source
                    // of truth both pages now share. The save above ran first, so
                    // this ride's scanned entry is guaranteed to be in here.
                    val cachedMatches = matchCacheManager.getMatches(course.id)
                    val currentActivityId = _activity.value?.id?.toString() ?: ""
                    val cachedForThisRide = cachedMatches.filter { it.activityId == currentActivityId }
                    val displayTime = cachedForThisRide.minOfOrNull { it.timeSeconds } ?: bestTime

                    val rank = cachedMatches.count { it.timeSeconds < displayTime } + 1
                    val isInCache = cachedForThisRide.isNotEmpty()
                    val total = if (isInCache) cachedMatches.size else cachedMatches.size + 1

                    android.util.Log.d("DEBUG_MATCH", "Found match for ${course.name} with time $displayTime (zone estimate was $bestTime), rank $rank/$total")
                    matches.add(CourseMatchInfo(course, displayTime, rank, total))
                }
 else {
                    android.util.Log.d("DEBUG_MATCH", "No match for ${course.name}. starts: ${startIndices.size}, ends: ${endIndices.size}")
                }
            }

            android.util.Log.d("DEBUG_MATCH", "Final matches: ${matches.size}")
            _matchedCourses.value = matches
        }
    }


    fun saveCourse(name: String, courseId: String? = null) {
        val start = _startMarker.value ?: return
        val end = _endMarker.value ?: return
        viewModelScope.launch {
            if (courseId != null) {
                matchCacheManager.clearMatchesForCourse(courseId)
            }
            val id = courseId ?: java.util.UUID.randomUUID().toString()
            
            // Fetch polyline from OSRM, routed through every via point in order.
            val routePoints = buildList {
                add(start.longitude to start.latitude)
                _viaPoints.value.forEach { add(it.longitude to it.latitude) }
                add(end.longitude to end.latitude)
            }
            val polyline = com.example.shift.data.OsrmClient.getRoutePolyline(routePoints)
            
            val course = com.example.shift.data.Course(
                id = id,
                name = name,
                startLat = start.latitude,
                startLng = start.longitude,
                endLat = end.latitude,
                endLng = end.longitude,
                encodedPolyline = polyline
            )
            courseManager.saveCourse(course)
            // New/edited segments reach the cloud immediately, not at next launch.
            cloudSyncManager.fullSync()
        }
    }
}

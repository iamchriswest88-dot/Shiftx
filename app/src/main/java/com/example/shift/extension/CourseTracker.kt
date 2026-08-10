package com.example.shift.extension

import android.content.Context
import android.util.Log
import com.example.shift.data.Course
import com.example.shift.data.CourseManager
import com.example.shift.data.CourseMatch
import com.example.shift.data.MatchCacheManager
import com.example.shift.utils.PolylineUtils
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class TrackingState(
    val activeCourseId: String? = null,
    val distanceRemainingMeters: Double? = null,
    val timeDeltaSeconds: Double? = null
)

class CourseTracker(
    private val context: Context,
    private val karooSystem: KarooSystemService
) {
    companion object {
        private const val TAG = "CourseTracker"
        /** Radius in meters to detect entering a start zone */
        private const val START_RADIUS_M = 40.0
        /** Radius in meters to detect reaching the end zone */
        private const val END_RADIUS_M = 40.0
        /** If rider strays further than this from the polyline, abandon tracking */
        private const val OFF_COURSE_M = 40.0
    }

    private val courseManager = CourseManager(context)
    private val matchManager = MatchCacheManager(context)

    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private var courses: List<Course> = emptyList()
    private val coursePrs = mutableMapOf<String, Int>()

    private var activeCourse: Course? = null
    private var activeStartTime: Long = 0L
    private var decodedPolyline: List<Pair<Double, Double>> = emptyList()
    private var totalPolylineDist: Double = 0.0

    private val started = AtomicBoolean(false)

    fun startTracking(scope: CoroutineScope) {
        // Guard against duplicate collectors if connect() fires more than once (reconnects)
        if (!started.compareAndSet(false, true)) return

        // Continuously collect courses so edits in the app are picked up live
        scope.launch(Dispatchers.IO) {
            courseManager.coursesFlow.collect { latestCourses ->
                courses = latestCourses
                Log.d(TAG, "Courses reloaded: ${courses.size} segments")
                // Refresh PRs whenever courses change
                refreshPrs()
            }
        }

        // Consume location updates from the Karoo System.
        // OnLocationChanged (karoo-ext >= 1.1.3) is the supported way to observe
        // position; streaming DataType.Type.LOCATION is unreliable outside an
        // active recording and is not what the official sample uses.
        scope.launch(Dispatchers.IO) {
            karooSystem.consumerFlow<OnLocationChanged>().collect { loc ->
                processLocationUpdate(loc.lat, loc.lng)
            }
        }
    }

    private suspend fun refreshPrs() {
        val allMatches = matchManager.getAllMatches()
        coursePrs.clear()
        courses.forEach { course ->
            val pr = allMatches.filter { it.courseId == course.id }.minOfOrNull { it.timeSeconds }
            if (pr != null) {
                coursePrs[course.id] = pr
            }
        }
    }

    // ── Haversine distance between two points (meters) ──────────────────
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2.0 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ── Total length of a decoded polyline ──────────────────────────────
    private fun polylineLength(pts: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 0 until pts.size - 1) {
            total += haversineMeters(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
        }
        return total
    }

    // ── Distance along polyline up to (and including partial projection onto) segment at closestIndex
    private fun distanceAlongPolyline(pts: List<Pair<Double, Double>>, closestIndex: Int, riderLat: Double, riderLon: Double): Double {
        var dist = 0.0
        for (i in 0 until closestIndex) {
            dist += haversineMeters(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
        }
        // Add partial distance along the closest segment
        if (closestIndex < pts.size - 1) {
            val p1 = pts[closestIndex]
            val p2 = pts[closestIndex + 1]
            val segLen = haversineMeters(p1.first, p1.second, p2.first, p2.second)
            if (segLen > 0) {
                // Project rider onto segment to find fraction
                val t = projectOntoSegment(p1, p2, riderLat, riderLon)
                dist += t * segLen
            }
        }
        return dist
    }

    // ── Project a point onto a segment, returning clamped t in [0, 1] ──
    private fun projectOntoSegment(p1: Pair<Double, Double>, p2: Pair<Double, Double>, ptLat: Double, ptLon: Double): Double {
        val cosLat = cos(Math.toRadians(p1.first))
        val x2 = (p2.second - p1.second) * cosLat
        val y2 = (p2.first - p1.first)
        val x3 = (ptLon - p1.second) * cosLat
        val y3 = (ptLat - p1.first)
        val l2 = x2 * x2 + y2 * y2
        if (l2 == 0.0) return 0.0
        return (x3 * x2 + y3 * y2).coerceIn(0.0, l2) / l2
    }

    private fun processLocationUpdate(lat: Double, lng: Double) {
        val course = activeCourse
        if (course == null) {
            // ── Not currently on a segment — check if we entered one ──
            for (c in courses) {
                if (c.encodedPolyline == null) continue
                val distToStart = haversineMeters(lat, lng, c.startLat, c.startLng)
                if (distToStart < START_RADIUS_M) {
                    Log.i(TAG, "Entered segment '${c.name}' (${distToStart.toInt()}m from start)")
                    activeCourse = c
                    activeStartTime = System.currentTimeMillis()
                    decodedPolyline = PolylineUtils.decodePolyline(c.encodedPolyline)
                    totalPolylineDist = polylineLength(decodedPolyline)
                    Log.d(TAG, "Polyline points=${decodedPolyline.size}, totalDist=${totalPolylineDist.toInt()}m")
                    return
                }
            }
            _state.value = TrackingState() // Idle
        } else {
            // ── Active on a segment ─────────────────────────────────────

            // 1. Find closest segment on polyline
            var minDist = Double.MAX_VALUE
            var closestIndex = 0
            for (i in 0 until decodedPolyline.size - 1) {
                val p1 = decodedPolyline[i]
                val p2 = decodedPolyline[i + 1]
                val dist = PolylineUtils.distanceToSegment(p1.first, p1.second, p2.first, p2.second, lat, lng)
                if (dist < minDist) {
                    minDist = dist
                    closestIndex = i
                }
            }

            // 2. Off-course check
            if (minDist > OFF_COURSE_M) {
                Log.w(TAG, "Off course (${minDist.toInt()}m from polyline), abandoning segment")
                activeCourse = null
                _state.value = TrackingState()
                return
            }

            // 3. Distance remaining and progress ratio
            val distanceCovered = distanceAlongPolyline(decodedPolyline, closestIndex, lat, lng)
            val distanceRemaining = (totalPolylineDist - distanceCovered).coerceAtLeast(0.0)
            val progressRatio = if (totalPolylineDist > 0) (distanceCovered / totalPolylineDist).coerceIn(0.0, 1.0) else 0.0
            val elapsedSeconds = ((System.currentTimeMillis() - activeStartTime) / 1000.0).toInt()

            // 4. Finish check: proximity to END point + meaningful progress (>80%) + min 10s elapsed
            val distToEnd = haversineMeters(lat, lng, course.endLat, course.endLng)
            if (distToEnd < END_RADIUS_M && progressRatio > 0.8 && elapsedSeconds >= 10) {
                Log.i(TAG, "Finished segment '${course.name}' in ${elapsedSeconds}s")
                recordAttempt(course, elapsedSeconds)
                activeCourse = null
                _state.value = TrackingState()
                return
            }

            // 5. Time delta against PR
            var timeDelta: Double? = null
            val prTime = coursePrs[course.id]
            if (prTime != null && totalPolylineDist > 0) {
                val elapsedTime = (System.currentTimeMillis() - activeStartTime) / 1000.0
                val expectedTime = prTime * progressRatio
                timeDelta = elapsedTime - expectedTime
            }

            _state.value = TrackingState(
                activeCourseId = course.id,
                distanceRemainingMeters = distanceRemaining,
                timeDeltaSeconds = timeDelta
            )
        }
    }


    private fun recordAttempt(course: Course, elapsedSeconds: Int) {
        val match = CourseMatch(
            courseId = course.id,
            activityId = "live-${System.currentTimeMillis()}",
            activityName = "Live Ride",
            date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
            timeSeconds = elapsedSeconds
        )
        // Fire-and-forget save on IO
        CoroutineScope(Dispatchers.IO).launch {
            try {
                matchManager.saveMatches(listOf(match))
                // Update PR cache
                val currentPr = coursePrs[course.id]
                if (currentPr == null || elapsedSeconds < currentPr) {
                    coursePrs[course.id] = elapsedSeconds
                    Log.i(TAG, "New PR for '${course.name}': ${elapsedSeconds}s")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save live attempt", e)
            }
        }
    }
}

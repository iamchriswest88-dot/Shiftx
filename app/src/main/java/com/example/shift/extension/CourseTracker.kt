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

import com.example.shift.data.CurvePoint

data class FinishResult(
    val timeSeconds: Int,
    val prTimeSeconds: Int?,
    val isNewPr: Boolean,
    /** Final placing, 1 = won. Null when there was no field to race. */
    val position: Int? = null,
    /** Field size including the rider. */
    val fieldSize: Int = 0
)

data class TrackingState(
    val activeCourseId: String? = null,
    val courseName: String? = null,
    val distanceRemainingMeters: Double? = null,
    val timeDeltaSeconds: Double? = null,
    val elapsedSeconds: Double? = null,
    val prTimeSeconds: Int? = null,
    val progressRatio: Double? = null,
    val riderLatLng: Pair<Double, Double>? = null,
    val ghostLatLng: Pair<Double, Double>? = null,
    val ghostBearing: Float? = null,
    val ghostIsLinearFallback: Boolean = false,
    val ghostField: List<GhostPosition> = emptyList(),
    val activePolyline: List<Pair<Double, Double>>? = null,
    val activeEncodedPolyline: String? = null,
    val finished: FinishResult? = null,
    /** Rider's place in the field, 1 = leading. Null when there are no ghosts. */
    val racePosition: Int? = null,
    /** Size of the field including the rider. */
    val fieldSize: Int = 0,
    /** Seconds back to the rider one place ahead. Null when leading. */
    val gapAheadSeconds: Double? = null,
    /** Seconds of advantage over the rider one place behind. Null when last. */
    val gapBehindSeconds: Double? = null,
    /** Gap to every ghost in the field, for the full-screen leaderboard. */
    val ghostGaps: List<GhostGap> = emptyList(),
    /** Nearest segment start ahead of the rider, when within announcement range. */
    val upcoming: UpcomingSegment? = null,
    /** Total length of the active segment, used to pick a map zoom that frames it. */
    val segmentLengthMeters: Double? = null
)

data class UpcomingSegment(
    val courseName: String,
    val distanceMeters: Double
)




data class PrRecord(
    val timeSeconds: Int,
    val curve: List<CurvePoint>?
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
        /**
         * How long the finish summary stays on screen. Must outlast PageNavigator's
         * 8s hold, otherwise the next location update wipes the summary before the
         * rider has seen it.
         */
        private const val FINISH_HOLD_MS = 10_000L
        /** Announce an approaching segment start within this range. */
        private const val UPCOMING_RANGE_M = 1_000.0

        fun selectRepresentativeGhosts(matches: List<CourseMatch>): List<CourseMatch> {
            if (matches.isEmpty()) return emptyList()
            val nonEstimated = matches.filter { !it.estimatedTime }
            val candidates = if (nonEstimated.isNotEmpty()) nonEstimated else matches
            val uniqueSorted = candidates
                .distinctBy { it.activityId }
                .sortedBy { it.timeSeconds }

            if (uniqueSorted.size <= 5) return uniqueSorted

            val n = uniqueSorted.size
            val indices = listOf(
                0,
                kotlin.math.round((n - 1) * 0.25).toInt(),
                kotlin.math.round((n - 1) * 0.50).toInt(),
                kotlin.math.round((n - 1) * 0.75).toInt(),
                n - 1
            ).distinct()

            return indices.map { uniqueSorted[it] }
        }
    }



    private val courseManager = CourseManager(context)
    private val matchManager = MatchCacheManager(context)

    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private var courses: List<Course> = emptyList()
    private val coursePrs = mutableMapOf<String, PrRecord>()

    private var activeCourse: Course? = null
    private var activeStartTime: Long = 0L
    private var decodedPolyline: List<Pair<Double, Double>> = emptyList()
    private var cumPolylineDistances: List<Double> = emptyList()
    private var totalPolylineDist: Double = 0.0

    private val currentCurve = mutableListOf<CurvePoint>()
    private var lastSampleTimeMs = 0L
    private var lastSampleDist = 0.0

    private val started = AtomicBoolean(false)

    fun startTracking(scope: CoroutineScope) {
        // Guard against duplicate collectors if connect() fires more than once (reconnects)
        if (!started.compareAndSet(false, true)) return

        // Continuously collect courses so edits in the app are picked up live
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
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
        scope.launch(Dispatchers.IO + extensionExceptionHandler) {
            karooSystem.consumerFlow<OnLocationChanged>().collect { loc ->
                guarded("location tick") {
                    processLocationUpdate(loc.lat, loc.lng)
                }
            }
        }
    }

    private suspend fun refreshPrs() {
        val allMatches = matchManager.getAllMatches()
        coursePrs.clear()
        courses.forEach { course ->
            val matchesForCourse = allMatches.filter { it.courseId == course.id }
            val nonEstimated = matchesForCourse.filter { !it.estimatedTime }
            val candidateMatches = if (nonEstimated.isNotEmpty()) nonEstimated else matchesForCourse
            val prMatch = candidateMatches.minByOrNull { it.timeSeconds }
            if (prMatch != null) {
                coursePrs[course.id] = PrRecord(prMatch.timeSeconds, prMatch.curve)
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

    // ── Total length and cumulative segment distances ────────────────────
    private fun computeCumulativeDistances(pts: List<Pair<Double, Double>>): List<Double> {
        if (pts.isEmpty()) return emptyList()
        val list = ArrayList<Double>(pts.size)
        list.add(0.0)
        var total = 0.0
        for (i in 0 until pts.size - 1) {
            total += haversineMeters(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
            list.add(total)
        }
        return list
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

    private var activeGhostSpecs: List<GhostSpec> = emptyList()

    private var finishHoldUntilMs: Long = 0L

    private suspend fun processLocationUpdate(lat: Double, lng: Double) {
        // Keep the finish summary on screen rather than letting the idle state
        // overwrite it on the very next GPS tick.
        if (System.currentTimeMillis() < finishHoldUntilMs) return

        val course = activeCourse
        if (course == null) {
            // ── Not currently on a segment — check if we entered one ──
            var nearestName: String? = null
            var nearestDist = UPCOMING_RANGE_M
            for (c in courses) {
                if (c.encodedPolyline == null) continue
                val distToStart = haversineMeters(lat, lng, c.startLat, c.startLng)
                if (distToStart < nearestDist) {
                    nearestDist = distToStart
                    nearestName = c.name
                }
                if (distToStart < START_RADIUS_M) {
                    Log.i(TAG, "Entered segment '${c.name}' (${distToStart.toInt()}m from start)")
                    activeCourse = c
                    activeStartTime = System.currentTimeMillis()
                    decodedPolyline = PolylineUtils.decodePolyline(c.encodedPolyline)
                    cumPolylineDistances = computeCumulativeDistances(decodedPolyline)
                    totalPolylineDist = cumPolylineDistances.lastOrNull() ?: 0.0
                    currentCurve.clear()
                    currentCurve.add(CurvePoint(0.0, 0.0))
                    lastSampleTimeMs = activeStartTime
                    lastSampleDist = 0.0

                    val matchesForCourse = matchManager.getMatches(c.id)
                    val selectedMatches = selectRepresentativeGhosts(matchesForCourse)
                    activeGhostSpecs = if (selectedMatches.isNotEmpty()) {
                        selectedMatches.mapIndexed { idx, m -> GhostSpec(idx + 1, m.timeSeconds, m.curve) }
                    } else {
                        val pr = coursePrs[c.id]
                        if (pr != null) listOf(GhostSpec(1, pr.timeSeconds, pr.curve)) else emptyList()
                    }

                    Log.d(TAG, "Polyline points=${decodedPolyline.size}, totalDist=${totalPolylineDist.toInt()}m, ghosts=${activeGhostSpecs.size}")
                    return
                }
            }
            activeGhostSpecs = emptyList()
            // Idle, but announce a nearby segment start so the drawer can show its
            // approach chip — the Climber pattern of a tab peeking before the event.
            _state.value = TrackingState(
                upcoming = nearestName?.let { UpcomingSegment(it, nearestDist) }
            )
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
                activeGhostSpecs = emptyList()
                _state.value = TrackingState()
                return
            }

            // 3. Distance remaining and progress ratio
            val distanceCovered = distanceAlongPolyline(decodedPolyline, closestIndex, lat, lng)
            val distanceRemaining = (totalPolylineDist - distanceCovered).coerceAtLeast(0.0)
            val progressRatio = if (totalPolylineDist > 0) (distanceCovered / totalPolylineDist).coerceIn(0.0, 1.0) else 0.0
            val nowMs = System.currentTimeMillis()
            val elapsedSec = (nowMs - activeStartTime) / 1000.0
            val elapsedSecondsInt = elapsedSec.toInt()

            // 4. Sample ride curve (at most 1 sample per ~2s or ~20m progress; monotonic)
            if (distanceCovered >= lastSampleDist) {
                val timeSinceLastMs = nowMs - lastSampleTimeMs
                val distSinceLast = distanceCovered - lastSampleDist
                if (timeSinceLastMs >= 2000 || distSinceLast >= 20.0) {
                    currentCurve.add(CurvePoint(distanceCovered, elapsedSec))
                    lastSampleTimeMs = nowMs
                    lastSampleDist = distanceCovered
                }
            }

            val prRecord = coursePrs[course.id]

            // 5. Finish check: proximity to END point + meaningful progress (>80%) + min 10s elapsed
            val distToEnd = haversineMeters(lat, lng, course.endLat, course.endLng)
            if (distToEnd < END_RADIUS_M && progressRatio > 0.8 && elapsedSecondsInt >= 10) {
                Log.i(TAG, "Finished segment '${course.name}' in ${elapsedSecondsInt}s")
                val finalDist = maxOf(totalPolylineDist, lastSampleDist)
                currentCurve.add(CurvePoint(finalDist, elapsedSec))
                recordAttempt(course, elapsedSecondsInt, currentCurve.toList())
                
                val isNewPr = prRecord == null || elapsedSecondsInt < prRecord.timeSeconds

                // Final placing is settled on finishing times, not the mid-segment gap
                // positions — a ghost still out on the road has simply been beaten.
                // Computed before activeGhostSpecs is cleared below.
                val ratedGhosts = activeGhostSpecs.filter { it.timeSeconds > 0 }
                val beatenBy = ratedGhosts.count { it.timeSeconds < elapsedSecondsInt }

                val finishResult = FinishResult(
                    timeSeconds = elapsedSecondsInt,
                    prTimeSeconds = prRecord?.timeSeconds,
                    isNewPr = isNewPr,
                    position = if (ratedGhosts.isEmpty()) null else beatenBy + 1,
                    fieldSize = if (ratedGhosts.isEmpty()) 0 else ratedGhosts.size + 1
                )
                activeCourse = null
                activeGhostSpecs = emptyList()
                // activeCourseId is deliberately left null. PageNavigator and
                // MapLayerManager detect segment exit as "was non-null, now null";
                // emitting the course id here meant neither saw a transition on this
                // tick, and the following idle tick looked like an abandon instead.
                _state.value = TrackingState(
                    courseName = course.name,
                    finished = finishResult
                )
                // Without this the next location update (~1s later) replaces the
                // summary with the idle state before it can be read.
                finishHoldUntilMs = System.currentTimeMillis() + FINISH_HOLD_MS
                return
            }

            // 6. Time delta against PR using curve-aware expected time
            val expectedTime = GhostEngine.calculateExpectedTime(prRecord, distanceCovered, totalPolylineDist)
            val timeDelta = if (expectedTime != null) elapsedSec - expectedTime else null

            // 7. Multi-ghost positions
            val ghostField = GhostEngine.positionsAt(
                elapsedSec,
                activeGhostSpecs,
                decodedPolyline,
                cumPolylineDistances,
                totalPolylineDist
            )
            val prGhost = ghostField.find { it.rank == 1 }

            // 8. Race position and gaps, measured at the rider's current distance so
            // they read as "how far up the road", not "how far apart in elapsed time".
            val gaps = GhostEngine.gapsAt(elapsedSec, distanceCovered, activeGhostSpecs, totalPolylineDist)
            val ahead = gaps.filter { it.gapSeconds > 0.0 }
            val behind = gaps.filter { it.gapSeconds <= 0.0 }
            val racePosition = if (gaps.isEmpty()) null else ahead.size + 1
            val gapAhead = ahead.minByOrNull { it.gapSeconds }?.gapSeconds
            val gapBehind = behind.maxByOrNull { it.gapSeconds }?.gapSeconds?.let { -it }

            _state.value = TrackingState(
                racePosition = racePosition,
                fieldSize = if (gaps.isEmpty()) 0 else gaps.size + 1,
                gapAheadSeconds = gapAhead,
                gapBehindSeconds = gapBehind,
                ghostGaps = gaps,
                segmentLengthMeters = totalPolylineDist,
                activeCourseId = course.id,
                courseName = course.name,
                distanceRemainingMeters = distanceRemaining,
                timeDeltaSeconds = timeDelta,
                elapsedSeconds = elapsedSec,
                prTimeSeconds = prRecord?.timeSeconds,
                progressRatio = progressRatio,
                riderLatLng = Pair(lat, lng),
                ghostLatLng = prGhost?.latLng,
                ghostBearing = prGhost?.bearing,
                ghostIsLinearFallback = prGhost?.isLinearFallback ?: false,
                ghostField = ghostField,
                activePolyline = decodedPolyline,
                activeEncodedPolyline = course.encodedPolyline,
                finished = null
            )
        }
    }

    private fun recordAttempt(course: Course, elapsedSeconds: Int, curve: List<CurvePoint>? = null) {

        val match = CourseMatch(
            courseId = course.id,
            activityId = "live-${System.currentTimeMillis()}",
            activityName = "Live Ride",
            date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
            timeSeconds = elapsedSeconds,
            curve = curve
        )
        // Fire-and-forget save on IO
        CoroutineScope(Dispatchers.IO + extensionExceptionHandler).launch {
            try {
                matchManager.saveMatches(listOf(match))
                // Update PR cache
                val currentPr = coursePrs[course.id]
                if (currentPr == null || elapsedSeconds < currentPr.timeSeconds) {
                    coursePrs[course.id] = PrRecord(elapsedSeconds, curve)
                    Log.i(TAG, "New PR for '${course.name}': ${elapsedSeconds}s")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save live attempt", e)
            }
        }
    }
}





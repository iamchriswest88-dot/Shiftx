package com.example.shift.extension

import com.example.shift.utils.PolylineUtils
import kotlin.math.*

/**
 * Standalone simulation of CourseTracker logic.
 * Uses the same haversine math, polyline projection, and detection thresholds
 * to verify the tracking algorithm works end-to-end.
 *
 * Run with: ./gradlew test --tests "com.example.shift.extension.CourseTrackerSimTest"
 */
class CourseTrackerSimTest {

    // ── Same constants as CourseTracker ──────────────────────────────────
    private val START_RADIUS_M = 40.0
    private val END_RADIUS_M = 40.0
    private val OFF_COURSE_M = 150.0

    // ── Same math as CourseTracker ───────────────────────────────────────
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2.0 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun polylineLength(pts: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 0 until pts.size - 1) {
            total += haversineMeters(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
        }
        return total
    }

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

    private fun distanceAlongPolyline(pts: List<Pair<Double, Double>>, closestIndex: Int, riderLat: Double, riderLon: Double): Double {
        var dist = 0.0
        for (i in 0 until closestIndex) {
            dist += haversineMeters(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
        }
        if (closestIndex < pts.size - 1) {
            val p1 = pts[closestIndex]
            val p2 = pts[closestIndex + 1]
            val segLen = haversineMeters(p1.first, p1.second, p2.first, p2.second)
            if (segLen > 0) {
                val t = projectOntoSegment(p1, p2, riderLat, riderLon)
                dist += t * segLen
            }
        }
        return dist
    }

    // ── Simulation State ────────────────────────────────────────────────
    data class SimCourse(
        val name: String,
        val startLat: Double, val startLng: Double,
        val endLat: Double, val endLng: Double,
        val polyline: List<Pair<Double, Double>>
    )

    enum class TrackerState { IDLE, ACTIVE, FINISHED, OFF_COURSE }

    data class SimResult(
        val state: TrackerState,
        val distanceRemaining: Double? = null,
        val distanceCovered: Double? = null,
        val closestDistToPolyline: Double? = null
    )

    private fun simulateUpdate(
        course: SimCourse,
        totalDist: Double,
        lat: Double, lng: Double
    ): SimResult {
        // Check start
        val distToStart = haversineMeters(lat, lng, course.startLat, course.startLng)
        val distToEnd = haversineMeters(lat, lng, course.endLat, course.endLng)

        // Find closest segment
        var minDist = Double.MAX_VALUE
        var closestIndex = 0
        for (i in 0 until course.polyline.size - 1) {
            val p1 = course.polyline[i]
            val p2 = course.polyline[i + 1]
            val dist = PolylineUtils.distanceToSegment(p1.first, p1.second, p2.first, p2.second, lat, lng)
            if (dist < minDist) {
                minDist = dist
                closestIndex = i
            }
        }

        // Off-course
        if (minDist > OFF_COURSE_M) {
            return SimResult(TrackerState.OFF_COURSE, closestDistToPolyline = minDist)
        }

        val covered = distanceAlongPolyline(course.polyline, closestIndex, lat, lng)
        val remaining = (totalDist - covered).coerceAtLeast(0.0)
        val progressRatio = if (totalDist > 0) (covered / totalDist).coerceIn(0.0, 1.0) else 0.0

        // End detection with progressRatio > 0.8 finish guard
        if (distToEnd < END_RADIUS_M && progressRatio > 0.8) {
            return SimResult(TrackerState.FINISHED, 0.0, totalDist, minDist)
        }

        return SimResult(TrackerState.ACTIVE, remaining, covered, minDist)
    }


    // ── Interpolate between two points ──────────────────────────────────
    private fun interpolate(p1: Pair<Double, Double>, p2: Pair<Double, Double>, t: Double): Pair<Double, Double> {
        return Pair(
            p1.first + (p2.first - p1.first) * t,
            p1.second + (p2.second - p1.second) * t
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  TESTS
    // ════════════════════════════════════════════════════════════════════

    @org.junit.Test
    fun test_haversine_known_distance() {
        // London to Paris ≈ 343.5 km
        val d = haversineMeters(51.5074, -0.1278, 48.8566, 2.3522)
        println("London→Paris: ${(d / 1000).toInt()} km")
        assert(d > 340_000 && d < 350_000) { "Expected ~343km, got ${d / 1000}km" }
        println("  ✅ haversine distance correct")
    }

    @org.junit.Test
    fun test_polyline_encode_decode_roundtrip() {
        val points = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5010, -0.0990),
            Pair(51.5020, -0.0970),
            Pair(51.5030, -0.0950)
        )
        val encoded = PolylineUtils.encodePolyline(points)
        val decoded = PolylineUtils.decodePolyline(encoded)

        assert(decoded.size == points.size) { "Point count mismatch" }
        for (i in points.indices) {
            val dLat = abs(decoded[i].first - points[i].first)
            val dLng = abs(decoded[i].second - points[i].second)
            assert(dLat < 0.00002 && dLng < 0.00002) { "Point $i drifted too far after encode/decode" }
        }
        println("  ✅ polyline encode/decode roundtrip accurate")
    }

    @org.junit.Test
    fun test_polyline_length_nonzero() {
        val points = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5010, -0.0990),
            Pair(51.5020, -0.0970),
            Pair(51.5030, -0.0950)
        )
        val length = polylineLength(points)
        println("  Polyline length: ${length.toInt()} m")
        assert(length > 100) { "Polyline length should be > 100m, got $length" }
        assert(length < 10000) { "Polyline length should be < 10km, got $length" }
        println("  ✅ polyline length is non-zero and reasonable")
    }

    @org.junit.Test
    fun test_start_detection() {
        val polyline = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5005, -0.0990),
            Pair(51.5010, -0.0980),
            Pair(51.5015, -0.0970),
            Pair(51.5020, -0.0960)
        )
        val course = SimCourse(
            name = "Test Segment",
            startLat = 51.5000, startLng = -0.1000,
            endLat = 51.5020, endLng = -0.0960,
            polyline = polyline
        )

        // Rider at start point
        val distAtStart = haversineMeters(51.5000, -0.1000, course.startLat, course.startLng)
        println("  Distance to start from start point: ${distAtStart.toInt()} m")
        assert(distAtStart < START_RADIUS_M) { "Should detect start, dist=$distAtStart" }

        // Rider 20m away from start (should still trigger)
        val nearStart = interpolate(Pair(51.5000, -0.1000), Pair(51.4999, -0.1002), 0.3)
        val distNear = haversineMeters(nearStart.first, nearStart.second, course.startLat, course.startLng)
        println("  Distance to start from nearby: ${distNear.toInt()} m")
        assert(distNear < START_RADIUS_M) { "Should detect start from nearby, dist=$distNear" }

        // Rider 500m away (should NOT trigger)
        val distFar = haversineMeters(51.5050, -0.1000, course.startLat, course.startLng)
        println("  Distance to start from 500m away: ${distFar.toInt()} m")
        assert(distFar > START_RADIUS_M) { "Should NOT detect start from far away, dist=$distFar" }

        println("  ✅ start detection works correctly")
    }

    @org.junit.Test
    fun test_end_detection() {
        val polyline = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5005, -0.0990),
            Pair(51.5010, -0.0980),
            Pair(51.5015, -0.0970),
            Pair(51.5020, -0.0960)
        )
        val course = SimCourse(
            name = "Test Segment",
            startLat = 51.5000, startLng = -0.1000,
            endLat = 51.5020, endLng = -0.0960,
            polyline = polyline
        )
        val totalDist = polylineLength(polyline)

        // Rider at end point
        val result = simulateUpdate(course, totalDist, 51.5020, -0.0960)
        println("  At end point: state=${result.state}, distToPolyline=${result.closestDistToPolyline?.toInt()}m")
        assert(result.state == TrackerState.FINISHED) { "Should detect finish at end point" }

        // Rider 15m from end (should still trigger)
        val nearEnd = interpolate(Pair(51.5020, -0.0960), Pair(51.5021, -0.0959), 0.5)
        val resultNear = simulateUpdate(course, totalDist, nearEnd.first, nearEnd.second)
        println("  Near end: state=${resultNear.state}")
        assert(resultNear.state == TrackerState.FINISHED) { "Should detect finish near end" }

        println("  ✅ end detection works correctly")
    }

    @org.junit.Test
    fun test_distance_decreases_along_route() {
        // Create a ~500m segment heading northeast
        val polyline = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5005, -0.0990),
            Pair(51.5010, -0.0980),
            Pair(51.5015, -0.0970),
            Pair(51.5020, -0.0960)
        )
        val course = SimCourse(
            name = "Test Segment",
            startLat = 51.5000, startLng = -0.1000,
            endLat = 51.5020, endLng = -0.0960,
            polyline = polyline
        )
        val totalDist = polylineLength(polyline)
        println("  Total segment distance: ${totalDist.toInt()} m")

        // Walk the rider along the polyline at 10 steps
        var prevRemaining = totalDist + 1
        val steps = 20
        println("  Simulating $steps GPS updates along route...")
        for (step in 0..steps) {
            val t = step.toDouble() / steps
            // Interpolate along the polyline segments
            val totalFrac = t * (polyline.size - 1)
            val segIdx = totalFrac.toInt().coerceAtMost(polyline.size - 2)
            val segFrac = totalFrac - segIdx
            val pos = interpolate(polyline[segIdx], polyline[segIdx + 1], segFrac)

            val result = simulateUpdate(course, totalDist, pos.first, pos.second)

            if (result.state == TrackerState.FINISHED) {
                println("    Step $step (t=${String.format("%.1f", t * 100)}%): FINISHED ✅")
                assert(step > steps * 0.7) { "Finished too early at step $step" }
                break
            }

            val remaining = result.distanceRemaining!!
            val covered = result.distanceCovered!!
            val offTrack = result.closestDistToPolyline!!

            println("    Step $step (t=${String.format("%.1f", t * 100)}%): remaining=${remaining.toInt()}m, covered=${covered.toInt()}m, offTrack=${String.format("%.1f", offTrack)}m")

            // Distance remaining should decrease (with small tolerance for interpolation)
            assert(remaining <= prevRemaining + 5.0) {
                "Distance remaining increased! prev=${prevRemaining.toInt()}, now=${remaining.toInt()}"
            }
            // Should be on-course
            assert(offTrack < 5.0) { "Should be on-course but off by ${offTrack}m" }

            prevRemaining = remaining
        }
        println("  ✅ distance decreases monotonically along route")
    }

    @org.junit.Test
    fun test_off_course_detection() {
        val polyline = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5010, -0.0980),
            Pair(51.5020, -0.0960)
        )
        val course = SimCourse(
            name = "Test Segment",
            startLat = 51.5000, startLng = -0.1000,
            endLat = 51.5020, endLng = -0.0960,
            polyline = polyline
        )
        val totalDist = polylineLength(polyline)

        // Rider 200m south of the polyline midpoint
        val result = simulateUpdate(course, totalDist, 51.4980, -0.0980)
        println("  200m off course: state=${result.state}, dist=${result.closestDistToPolyline?.toInt()}m")
        assert(result.state == TrackerState.OFF_COURSE) { "Should detect off-course" }

        // Rider 30m from polyline (should stay active)
        val resultNear = simulateUpdate(course, totalDist, 51.5011, -0.0980)
        println("  30m off course: state=${resultNear.state}, dist=${resultNear.closestDistToPolyline?.toInt()}m")
        assert(resultNear.state == TrackerState.ACTIVE) { "Should stay active when 30m off" }

        println("  ✅ off-course detection works correctly")
    }

    @org.junit.Test
    fun test_full_ride_simulation() {
        println("\n  ══════════════════════════════════════════")
        println("  FULL RIDE SIMULATION")
        println("  ══════════════════════════════════════════")

        // A realistic ~350m segment in London
        val polyline = listOf(
            Pair(51.50100, -0.14160),  // Start: near Hyde Park Corner
            Pair(51.50120, -0.14100),
            Pair(51.50150, -0.14020),
            Pair(51.50180, -0.13940),
            Pair(51.50210, -0.13860),
            Pair(51.50240, -0.13780),
            Pair(51.50270, -0.13700),
            Pair(51.50300, -0.13620),  // End
        )
        val course = SimCourse(
            name = "Hyde Park Sprint",
            startLat = polyline.first().first, startLng = polyline.first().second,
            endLat = polyline.last().first, endLng = polyline.last().second,
            polyline = polyline
        )
        val totalDist = polylineLength(polyline)
        println("  Course: '${course.name}', ${totalDist.toInt()}m, ${polyline.size} points")

        // Phase 1: Approaching — 100m away, should be IDLE
        val approach = haversineMeters(51.5000, -0.1416, course.startLat, course.startLng)
        println("\n  Phase 1 — Approaching (${approach.toInt()}m from start)")
        assert(approach > START_RADIUS_M) { "Should not trigger from approach distance" }
        println("  ✅ Not triggered while approaching")

        // Phase 2: Enter start zone
        val entryDist = haversineMeters(51.50100, -0.14160, course.startLat, course.startLng)
        println("\n  Phase 2 — At start (${entryDist.toInt()}m from start)")
        assert(entryDist < START_RADIUS_M) { "Should trigger at start" }
        println("  ✅ Start zone detected")

        // Phase 3: Ride through
        println("\n  Phase 3 — Riding through segment...")
        var detectedFinish = false
        val rideSteps = 30
        for (step in 1..rideSteps) {
            val t = step.toDouble() / rideSteps
            val totalFrac = t * (polyline.size - 1)
            val segIdx = totalFrac.toInt().coerceAtMost(polyline.size - 2)
            val segFrac = totalFrac - segIdx
            // Add slight GPS noise (±5m equivalent)
            val noise = if (step % 3 == 0) 0.00003 else -0.00002
            val pos = interpolate(polyline[segIdx], polyline[segIdx + 1], segFrac)
            val noisyPos = Pair(pos.first + noise, pos.second + noise)

            val result = simulateUpdate(course, totalDist, noisyPos.first, noisyPos.second)

            if (result.state == TrackerState.FINISHED) {
                println("    Step $step: ✅ FINISHED (covered ${totalDist.toInt()}m)")
                detectedFinish = true
                break
            }
            if (step % 5 == 0 || step == 1) {
                println("    Step $step: remaining=${result.distanceRemaining?.toInt()}m, covered=${result.distanceCovered?.toInt()}m")
            }
        }
        assert(detectedFinish) { "Rider should have finished the segment!" }

        // Phase 4: Off-course test
        println("\n  Phase 4 — Off-course detection")
        val offResult = simulateUpdate(course, totalDist, 51.5050, -0.1390)
        println("    Far from route: state=${offResult.state}")
        assert(offResult.state == TrackerState.OFF_COURSE) { "Should detect off-course" }
        println("  ✅ Off-course correctly detected")

        println("\n  ══════════════════════════════════════════")
        println("  ALL SIMULATION CHECKS PASSED ✅")
        println("  ══════════════════════════════════════════\n")
    }

    @org.junit.Test
    fun test_ghost_engine_interpolation() {
        val polyline = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5010, -0.0980),
            Pair(51.5020, -0.0960)
        )
        val cumDistances = listOf(0.0, 200.0, 400.0)
        val totalDist = 400.0

        val curve = listOf(
            com.example.shift.data.CurvePoint(0.0, 0.0),
            com.example.shift.data.CurvePoint(200.0, 30.0),
            com.example.shift.data.CurvePoint(400.0, 60.0)
        )
        val prRecord = PrRecord(60, curve)

        // At t=15s, ghost should be at 100m (progressRatio = 0.25)
        val pos = GhostEngine.calculateGhostPosition(prRecord, 15.0, polyline, cumDistances, totalDist)
        assert(pos != null)
        assert(pos?.latLng != null)
        assert(!pos!!.isLinearFallback)
        assert(abs(pos.progressRatio - 0.25) < 0.01)


        // Expected time at 100m should be 15s
        val expTime = GhostEngine.calculateExpectedTime(prRecord, 100.0, totalDist)
        assert(expTime != null && abs(expTime - 15.0) < 0.1)

        println("  ✅ GhostEngine curve interpolation accurate")
    }

    @org.junit.Test
    fun test_loop_false_finish_prevention() {
        // A loop segment where start and end are 10m apart
        val polyline = listOf(
            Pair(51.5000, -0.1000), // Start
            Pair(51.5050, -0.1000), // Far point 1
            Pair(51.5050, -0.0950), // Far point 2
            Pair(51.5001, -0.1000)  // End (10m from start)
        )
        val course = SimCourse(
            name = "Loop Segment",
            startLat = 51.5000, startLng = -0.1000,
            endLat = 51.5001, endLng = -0.1000,
            polyline = polyline
        )
        val totalDist = polylineLength(polyline)

        // Rider is at start gate, which is also 10m from end gate
        // At start (covered = 0m, progressRatio = 0.0), it MUST NOT trigger finish!
        val resultAtStart = simulateUpdate(course, totalDist, 51.5000, -0.1000)
        assert(resultAtStart.state != TrackerState.FINISHED) { "False finish detected on loop start!" }
        println("  ✅ Loop segment false finish prevented at start")
    }

    @org.junit.Test
    fun test_top5_ghost_selection() {
        val matches = listOf(
            com.example.shift.data.CourseMatch("c1", "act1", "Ride 1", "2026-01-01", 300),
            com.example.shift.data.CourseMatch("c1", "act2", "Ride 2", "2026-01-02", 250),
            com.example.shift.data.CourseMatch("c1", "act3", "Ride 3", "2026-01-03", 280),
            com.example.shift.data.CourseMatch("c1", "act2", "Ride 2 Dup", "2026-01-04", 240),
            com.example.shift.data.CourseMatch("c1", "act4", "Ride 4", "2026-01-05", 320),
            com.example.shift.data.CourseMatch("c1", "act5", "Ride 5", "2026-01-06", 260),
            com.example.shift.data.CourseMatch("c1", "act6", "Ride 6", "2026-01-07", 290)
        )

        val top5 = matches
            .distinctBy { it.activityId }
            .sortedBy { it.timeSeconds }
            .take(5)

        assert(top5.size == 5)
        assert(top5[0].timeSeconds == 250)
        assert(top5[1].timeSeconds == 260)
        assert(top5[2].timeSeconds == 280)
        assert(top5[3].timeSeconds == 290)
        assert(top5[4].timeSeconds == 300)

        val specs = top5.mapIndexed { idx, m -> GhostSpec(idx + 1, m.timeSeconds, m.curve) }
        assert(specs[0].rank == 1 && specs[0].timeSeconds == 250)
        assert(specs[4].rank == 5 && specs[4].timeSeconds == 300)

        println("  ✅ Top 5 ghost selection, ordering, and deduplication verified")
    }

    @org.junit.Test
    fun test_multighost_positions_at() {
        val polyline = listOf(
            Pair(51.5000, -0.1000),
            Pair(51.5010, -0.0980),
            Pair(51.5020, -0.0960)
        )
        val cumDistances = listOf(0.0, 200.0, 400.0)
        val totalDist = 400.0

        val curve = listOf(
            com.example.shift.data.CurvePoint(0.0, 0.0),
            com.example.shift.data.CurvePoint(200.0, 30.0),
            com.example.shift.data.CurvePoint(400.0, 60.0)
        )

        val ghosts = listOf(
            GhostSpec(rank = 1, timeSeconds = 60, curve = curve),
            GhostSpec(rank = 2, timeSeconds = 80, curve = null),
            GhostSpec(rank = 3, timeSeconds = 100, curve = null)
        )

        val activePositions = GhostEngine.positionsAt(40.0, ghosts, polyline, cumDistances, totalDist)
        assert(activePositions.size == 3)
        assert(activePositions[0].rank == 1 && !activePositions[0].finished && abs(activePositions[0].progressRatio - 0.6667) < 0.01)
        assert(activePositions[1].rank == 2 && !activePositions[1].finished && abs(activePositions[1].progressRatio - 0.5) < 0.01)
        assert(activePositions[2].rank == 3 && !activePositions[2].finished && abs(activePositions[2].progressRatio - 0.4) < 0.01)

        val endPositions = GhostEngine.positionsAt(70.0, ghosts, polyline, cumDistances, totalDist)
        val g1 = endPositions.find { it.rank == 1 }!!
        assert(g1.finished && g1.progressRatio == 1.0 && g1.latLng == polyline.last())

        val g2 = endPositions.find { it.rank == 2 }!!
        assert(!g2.finished && abs(g2.progressRatio - 0.875) < 0.01)

        println("  ✅ Multi-ghost positionsAt, curve vs fallback, and finish parking verified")
    }
}



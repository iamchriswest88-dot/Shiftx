package com.example.shift.data

import com.example.shift.utils.PolylineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.*
import kotlin.random.Random

data class LoopRouteResult(
    val encodedPolyline: String,
    val coordinates: List<Pair<Double, Double>>, // lat, lng
    val distanceMeters: Double,
    val durationSeconds: Double,
    val hadSpurWarning: Boolean = false,
    val spurCoordinates: List<Pair<Double, Double>>? = null,
    val ascentMeters: Double = 0.0,
    /** Fraction of the route riding a corridor it already rode the other way. */
    val retraceFraction: Double = 0.0,
    /** Fraction of the route on roads the rider has ridden before; null = no heatmap coverage here. */
    val familiarity: Double? = null,
    /** (cumulative metres, elevation metres) along the route, thinned for display. */
    val elevationProfile: List<Pair<Double, Double>>? = null
)

/** Rider's terrain wish, mapped to ORS steepness bands and to candidate scoring. */
enum class TerrainPreference(val steepnessDifficulty: Int) {
    FLAT(0),
    ROLLING(1),
    HILLY(3)
}

/** Compass wish for where the loop should head; absent = anywhere. */
enum class RideDirection(val bearingDeg: Double, val arrow: String) {
    NORTH(0.0, "↑"),
    EAST(90.0, "→"),
    SOUTH(180.0, "↓"),
    WEST(270.0, "←")
}

data class SpurDetectionResult(
    val hasSpur: Boolean,
    val maxSpurLengthMeters: Double,
    val spurPoints: List<Pair<Double, Double>>
)

class LoopRouteGenerator(private val orsClient: OpenRouteServiceClient) {
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }

    private fun haversineDestination(lat: Double, lng: Double, distanceMeters: Double, bearingDegrees: Double): Pair<Double, Double> {
        val r = 6371e3
        val angularDistance = distanceMeters / r
        val bearingRads = Math.toRadians(bearingDegrees)
        val latRads = Math.toRadians(lat)
        val lngRads = Math.toRadians(lng)

        val destLatRads = asin(
            sin(latRads) * cos(angularDistance) +
            cos(latRads) * sin(angularDistance) * cos(bearingRads)
        )
        val destLngRads = lngRads + atan2(
            sin(bearingRads) * sin(angularDistance) * cos(latRads),
            cos(angularDistance) - sin(latRads) * sin(destLatRads)
        )

        return Pair(Math.toDegrees(destLatRads), Math.toDegrees(destLngRads))
    }

    private suspend fun snapToNearestRoad(lat: Double, lng: Double): Pair<Double, Double> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://router.project-osrm.org/nearest/v1/cycling/$lng,$lat"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (bodyString != null) {
                        val root = json.parseToJsonElement(bodyString).jsonObject
                        val waypoints = root["waypoints"]?.jsonArray
                        if (!waypoints.isNullOrEmpty()) {
                            val location = waypoints[0].jsonObject["location"]?.jsonArray
                            if (location != null && location.size == 2) {
                                val outLng = location[0].jsonPrimitive.double
                                val outLat = location[1].jsonPrimitive.double
                                return@withContext Pair(outLat, outLng)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Pair(lat, lng) // fallback to original
        }
    }

    fun generateCircularWaypoints(
        startLat: Double, startLng: Double,
        targetDistanceMeters: Double,
        numPoints: Int = 6,
        rotationOffsetDegrees: Double = 0.0,
        radiusJitter: Double = 0.0,
        elliptical: Boolean = false
    ): List<Pair<Double, Double>> {
        val waypoints = mutableListOf<Pair<Double, Double>>()
        // approximate circle radius to get target circumference, accounting for roads meandering (x1.3)
        val baseRadius = targetDistanceMeters / (2 * Math.PI * 1.3)

        for (i in 0 until numPoints) {
            val angle = (360.0 / numPoints) * i + rotationOffsetDegrees
            var r = baseRadius
            if (elliptical) {
                // stretch E-W, compress N-S
                val rad = Math.toRadians(angle)
                r = baseRadius * (1.0 + 0.5 * abs(sin(rad)))
            }
            if (radiusJitter > 0) {
                r *= (1.0 + Random.nextDouble(-radiusJitter, radiusJitter))
            }
            
            waypoints.add(haversineDestination(startLat, startLng, r, angle))
        }
        return waypoints
    }

    private fun interpolatePolyline(points: List<Pair<Double, Double>>, intervalMeters: Double = 25.0): List<Pair<Double, Double>> {
        val interpolated = mutableListOf<Pair<Double, Double>>()
        if (points.isEmpty()) return interpolated
        
        interpolated.add(points[0])
        
        var remainingDist = intervalMeters
        var i = 0
        var currentPt = points[0]
        
        while (i < points.size - 1) {
            val nextPt = points[i + 1]
            val dist = haversineDistance(currentPt.first, currentPt.second, nextPt.first, nextPt.second)
            
            if (dist < remainingDist) {
                remainingDist -= dist
                currentPt = nextPt
                i++
            } else {
                val ratio = remainingDist / dist
                val newLat = currentPt.first + (nextPt.first - currentPt.first) * ratio
                val newLng = currentPt.second + (nextPt.second - currentPt.second) * ratio
                val newPt = Pair(newLat, newLng)
                interpolated.add(newPt)
                
                currentPt = newPt
                remainingDist = intervalMeters
            }
        }
        
        if (interpolated.last() != points.last()) {
            interpolated.add(points.last())
        }
        
        return interpolated
    }

    fun detectSpurs(points: List<Pair<Double, Double>>): SpurDetectionResult {
        if (points.size < 2) return SpurDetectionResult(false, 0.0, emptyList())

        val sampledPoints = interpolatePolyline(points, 25.0)

        var maxSpurLength = 0.0
        val spurPoints = mutableListOf<Pair<Double, Double>>()
        var hasSpur = false

        // Detect overlaps
        val overlapIndices = mutableSetOf<Int>()
        for (i in 0 until sampledPoints.size) {
            val ptA = sampledPoints[i]
            
            // Check points that are at least 2 samples ahead (50m route distance).
            // The user requested pruning right down to 2m.
            for (j in i + 2 until sampledPoints.size) {
                val ptB = sampledPoints[j]
                val latDiff = abs(ptA.first - ptB.first)
                val lngDiff = abs(ptA.second - ptB.second)
                if (latDiff > 0.002 || lngDiff > 0.002) continue

                val dist = haversineDistance(ptA.first, ptA.second, ptB.first, ptB.second)
                val routeDist = (j - i) * 25.0
                
                if (dist < 40.0 && routeDist > 60.0) { // Must be genuinely folding back, not just adjacent on a line
                    overlapIndices.add(i)
                    overlapIndices.add(j)
                }
            }
        }

        if (overlapIndices.isNotEmpty()) {
            val sortedOverlaps = overlapIndices.sorted()
            var currentSegmentLength = 0.0
            var maxSegmentLength = 0.0
            var currentSegmentPoints = mutableListOf<Pair<Double, Double>>()
            var bestSpurSegment = mutableListOf<Pair<Double, Double>>()

            for (i in 0 until sortedOverlaps.size) {
                val idx = sortedOverlaps[i]
                currentSegmentPoints.add(sampledPoints[idx])
                
                if (i > 0) {
                    val prevIdx = sortedOverlaps[i-1]
                    if (idx - prevIdx <= 3) { // Allowed gap of up to 75m in overlaps
                        val dist = haversineDistance(
                            sampledPoints[prevIdx].first, sampledPoints[prevIdx].second, 
                            sampledPoints[idx].first, sampledPoints[idx].second
                        )
                        currentSegmentLength += dist
                    } else {
                        // Break in segment
                        if (currentSegmentLength > maxSegmentLength) {
                            maxSegmentLength = currentSegmentLength
                            bestSpurSegment = currentSegmentPoints.toMutableList()
                        }
                        currentSegmentLength = 0.0
                        currentSegmentPoints.clear()
                        currentSegmentPoints.add(sampledPoints[idx])
                    }
                }
            }
            
            if (currentSegmentLength > maxSegmentLength) {
                maxSegmentLength = currentSegmentLength
                bestSpurSegment = currentSegmentPoints.toMutableList()
            }

            if (maxSegmentLength > 0.0) {
                hasSpur = true
                maxSpurLength = maxSegmentLength
                spurPoints.addAll(bestSpurSegment)
            }
        }

        return SpurDetectionResult(hasSpur, maxSpurLength, spurPoints)
    }

    /**
     * Fraction of the route spent retracing its own corridor in the opposite
     * direction — the precise signature of an out-and-back spur.
     *
     * The route is resampled every 20m onto a 30m grid; a sample that lands in a
     * cell an earlier part of THIS route crossed heading the opposite way (within
     * ±45°) is a retrace. A perpendicular self-crossing — the stick of a lollipop
     * route crossing its own loop — does not trigger it, which is what the older
     * proximity-only spur check could not distinguish.
     *
     * Unlike pruning, this scores the whole route including the classic stem at
     * the start/finish, which the pruner's keep-85% guard deliberately exempts.
     */
    fun retraceFraction(points: List<Pair<Double, Double>>): Double {
        if (points.size < 3) return 0.0
        val samples = interpolatePolyline(points, 20.0)
        if (samples.size < 6) return 0.0

        val cellSizeM = 30.0
        val latRef = Math.toRadians(samples[0].first)
        val mPerDegLat = 110_540.0
        val mPerDegLng = 111_320.0 * cos(latRef)

        // cell -> sample indices that crossed it, for bearing comparison
        val visited = HashMap<Long, MutableList<Int>>()
        val bearings = DoubleArray(samples.size)
        for (i in samples.indices) {
            val a = samples[max(0, i - 1)]
            val b = samples[min(samples.size - 1, i + 1)]
            bearings[i] = Math.toDegrees(
                atan2((b.second - a.second) * mPerDegLng, (b.first - a.first) * mPerDegLat)
            )
        }

        fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        var retraced = 0
        for (i in samples.indices) {
            val cx = floor(samples[i].second * mPerDegLng / cellSizeM).toInt()
            val cy = floor(samples[i].first * mPerDegLat / cellSizeM).toInt()

            var isRetrace = false
            outer@ for (dx in -1..1) {
                for (dy in -1..1) {
                    val earlier = visited[cellKey(cx + dx, cy + dy)] ?: continue
                    for (j in earlier) {
                        if (i - j < 5) continue // ≥100m apart along the route
                        // Physical gate: the same corridor means genuinely close.
                        // Cell adjacency alone let a parallel road 40-80m away
                        // count as a retrace, rejecting legitimate loops.
                        val pdx = (samples[i].second - samples[j].second) * mPerDegLng
                        val pdy = (samples[i].first - samples[j].first) * mPerDegLat
                        if (pdx * pdx + pdy * pdy > 35.0 * 35.0) continue
                        var diff = abs(bearings[i] - bearings[j]) % 360.0
                        if (diff > 180.0) diff = 360.0 - diff
                        if (diff > 135.0) { // within ±45° of straight back
                            isRetrace = true
                            break@outer
                        }
                    }
                }
            }
            if (isRetrace) retraced++
            visited.getOrPut(cellKey(cx, cy)) { mutableListOf() }.add(i)
        }
        return retraced.toDouble() / samples.size
    }

    /**
     * Isoperimetric roundness Q = 4πA/P²: 1.0 for a circle, π/4 ≈ 0.785 for a
     * square loop, near 0 for an out-and-back whose enclosed area is nothing.
     */
    fun roundness(points: List<Pair<Double, Double>>): Double {
        if (points.size < 4) return 0.0
        val latRef = Math.toRadians(points[0].first)
        val mPerDegLat = 110_540.0
        val mPerDegLng = 111_320.0 * cos(latRef)

        var area = 0.0
        var perimeter = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            val px = p.second * mPerDegLng
            val py = p.first * mPerDegLat
            val qx = q.second * mPerDegLng
            val qy = q.first * mPerDegLat
            area += px * qy - qx * py
            perimeter += sqrt((qx - px) * (qx - px) + (qy - py) * (qy - py))
        }
        if (perimeter <= 0.0) return 0.0
        return (4.0 * Math.PI * abs(area) / 2.0) / (perimeter * perimeter)
    }

    /**
     * Actively trace the route geometry and surgically snip off out-and-back spurs.
     */
    fun pruneSpurs(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        var currentPoints = points.toList()
        var pruned = true
        
        while (pruned) {
            pruned = false
            var bestStart = -1
            var bestEnd = -1
            var maxSpurLength = 0.0
            
            // We use interpolated points to easily measure route distance and find overlapping segments
            val interpolated = interpolatePolyline(currentPoints, 25.0)
            
            for (i in 0 until interpolated.size - 2) {
                val ptI = interpolated[i]
                for (j in i + 2 until interpolated.size) {
                    val ptJ = interpolated[j]

                    val latDiff = abs(ptI.first - ptJ.first)
                    val lngDiff = abs(ptI.second - ptJ.second)
                    if (latDiff > 0.002 || lngDiff > 0.002) continue

                    val physDist = haversineDistance(
                        ptI.first, ptI.second, 
                        ptJ.first, ptJ.second
                    )
                    val routeDist = (j - i) * 25.0
                    
                    if (physDist < 40.0 && routeDist > 60.0) {
                        var overlapPoints = 0
                        while (i + overlapPoints <= j - overlapPoints) {
                            val d = haversineDistance(
                                interpolated[i + overlapPoints].first, interpolated[i + overlapPoints].second,
                                interpolated[j - overlapPoints].first, interpolated[j - overlapPoints].second
                            )
                            if (d > 100.0) break
                            overlapPoints++
                        }
                        
                        val overlapDist = overlapPoints * 25.0
                        val totalDist = (j - i) * 25.0
                        
                        var isSpur = false
                        if (overlapDist > 0.0 || overlapDist >= (totalDist / 2) - 100.0) {
                            isSpur = true
                        }
                        
                        if (isSpur) {
                            // Do not prune if it would delete more than 85% of the entire route,
                            // as this is likely the main loop itself closing at the start point!
                            if (totalDist > maxSpurLength && totalDist < interpolated.size * 25.0 * 0.85) {
                                maxSpurLength = totalDist
                                bestStart = i
                                bestEnd = j
                            }
                        }
                    }
                }
            }
            
            if (bestStart != -1 && bestEnd != -1) {
                // Prune it! Splice the ends together, permanently deleting the spur.
                val newPoints = mutableListOf<Pair<Double, Double>>()
                newPoints.addAll(interpolated.subList(0, bestStart + 1))
                newPoints.addAll(interpolated.subList(bestEnd, interpolated.size))
                currentPoints = newPoints
                pruned = true
            }
        }
        
        return currentPoints
    }


    private class Scored(val score: Double, val terrainPenalty: Double, val result: LoopRouteResult)

    /**
     * Topological out-and-back removal (after Lewis & Corcoran, 2024). The
     * route is quantised onto a 30m grid and read as an undirected simple
     * graph; every out-and-back spur is then a chain hanging off the loop and
     * falls away by repeatedly deleting degree-1 nodes — at ANY separation
     * between the two passes, with no proximity gate to slip under. The
     * start's node is protected: the stem from the rider's door to the loop
     * is topologically mandatory, not a defect.
     */
    fun removeSpurTails(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size < 4) return points
        val samples = interpolatePolyline(points, 20.0)
        if (samples.size < 6) return points

        val cellM = 30.0
        val latRef = Math.toRadians(samples[0].first)
        val mPerDegLat = 110_540.0
        val mPerDegLng = 111_320.0 * cos(latRef)
        fun cellOf(pt: Pair<Double, Double>): Long {
            val cx = floor(pt.second * mPerDegLng / cellM).toInt()
            val cy = floor(pt.first * mPerDegLat / cellM).toInt()
            return (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        }

        val seq = mutableListOf<Long>()
        for (sample in samples) {
            val c = cellOf(sample)
            if (seq.isEmpty() || seq.last() != c) seq.add(c)
        }
        if (seq.size < 4) return points

        val adj = HashMap<Long, MutableSet<Long>>()
        for (i in 1 until seq.size) {
            val a = seq[i - 1]
            val b = seq[i]
            adj.getOrPut(a) { mutableSetOf() }.add(b)
            adj.getOrPut(b) { mutableSetOf() }.add(a)
        }

        val startCell = seq.first()
        val removed = HashSet<Long>()
        var frontier = adj.filter { it.value.size <= 1 && it.key != startCell }.keys.toMutableList()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Long>()
            for (leaf in frontier) {
                if (leaf in removed) continue
                removed.add(leaf)
                for (nb in adj[leaf] ?: emptySet<Long>()) {
                    val nbAdj = adj[nb] ?: continue
                    nbAdj.remove(leaf)
                    if (nbAdj.size <= 1 && nb != startCell && nb !in removed) next.add(nb)
                }
            }
            frontier = next
        }
        if (removed.isEmpty()) return points

        val kept = mutableListOf<Pair<Double, Double>>()
        for (sample in samples) {
            if (cellOf(sample) in removed) continue
            kept.add(sample)
        }
        // A route that was ALL (or almost all) spur — a pure out-and-back from
        // the start — collapses to a stub of samples clustered at the start
        // cell. Judge survival by remaining LENGTH, not point count: hand a
        // degenerate result back unchanged and let scoring condemn it instead.
        fun lengthOf(pts: List<Pair<Double, Double>>): Double {
            var d = 0.0
            for (i in 1 until pts.size) {
                d += haversineDistance(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
            }
            return d
        }
        return if (kept.size >= 4 && lengthOf(kept) >= lengthOf(samples) * 0.25) kept else points
    }

    /**
     * A forbidden corridor for avoid_polygons: small squares laid every ~200m
     * along a leg's geometry, skipping any square within [reliefM] of a stop
     * so later legs can still depart from and arrive at shared junctions.
     * Tiny squares keep every polygon far inside ORS's per-polygon extent and
     * area caps, and ~240m of width blocks the road actually ridden without
     * walling off the neighbourhood grid.
     */
    private fun buildAvoidCorridor(
        legPoints: List<Pair<Double, Double>>,
        reliefCenters: List<Pair<Double, Double>>,
        halfWidthM: Double = 120.0,
        reliefM: Double = 350.0
    ): List<List<Pair<Double, Double>>> {
        if (legPoints.size < 2) return emptyList()
        val rings = mutableListOf<List<Pair<Double, Double>>>()
        val samples = interpolatePolyline(legPoints, 200.0)
        val latRef = Math.toRadians(legPoints.first().first)
        val dLat = halfWidthM / 110_540.0
        val dLng = halfWidthM / (111_320.0 * cos(latRef))
        for (pt in samples) {
            val nearRelief = reliefCenters.any {
                haversineDistance(it.first, it.second, pt.first, pt.second) < reliefM
            }
            if (nearRelief) continue
            rings.add(
                listOf(
                    Pair(pt.first - dLat, pt.second - dLng),
                    Pair(pt.first - dLat, pt.second + dLng),
                    Pair(pt.first + dLat, pt.second + dLng),
                    Pair(pt.first + dLat, pt.second - dLng),
                    Pair(pt.first - dLat, pt.second - dLng)
                )
            )
        }
        return rings
    }

    private fun ringsToMultiPolygon(rings: List<List<Pair<Double, Double>>>): JsonObject =
        buildJsonObject {
            put("type", "MultiPolygon")
            put("coordinates", buildJsonArray {
                for (ring in rings) {
                    add(buildJsonArray {
                        add(buildJsonArray {
                            for (pt in ring) {
                                add(buildJsonArray {
                                    add(pt.second) // lng
                                    add(pt.first)  // lat
                                })
                            }
                        })
                    })
                }
            })
        }

    /**
     * Routes start→W1→…→Wn→start ONE LEG AT A TIME, each leg barred via
     * avoid_polygons from the roads earlier legs used — a hard version of the
     * reuse penalty routing engines apply internally during their search,
     * which the plain directions API entirely lacks (its legs have no memory
     * of each other; that gap was the main residual spur source). A leg that
     * avoidance makes unroutable — a lone bridge, a funnelled start — retries
     * without it: reuse beats no route, matching how the engines' own soft
     * penalties behave.
     */
    private suspend fun routeGuidedLoop(
        apiKey: String,
        stops: List<Pair<Double, Double>>, // lat/lng: start, W1..Wn, start
        steepness: Int
    ): RouteResult? {
        val allPoints = mutableListOf<Pair<Double, Double>>()
        val allElevations = mutableListOf<Double>()
        var distance = 0.0
        var duration = 0.0
        var ascent = 0.0
        val corridor = mutableListOf<List<Pair<Double, Double>>>()
        val relief = stops.dropLast(1) // start + waypoints (last stop == first)

        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            val coords = listOf(Pair(a.second, a.first), Pair(b.second, b.first))
            val avoid = if (corridor.isEmpty()) null else ringsToMultiPolygon(corridor)
            val leg = orsClient.getRoute(apiKey, coords, steepness, avoid)
                ?: orsClient.getRoute(apiKey, coords, steepness, null)
                ?: return null

            val pts = PolylineUtils.decodePolyline(leg.encodedPolyline)
            if (pts.size < 2) return null
            val legEle = if (leg.elevations.size == pts.size) leg.elevations else List(pts.size) { 0.0 }
            if (allPoints.isEmpty()) {
                allPoints.addAll(pts)
                allElevations.addAll(legEle)
            } else {
                allPoints.addAll(pts.drop(1))
                allElevations.addAll(legEle.drop(1))
            }
            distance += leg.distanceMeters
            duration += leg.durationSeconds
            ascent += leg.ascentMeters
            corridor.addAll(buildAvoidCorridor(pts, relief))
        }
        if (allPoints.size < 4) return null
        return RouteResult(
            PolylineUtils.encodePolyline(allPoints), distance, duration, 0.0, ascent, allElevations
        )
    }

    /**
     * Elevation profile (cumulative metres → elevation metres) for the FINAL
     * route geometry. Repair passes resample the line, so each final point
     * takes the elevation of the nearest original point via a coarse grid —
     * exact alignment survives only unrepaired routes, and 50m granularity is
     * ample for a phone-width graph. Thinned to ~200 samples for the UI.
     */
    private fun buildElevationProfile(
        pts: List<Pair<Double, Double>>,
        original: List<Pair<Double, Double>>,
        elevations: List<Double>
    ): List<Pair<Double, Double>>? {
        if (elevations.size != original.size || original.isEmpty() || pts.size < 2) return null

        val latRef = Math.toRadians(original[0].first)
        val mLat = 110_540.0
        val mLng = 111_320.0 * cos(latRef)
        val cellM = 50.0
        fun key(lat: Double, lng: Double): Long {
            val cx = floor(lng * mLng / cellM).toInt()
            val cy = floor(lat * mLat / cellM).toInt()
            return (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        }
        val grid = HashMap<Long, Int>()
        for (i in original.indices) grid.putIfAbsent(key(original[i].first, original[i].second), i)

        val out = ArrayList<Pair<Double, Double>>(pts.size)
        var cum = 0.0
        var lastEle = elevations.first()
        for (i in pts.indices) {
            if (i > 0) {
                cum += haversineDistance(
                    pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second
                )
            }
            val cx = floor(pts[i].second * mLng / cellM).toInt()
            val cy = floor(pts[i].first * mLat / cellM).toInt()
            var found = -1
            outer@ for (dx in -1..1) {
                for (dy in -1..1) {
                    val g = grid[((cx + dx).toLong() shl 32) or ((cy + dy).toLong() and 0xFFFFFFFFL)]
                    if (g != null) {
                        found = g
                        break@outer
                    }
                }
            }
            if (found >= 0) lastEle = elevations[found]
            out.add(Pair(cum, lastEle))
        }

        if (out.size <= 200) return out
        val step = out.size / 200.0
        val thin = ArrayList<Pair<Double, Double>>(202)
        var t = 0.0
        while (t < out.size) {
            thin.add(out[t.toInt()])
            t += step
        }
        if (thin.last() != out.last()) thin.add(out.last())
        return thin
    }

    /**
     * Prune, measure and score one ORS route as a tournament candidate.
     * Shared by both candidate sources — guided loops and random round
     * trips — so they compete under identical rules.
     */
    private fun scoreCandidate(
        routeResult: RouteResult,
        targetDistanceMeters: Double,
        terrain: TerrainPreference,
        heatmap: RideHeatmap?,
        useHeatmap: Boolean,
        direction: RideDirection? = null
    ): Scored? {
        val decoded = PolylineUtils.decodePolyline(routeResult.encodedPolyline)
        if (decoded.size < 4) return null

        // Repair pass 1: topological tail removal (any-width out-and-backs).
        // Repair pass 2: the proximity pruner, for thin same-corridor loops
        // the graph view sees as legitimate cycles.
        var pts = removeSpurTails(decoded)
        if (pts.size > 4 && detectSpurs(pts).hasSpur) {
            val pruned = pruneSpurs(pts)
            if (pruned.size < pts.size) pts = pruned
        }

        var distance = routeResult.distanceMeters
        var duration = routeResult.durationSeconds
        var ascent = routeResult.ascentMeters
        if (pts !== decoded) {
            var d = 0.0
            for (i in 1 until pts.size) {
                d += haversineDistance(
                    pts[i - 1].first, pts[i - 1].second,
                    pts[i].first, pts[i].second
                )
            }
            if (d > 0.0 && distance > 0.0) {
                val ratio = d / distance
                // Scale time and climb with the cut, else a pruned spur's
                // ascent inflates climb-per-km for exactly the candidates
                // that needed repair.
                duration *= ratio
                ascent *= ratio
            }
            distance = if (d > 0.0) d else distance
        }

        val retrace = retraceFraction(pts)
        val round = roundness(pts)
        val unpavedFrac =
            if (routeResult.distanceMeters > 0) routeResult.unpavedDistanceMeters / routeResult.distanceMeters else 0.0
        val distFit = abs(distance - targetDistanceMeters) / targetDistanceMeters
        val climbPerKm = if (distance > 0) ascent / (distance / 1000.0) else 0.0
        val terrainPenalty = when (terrain) {
            TerrainPreference.FLAT -> max(0.0, (climbPerKm - 6.0) / 20.0)
            TerrainPreference.ROLLING -> 0.0
            TerrainPreference.HILLY -> max(0.0, (12.0 - climbPerKm) / 20.0)
        }
        // Round trips cannot be aimed (ORS exposes no heading), so the compass
        // wish is enforced here: the bearing from start to the loop's centroid
        // versus the chosen direction. Weight 1.5 — decisive against random
        // seeds, still under retracing's 4.0, so a spurred loop pointing the
        // right way never beats a clean one slightly off-axis.
        val directionPenalty = if (direction != null && pts.size > 2) {
            var cLat = 0.0
            var cLng = 0.0
            for (p in pts) { cLat += p.first; cLng += p.second }
            cLat /= pts.size
            cLng /= pts.size
            val mLat = 110_540.0
            val mLng = 111_320.0 * cos(Math.toRadians(pts.first().first))
            val dy = (cLat - pts.first().first) * mLat
            val dx = (cLng - pts.first().second) * mLng
            if (sqrt(dx * dx + dy * dy) < 200.0) 0.0 // centroid too close: bearing is noise
            else {
                val centroidBearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                var diff = abs(centroidBearing - direction.bearingDeg) % 360.0
                if (diff > 180.0) diff = 360.0 - diff
                1.5 * (diff / 180.0)
            }
        } else 0.0

        val familiarity = if (useHeatmap) heatmap!!.familiarity(pts) else null
        // A spur on familiar roads is still a spur: the familiarity bonus
        // fades to nothing as retracing rises, so it can never subsidise
        // doubling back (previously a fully-familiar candidate could absorb
        // 30% retrace on the bonus alone).
        val famBonus = familiarity?.let { it * (1.0 - min(1.0, retrace * 5.0)) }

        // Retracing dominates: a clean loop 15% off target beats a
        // spur-ridden one bang on distance.
        val score = 4.0 * retrace +
            2.0 * min(1.0, unpavedFrac * 10.0) +
            distFit +
            terrainPenalty +
            directionPenalty +
            0.3 * (1.0 - min(1.0, round / 0.55)) +
            (if (famBonus != null) 1.2 * (1.0 - famBonus) else 0.0)

        val candidate = LoopRouteResult(
            encodedPolyline = PolylineUtils.encodePolyline(pts),
            coordinates = pts,
            distanceMeters = distance,
            durationSeconds = duration,
            hadSpurWarning = unpavedFrac > 0.02,
            spurCoordinates = null,
            ascentMeters = ascent,
            retraceFraction = retrace,
            familiarity = familiarity,
            elevationProfile = buildElevationProfile(pts, decoded, routeResult.elevations)
        )
        return Scored(score, terrainPenalty, candidate)
    }

    /**
     * Candidate tournament with two sources. Heatmap-GUIDED candidates route
     * leg-by-leg through waypoints picked on the rider's own ridden roads,
     * with earlier legs' corridors forbidden to later legs; random round
     * trips fill the field where history is thin. Everything competes under
     * identical scoring, and — the Strava trade, adopted deliberately — the
     * final pick is loose on distance, strict on quality: a clean loop within
     * 25% of target always beats a spurred one bang on distance.
     */
    /**
     * [quick] trims the field (2 guided + 4 round-trip candidates instead of
     * 3 + 8) for callers generating several terrains in one tap, keeping the
     * whole trio inside the API's per-minute budget.
     */
    suspend fun generateLoopRoute(
        apiKey: String,
        startLat: Double, startLng: Double,
        targetDistanceMeters: Double,
        terrain: TerrainPreference = TerrainPreference.ROLLING,
        heatmap: RideHeatmap? = null,
        quick: Boolean = false,
        direction: RideDirection? = null,
        onStatusUpdate: (String) -> Unit = {}
    ): LoopRouteResult? = withContext(Dispatchers.Default) {
        val useHeatmap = heatmap != null && !heatmap.isEmpty &&
            heatmap.hasCoverageNear(startLat, startLng)
        val toleranceMeters = max(500.0, targetDistanceMeters * 0.1)

        var best: LoopRouteResult? = null
        var bestScore = Double.MAX_VALUE
        var bestTerrainPenalty = 0.0
        var bestClean: LoopRouteResult? = null
        var bestCleanScore = Double.MAX_VALUE
        var attempt = 0

        fun consider(scored: Scored?) {
            if (scored == null) return
            if (scored.score < bestScore) {
                bestScore = scored.score
                best = scored.result
                bestTerrainPenalty = scored.terrainPenalty
            }
            val cleanDistFit =
                abs(scored.result.distanceMeters - targetDistanceMeters) / targetDistanceMeters
            if (scored.result.retraceFraction < 0.02 && cleanDistFit <= 0.25 &&
                scored.score < bestCleanScore
            ) {
                bestCleanScore = scored.score
                bestClean = scored.result
            }
        }

        fun leaderIsSpotless(): Boolean {
            val leader = best ?: return false
            return leader.retraceFraction < 0.02 &&
                abs(leader.distanceMeters - targetDistanceMeters) <= toleranceMeters &&
                !leader.hadSpurWarning &&
                bestTerrainPenalty <= 0.15
        }

        // ── Source 1: guided loops on the rider's own roads, leg-by-leg ──
        if (useHeatmap) {
            val radiusFactors = if (quick) listOf(1.0, 0.85) else listOf(1.0, 0.85, 1.15)
            for (f in radiusFactors) {
                attempt++
                onStatusUpdate("Trying loops on your roads ($attempt)...")
                val rotation = Random.nextDouble(0.0, 360.0)
                val waypoints = heatmap!!.waypointsNear(
                    startLat, startLng, targetDistanceMeters * f,
                    sectors = 4, rotationDeg = rotation,
                    directionDeg = direction?.bearingDeg
                )
                if (waypoints.size < 3) continue
                // Cell centers sit up to ~17m off the ridden road; snapping
                // stops ORS attaching a waypoint to a parallel driveway and
                // micro-out-and-backing to touch it.
                val snapped = waypoints.map { snapToNearestRoad(it.first, it.second) }
                val stops = buildList {
                    add(Pair(startLat, startLng))
                    addAll(snapped)
                    add(Pair(startLat, startLng))
                }
                val routeResult = routeGuidedLoop(apiKey, stops, terrain.steepnessDifficulty) ?: continue
                consider(scoreCandidate(routeResult, targetDistanceMeters, terrain, heatmap, useHeatmap, direction))
                // A spotless guided loop is the whole point — stop here.
                if (leaderIsSpotless()) break
            }
        }

        // ── Source 2: random round trips, filling the field ──
        if (!leaderIsSpotless()) {
            val correctionFactors =
                if (quick) listOf(1.0, 0.82, 0.75, 0.68)
                else listOf(1.0, 0.82, 1.0, 0.82, 0.75, 1.0, 0.82, 0.68)
            for ((attemptIdx, factor) in correctionFactors.withIndex()) {
                attempt++
                onStatusUpdate("Trying loops ($attempt)...")
                val seed = Random.nextInt(100000)
                val routeResult = orsClient.getRoundTripRoute(
                    apiKey, startLng, startLat,
                    targetDistanceMeters * factor, seed, terrain.steepnessDifficulty
                ) ?: continue
                consider(scoreCandidate(routeResult, targetDistanceMeters, terrain, heatmap, useHeatmap, direction))
                if (attemptIdx >= 2 && leaderIsSpotless()) break
            }
        }

        // The quality floor: never hand back a spurred loop while a clean one
        // within 25% of the asked distance exists.
        val overall = best
        val clean = bestClean
        return@withContext if (clean != null && overall != null &&
            overall.retraceFraction >= 0.05 && overall !== clean
        ) clean else overall
    }
}
